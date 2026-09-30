package com.lansync.client;

import com.lansync.common.FileMetadata;
import com.lansync.common.Message;
import com.lansync.common.MessageType;
import com.lansync.common.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Điều phối đồng bộ file giữa FileWatcher và SyncClient.
 *
 * Nhiệm vụ:
 * 1. Lắng nghe sự kiện từ FileWatcher → upload lên server
 * 2. Lắng nghe bản tin từ Server → download file về
 * 3. Xử lý conflict
 * 4. Đồng bộ bù sau khi reconnect
 */
public class SyncEngine {
    private static final Logger LOGGER = Logger.getLogger(SyncEngine.class.getName());

    private final SyncClient syncClient;
    private final FileWatcher fileWatcher;
    private final Path syncDir;

    private final ExecutorService uploadExecutor  = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "UploadThread"));
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "DownloadThread"));

    /** Danh sách file hiện có trong hệ thống (cập nhật từ server) */
    private final List<FileMetadata> fileList = new ArrayList<>();

    // ===== LISTENERS CHO GUI =====
    /** Được gọi khi danh sách file thay đổi */
    private Consumer<List<FileMetadata>> fileListListener;
    /** Được gọi khi có sự kiện log */
    private Consumer<String> logListener;
    /** Được gọi khi trạng thái đồng bộ thay đổi */
    private BiConsumer<String, SyncStatus> statusListener;
    /** Được gọi khi có conflict */
    private BiConsumer<String, String> conflictListener;

    public enum SyncStatus { SYNCED, SYNCING, ERROR, DELETED }

    public SyncEngine(SyncClient syncClient, FileWatcher fileWatcher, Path syncDir) {
        this.syncClient = syncClient;
        this.fileWatcher = fileWatcher;
        this.syncDir     = syncDir;

        // Đăng ký xử lý sự kiện từ FileWatcher
        fileWatcher.setEventCallback(this::onLocalFileEvent);

        // Đăng ký xử lý bản tin từ Server
        syncClient.setMessageListener(this::onServerMessage);

        // Đăng ký nhận file
        syncClient.setFileReceiveCallback(this::onReceiveFile);
    }

    // ===================================================
    //  XỬ LÝ SỰ KIỆN LOCAL (FileWatcher → Upload)
    // ===================================================

    /**
     * Xử lý khi FileWatcher phát hiện thay đổi trong thư mục đồng bộ.
     */
    private void onLocalFileEvent(String filename, FileWatcher.FileEvent event) {
        if (!syncClient.isConnected()) {
            log("⚠️  Không kết nối — bỏ qua sự kiện: " + event + " | " + filename);
            return;
        }

        switch (event) {
            case CREATED  -> uploadFile(filename, MessageType.FILE_ADDED);
            case MODIFIED -> uploadFile(filename, MessageType.FILE_UPDATED);
            case DELETED  -> {
                log("🗑️  File bị xóa: " + filename);
                syncClient.sendFileDeleted(filename);
                removeFromFileList(filename);
                notifyStatus(filename, SyncStatus.DELETED);
            }
        }
    }

    /**
     * Upload file lên server trong background thread.
     */
    private void uploadFile(String filename, MessageType type) {
        uploadExecutor.submit(() -> {
            File file = fileWatcher.getFile(filename);
            if (!file.exists() || file.isDirectory()) return;

            // Đợi file ghi xong (tránh đọc file đang được ghi)
            waitForFileStable(file);

            try {
                notifyStatus(filename, SyncStatus.SYNCING);
                log("⬆️  Đang upload: " + filename);

                String sha256 = Utils.sha256OfFile(file);
                if (sha256 == null) {
                    log("❌ Không tính được hash: " + filename);
                    notifyStatus(filename, SyncStatus.ERROR);
                    return;
                }

                FileMetadata meta = new FileMetadata(
                    filename, sha256, file.length(),
                    file.lastModified(), syncClient.getClientId()
                );

                syncClient.sendFile(type, meta, file, (transferred, total) -> {
                    // Có thể cập nhật progress bar ở đây
                });

                updateFileList(meta);
                notifyStatus(filename, SyncStatus.SYNCED);
                log("✅ Upload xong: " + filename + " (" + meta.getReadableSize() + ")");

            } catch (IOException e) {
                LOGGER.severe("SyncEngine: Lỗi upload " + filename + ": " + e.getMessage());
                log("❌ Lỗi upload: " + filename + " — " + e.getMessage());
                notifyStatus(filename, SyncStatus.ERROR);
            }
        });
    }

    /**
     * Chờ file ổn định (không bị ghi thêm) trước khi upload.
     */
    private void waitForFileStable(File file) {
        long prevSize = -1;
        int stableCount = 0;
        try {
            while (stableCount < 3) {
                long currentSize = file.length();
                if (currentSize == prevSize) stableCount++;
                else { stableCount = 0; prevSize = currentSize; }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===================================================
    //  XỬ LÝ BẢN TIN TỪ SERVER (Download / Sync)
    // ===================================================

    /**
     * Xử lý bản tin từ Server.
     */
    private void onServerMessage(Message msg) {
        switch (msg.getType()) {
            case LOGIN_OK -> handleLoginOk(msg);
            case LOGIN_FAIL -> log("❌ Đăng nhập thất bại: " + msg.getPayloadString("reason"));
            case REGISTER_OK -> log("✅ " + msg.getPayloadString("message"));
            case REGISTER_FAIL -> log("❌ Đăng ký thất bại: " + msg.getPayloadString("reason"));
            case FILE_LIST_RESPONSE -> handleFileListResponse(msg);
            case SYNC_NOTIFY -> handleSyncNotify(msg);
            case CONFLICT_NOTIFY -> handleConflictNotify(msg);
            case VERSION_HISTORY_RESPONSE -> handleVersionHistoryResponse(msg);
            case HEARTBEAT -> syncClient.send(Message.createHeartbeatAck());
            case HEARTBEAT_ACK -> {}
            case ERROR -> log("⚠️  Lỗi từ server: " + msg.getPayloadString("error"));
            default -> LOGGER.fine("SyncEngine: Bản tin không xử lý: " + msg.getType());
        }
    }

    private void handleLoginOk(Message msg) {
        String welcome = msg.getPayloadString("message");
        log("✅ " + welcome);
        // Yêu cầu đồng bộ bù
        syncClient.requestSync();
    }

    private void handleFileListResponse(Message msg) {
        FileMetadata[] files = msg.getPayloadFilesArray();
        synchronized (fileList) {
            fileList.clear();
            for (FileMetadata f : files) fileList.add(f);
        }
        notifyFileListChanged();
        log("📋 Nhận danh sách file: " + files.length + " file(s)");
    }

    /**
     * Xử lý thông báo đồng bộ từ server — download file về.
     */
    private void handleSyncNotify(Message msg) {
        String action   = msg.getPayloadString("action");
        String source   = msg.getPayloadString("sourceClient");
        String filename = msg.getPayloadString("filename");

        if ("DELETE".equals(action)) {
            // Xóa file tương ứng
            deleteLocalFile(filename, source);
            return;
        }

        if ("RENAME".equals(action)) {
            String oldName = msg.getPayloadString("oldName");
            String newName = msg.getPayloadString("newName");
            renameLocalFile(oldName, newName, source);
            return;
        }

        // Còn lại: thêm/cập nhật file
        FileMetadata meta = msg.getPayloadAsFileMetadata();
        if (meta == null || Utils.isBlank(meta.getFilename())) return;

        log("🔔 Thông báo đồng bộ từ [" + source + "]: " + meta.getFilename());
        downloadFile(meta);
    }

    /**
     * Tải file từ server về thư mục đồng bộ.
     */
    private void downloadFile(FileMetadata meta) {
        downloadExecutor.submit(() -> {
            try {
                notifyStatus(meta.getFilename(), SyncStatus.SYNCING);
                log("⬇️  Đang tải: " + meta.getFilename());

                // Báo FileWatcher bỏ qua sự kiện cho file này
                fileWatcher.ignoreFile(meta.getFilename());

                // Yêu cầu server gửi file
                syncClient.requestFile(meta.getFilename(), meta.getVersion());
                // File sẽ được nhận qua onReceiveFile callback

            } catch (Exception e) {
                LOGGER.severe("SyncEngine: Lỗi tải file: " + e.getMessage());
                log("❌ Lỗi tải file: " + meta.getFilename());
                notifyStatus(meta.getFilename(), SyncStatus.ERROR);
                fileWatcher.unignoreFile(meta.getFilename());
            }
        });
    }

    /**
     * Callback được gọi bởi SyncClient khi server bắt đầu gửi file.
     * Đọc bytes file và lưu vào thư mục đồng bộ.
     */
    private void onReceiveFile(FileMetadata meta, InputStream rawIn) throws IOException {
        String filename = meta.getFilename();
        File dest = syncDir.resolve(filename).toFile();

        try {
            // Đảm bảo thư mục tồn tại
            dest.getParentFile().mkdirs();

            // Nhận bytes file
            Utils.receiveFile(rawIn, dest, (transferred, total) -> {
                // Progress: có thể cập nhật UI ở đây
            });

            // Cập nhật danh sách
            updateFileList(meta);
            notifyStatus(filename, SyncStatus.SYNCED);
            log("✅ Đã nhận: " + filename + " (" + meta.getReadableSize() + ")");

        } finally {
            // Bỏ ignore sau khi lưu xong
            fileWatcher.unignoreFile(filename);
        }
    }

    private void handleConflictNotify(Message msg) {
        String originalFile = msg.getPayloadString("originalFile");
        String conflictFile = msg.getPayloadString("conflictFile");
        String reason       = msg.getPayloadString("reason");

        log("⚠️  CONFLICT: " + originalFile + " → " + conflictFile);
        if (conflictListener != null) {
            conflictListener.accept(conflictFile, reason);
        }
    }

    private void handleVersionHistoryResponse(Message msg) {
        String filename = msg.getPayloadString("filename");
        FileMetadata[] versions = msg.getPayloadVersionsArray();
        log("📜 Lịch sử phiên bản: " + filename + " → " + versions.length + " phiên bản");
        // Delegate cho GUI xử lý thông qua messageListener
    }

    // ===================================================
    //  THAO TÁC FILE LOCAL
    // ===================================================

    private void deleteLocalFile(String filename, String source) {
        if (filename == null) return;
        File file = syncDir.resolve(filename).toFile();
        fileWatcher.ignoreFile(filename);
        if (file.exists()) {
            if (file.delete()) {
                log("🗑️  Đã xóa file (theo " + source + "): " + filename);
            }
        }
        removeFromFileList(filename);
        notifyStatus(filename, SyncStatus.DELETED);
        fileWatcher.unignoreFile(filename);
    }

    private void renameLocalFile(String oldName, String newName, String source) {
        if (oldName == null || newName == null) return;
        File oldFile = syncDir.resolve(oldName).toFile();
        File newFile = syncDir.resolve(newName).toFile();
        fileWatcher.ignoreFile(oldName);
        fileWatcher.ignoreFile(newName);
        if (oldFile.exists()) {
            oldFile.renameTo(newFile);
            log("✏️  Đổi tên (theo " + source + "): " + oldName + " → " + newName);
        }
        fileWatcher.unignoreFile(oldName);
        fileWatcher.unignoreFile(newName);
        notifyFileListChanged();
    }

    // ===================================================
    //  QUẢN LÝ FILE LIST
    // ===================================================

    private void updateFileList(FileMetadata meta) {
        synchronized (fileList) {
            fileList.removeIf(f -> f.getFilename().equals(meta.getFilename()));
            fileList.add(meta);
        }
        notifyFileListChanged();
    }

    private void removeFromFileList(String filename) {
        synchronized (fileList) {
            fileList.removeIf(f -> f.getFilename().equals(filename));
        }
        notifyFileListChanged();
    }

    public List<FileMetadata> getFileList() {
        synchronized (fileList) {
            return new ArrayList<>(fileList);
        }
    }

    // ===================================================
    //  THÔNG BÁO CHO GUI
    // ===================================================

    private void notifyFileListChanged() {
        if (fileListListener != null) {
            fileListListener.accept(getFileList());
        }
    }

    private void notifyStatus(String filename, SyncStatus status) {
        if (statusListener != null) {
            statusListener.accept(filename, status);
        }
    }

    private void log(String msg) {
        LOGGER.info(msg);
        if (logListener != null) {
            logListener.accept(msg);
        }
    }

    /**
     * Dừng tất cả executor.
     */
    public void shutdown() {
        uploadExecutor.shutdown();
        downloadExecutor.shutdown();
        fileWatcher.stop();
    }

    // ===== SETTERS =====
    public void setFileListListener(Consumer<List<FileMetadata>> l) { this.fileListListener = l; }
    public void setLogListener(Consumer<String> l) { this.logListener = l; }
    public void setStatusListener(BiConsumer<String, SyncStatus> l) { this.statusListener = l; }
    public void setConflictListener(BiConsumer<String, String> l) { this.conflictListener = l; }
}

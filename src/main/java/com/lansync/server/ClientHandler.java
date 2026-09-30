package com.lansync.server;

import com.lansync.common.FileMetadata;
import com.lansync.common.Message;
import com.lansync.common.MessageType;
import com.lansync.common.Utils;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

/**
 * Xử lý kết nối TCP của một Client cụ thể trên Server.
 * Mỗi ClientHandler chạy trên một Thread riêng biệt.
 *
 * Luồng xử lý:
 * 1. Chờ LOGIN / REGISTER
 * 2. Sau khi xác thực → xử lý các lệnh file (ADDED/UPDATED/DELETED/...)
 * 3. Khi ngắt kết nối → dọn dẹp tài nguyên
 */
public class ClientHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final SyncServer server;
    private final AuthManager authManager;
    private final MetadataManager metadataManager;

    private BufferedReader reader;
    private PrintWriter writer;
    private InputStream rawInputStream;
    private OutputStream rawOutputStream;

    private String clientId;       // username
    private String clientAddress;  // IP:port
    private boolean authenticated = false;
    private boolean running = true;

    public ClientHandler(Socket socket, SyncServer server,
                         AuthManager authManager, MetadataManager metadataManager) {
        this.socket = socket;
        this.server = server;
        this.authManager = authManager;
        this.metadataManager = metadataManager;
        this.clientAddress = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    @Override
    public void run() {
        LOGGER.info("ClientHandler: Kết nối mới từ " + clientAddress);
        try {
            reader = Utils.createReader(socket);
            writer = Utils.createWriter(socket);
            rawInputStream = socket.getInputStream();
            rawOutputStream = socket.getOutputStream();

            while (running && !socket.isClosed()) {
                Message msg = Utils.receiveMessage(reader);
                if (msg == null) break; // Client ngắt kết nối

                handleMessage(msg);
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.warning("ClientHandler [" + clientAddress + "]: " + e.getMessage());
            }
        } finally {
            disconnect();
        }
    }

    /**
     * Điều phối xử lý bản tin dựa theo MessageType.
     */
    private void handleMessage(Message msg) throws IOException {
        MessageType type = msg.getType();
        LOGGER.fine("ClientHandler [" + clientId + "]: Nhận " + type);

        // Bản tin xác thực không cần authenticated
        if (type == MessageType.LOGIN) { handleLogin(msg); return; }
        if (type == MessageType.REGISTER) { handleRegister(msg); return; }

        // Các bản tin còn lại yêu cầu đã đăng nhập
        if (!authenticated) {
            sendMessage(Message.createError("Chưa xác thực. Vui lòng đăng nhập trước."));
            return;
        }

        switch (type) {
            case FILE_ADDED   -> handleFileAdded(msg);
            case FILE_UPDATED -> handleFileUpdated(msg);
            case FILE_DELETED -> handleFileDeleted(msg);
            case FILE_RENAMED -> handleFileRenamed(msg);
            case REQUEST_FILE -> handleRequestFile(msg);
            case FILE_LIST_REQUEST -> handleFileListRequest();
            case REQUEST_SYNC -> handleRequestSync(msg);
            case VERSION_HISTORY_REQUEST -> handleVersionHistoryRequest(msg);
            case RESTORE_VERSION -> handleRestoreVersion(msg);
            case HEARTBEAT    -> sendMessage(Message.createHeartbeatAck());
            case LOGOUT       -> { running = false; }
            default           -> LOGGER.warning("ClientHandler: Bản tin không xác định: " + type);
        }
    }

    // ===================================================
    //  XÁC THỰC
    // ===================================================

    private void handleLogin(Message msg) {
        String username = msg.getPayloadString("username");
        String password = msg.getPayloadString("password");

        if (authManager.authenticate(username, password)) {
            this.clientId = username.trim().toLowerCase();
            this.authenticated = true;
            server.registerClient(clientId, this);

            String displayName = authManager.getDisplayName(clientId);
            sendMessage(Message.createLoginOk(clientId, "Xin chào, " + displayName + "!"));

            // Gửi danh sách file hiện tại
            sendFileList();
            LOGGER.info("ClientHandler: Đăng nhập thành công → " + clientId + " từ " + clientAddress);

            // Thông báo các client khác về sự kiện online
            server.broadcast(Message.createSyncNotify(new FileMetadata(), clientId), clientId);
            server.notifyClientEvent(clientId, true);
        } else {
            sendMessage(Message.createLoginFail("Tài khoản hoặc mật khẩu không đúng."));
        }
    }

    private void handleRegister(Message msg) {
        String username = msg.getPayloadString("username");
        String password = msg.getPayloadString("password");
        String displayName = msg.getPayloadString("displayName");

        if (Utils.isBlank(username) || Utils.isBlank(password)) {
            sendMessage(Message.createError("Username và password không được để trống."));
            return;
        }
        if (username.length() < 3) {
            sendMessage(Message.createError("Username phải có ít nhất 3 ký tự."));
            return;
        }
        if (password.length() < 6) {
            sendMessage(Message.createError("Mật khẩu phải có ít nhất 6 ký tự."));
            return;
        }

        if (authManager.register(username, password, displayName)) {
            Message okMsg = new Message(MessageType.REGISTER_OK);
            okMsg.addPayload("message", "Đăng ký thành công! Vui lòng đăng nhập.");
            sendMessage(okMsg);
        } else {
            Message failMsg = new Message(MessageType.REGISTER_FAIL);
            failMsg.addPayload("reason", "Username '" + username + "' đã tồn tại.");
            sendMessage(failMsg);
        }
    }

    // ===================================================
    //  XỬ LÝ FILE
    // ===================================================

    /**
     * Xử lý khi client thêm file mới.
     * Flow: nhận metadata → nhận bytes file → lưu → broadcast SYNC_NOTIFY
     */
    private void handleFileAdded(Message msg) throws IOException {
        FileMetadata meta = msg.getPayloadAsFileMetadata();
        meta.setOwnerClient(clientId);
        LOGGER.info("ClientHandler [" + clientId + "]: FILE_ADDED → " + meta.getFilename());

        // Nhận dữ liệu file từ client
        receiveAndStoreFile(meta, false);
    }

    /**
     * Xử lý khi client cập nhật file.
     * Kiểm tra xung đột trước khi lưu.
     */
    private void handleFileUpdated(Message msg) throws IOException {
        FileMetadata clientMeta = msg.getPayloadAsFileMetadata();
        clientMeta.setOwnerClient(clientId);
        LOGGER.info("ClientHandler [" + clientId + "]: FILE_UPDATED → " + clientMeta.getFilename());

        boolean isConflict = metadataManager.isConflict(clientMeta);

        if (isConflict) {
            // Xử lý conflict: lưu file client thành tên mới
            String conflictName = Utils.createConflictFilename(clientMeta.getFilename(), clientId);
            LOGGER.warning("ClientHandler [" + clientId + "]: CONFLICT → " + conflictName);

            FileMetadata conflictMeta = new FileMetadata(
                conflictName, clientMeta.getSha256(),
                clientMeta.getFileSize(), clientMeta.getLastModified(), clientId
            );
            receiveAndStoreFile(conflictMeta, false);

            // Thông báo conflict cho client gửi
            sendMessage(Message.createConflictNotify(
                clientMeta.getFilename(), conflictName,
                "File đã được chỉnh sửa bởi máy khác. Bản của bạn được lưu thành: " + conflictName
            ));

            // Broadcast để các client khác cũng nhận file conflict
            server.broadcast(Message.createSyncNotify(conflictMeta, clientId), clientId);
        } else {
            receiveAndStoreFile(clientMeta, false);
        }
    }

    /**
     * Nhận bytes file từ socket và lưu vào server_store.
     * Sau đó cập nhật metadata DB và broadcast SYNC_NOTIFY.
     */
    private void receiveAndStoreFile(FileMetadata meta, boolean isConflict) throws IOException {
        metadataManager.upsertFile(meta);

        // Tính đường dẫn lưu trữ
        String storedPath = MetadataManager.SERVER_STORE + File.separator
                + meta.getFilename() + ".v" + meta.getVersion();
        File storedFile = new File(storedPath);
        storedFile.getParentFile().mkdirs();

        // Nhận file từ socket
        Utils.receiveFile(rawInputStream, storedFile, (transferred, total) ->
            LOGGER.fine("  Nhận file: " + transferred + "/" + total)
        );

        LOGGER.info("ClientHandler [" + clientId + "]: Đã lưu file → " + storedPath
                + " (" + meta.getReadableSize() + ")");

        // Thông báo tất cả client khác cần đồng bộ
        server.broadcast(Message.createSyncNotify(meta, clientId), clientId);
    }

    /**
     * Xử lý khi client xóa file.
     */
    private void handleFileDeleted(Message msg) {
        String filename = msg.getPayloadString("filename");
        LOGGER.info("ClientHandler [" + clientId + "]: FILE_DELETED → " + filename);

        metadataManager.markDeleted(filename);

        // Broadcast cho các client khác
        Message deleteNotify = new Message(MessageType.SYNC_NOTIFY);
        deleteNotify.addPayload("filename", filename);
        deleteNotify.addPayload("action", "DELETE");
        deleteNotify.addPayload("sourceClient", clientId);
        server.broadcast(deleteNotify, clientId);
    }

    /**
     * Xử lý khi client đổi tên file.
     */
    private void handleFileRenamed(Message msg) {
        String oldName = msg.getPayloadString("oldName");
        String newName = msg.getPayloadString("newName");
        LOGGER.info("ClientHandler [" + clientId + "]: FILE_RENAMED → " + oldName + " → " + newName);

        metadataManager.renameFile(oldName, newName);

        // Broadcast cho các client khác
        Message renameNotify = Message.createFileRenamed(oldName, newName);
        renameNotify.setType(MessageType.SYNC_NOTIFY);
        renameNotify.addPayload("action", "RENAME");
        renameNotify.addPayload("sourceClient", clientId);
        server.broadcast(renameNotify, clientId);
    }

    /**
     * Xử lý yêu cầu tải file từ client.
     * Gửi: FILE_DATA_START → bytes file
     */
    private void handleRequestFile(Message msg) throws IOException {
        String filename = msg.getPayloadString("filename");
        int version = msg.getPayloadInt("version");

        FileMetadata meta;
        String storedPath;

        if (version <= 0) {
            // Lấy phiên bản mới nhất
            meta = metadataManager.getLatestVersion(filename);
        } else {
            meta = metadataManager.getVersion(filename, version);
        }

        if (meta == null) {
            sendMessage(Message.createError("File không tồn tại: " + filename));
            return;
        }

        storedPath = metadataManager.getStoredPath(meta.getFilename(), meta.getVersion());
        File file = (storedPath != null) ? new File(storedPath) : null;

        if (file == null || !file.exists()) {
            sendMessage(Message.createError("File không tồn tại trên server: " + filename));
            return;
        }

        LOGGER.info("ClientHandler [" + clientId + "]: Gửi file → " + filename + " (v" + meta.getVersion() + ")");

        // Gửi header trước
        sendMessage(Message.createFileDataStart(meta));

        // Gửi bytes file
        Utils.sendFile(rawOutputStream, file, (transferred, total) ->
            LOGGER.fine("  Gửi file: " + transferred + "/" + total)
        );
    }

    /**
     * Gửi danh sách metadata tất cả file hiện tại.
     */
    private void handleFileListRequest() {
        sendFileList();
    }

    /**
     * Đồng bộ bù khi client reconnect.
     */
    private void handleRequestSync(Message msg) throws IOException {
        long lastSyncTime = msg.getPayloadLong("lastSyncTime");
        LOGGER.info("ClientHandler [" + clientId + "]: REQUEST_SYNC từ " + lastSyncTime);

        List<FileMetadata> changes = metadataManager.getChangesSince(lastSyncTime);
        for (FileMetadata meta : changes) {
            // Gửi SYNC_NOTIFY cho từng thay đổi
            server.sendToClient(clientId, Message.createSyncNotify(meta, "server"));
        }
        LOGGER.info("ClientHandler [" + clientId + "]: Đã gửi " + changes.size() + " thay đổi bù.");
    }

    /**
     * Gửi lịch sử phiên bản của file.
     */
    private void handleVersionHistoryRequest(Message msg) {
        String filename = msg.getPayloadString("filename");
        List<FileMetadata> versions = metadataManager.getVersionHistory(filename);
        sendMessage(Message.createVersionHistoryResponse(
            filename,
            versions.toArray(new FileMetadata[0])
        ));
    }

    /**
     * Khôi phục phiên bản cũ của file.
     */
    private void handleRestoreVersion(Message msg) throws IOException {
        String filename = msg.getPayloadString("filename");
        int version = msg.getPayloadInt("version");

        FileMetadata oldMeta = metadataManager.getVersion(filename, version);
        if (oldMeta == null) {
            sendMessage(Message.createError("Phiên bản không tồn tại: " + filename + " v" + version));
            return;
        }

        // Tạo bản ghi mới (phiên bản tiếp theo) cho file đã khôi phục
        FileMetadata restoredMeta = new FileMetadata(
            filename, oldMeta.getSha256(), oldMeta.getFileSize(),
            System.currentTimeMillis(), clientId
        );
        metadataManager.upsertFile(restoredMeta);

        // Broadcast SYNC_NOTIFY
        server.broadcast(Message.createSyncNotify(restoredMeta, clientId), null);
        LOGGER.info("ClientHandler [" + clientId + "]: Đã khôi phục " + filename + " về v" + version);
    }

    // ===================================================
    //  TIỆN ÍCH
    // ===================================================

    private void sendFileList() {
        List<FileMetadata> files = metadataManager.getAllCurrentFiles();
        sendMessage(Message.createFileListResponse(files.toArray(new FileMetadata[0])));
    }

    /**
     * Gửi bản tin cho client này (thread-safe).
     */
    public synchronized void sendMessage(Message msg) {
        if (writer != null && !socket.isClosed()) {
            Utils.sendMessage(writer, msg);
        }
    }

    /**
     * Dọn dẹp khi client ngắt kết nối.
     */
    private void disconnect() {
        running = false;
        if (clientId != null) {
            server.unregisterClient(clientId);
            server.notifyClientEvent(clientId, false);
            LOGGER.info("ClientHandler: Ngắt kết nối → " + clientId + " (" + clientAddress + ")");
        }
        Utils.closeQuietly(socket);
    }

    // ===== GETTERS =====
    public String getClientId() { return clientId; }
    public String getClientAddress() { return clientAddress; }
    public boolean isAuthenticated() { return authenticated; }
    public void stop() { running = false; Utils.closeQuietly(socket); }
}

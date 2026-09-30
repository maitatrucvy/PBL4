package com.lansync.client;

import com.lansync.common.FileMetadata;
import com.lansync.common.Message;
import com.lansync.common.MessageType;
import com.lansync.common.Utils;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Quản lý kết nối TCP của Client tới Server.
 * Xử lý gửi/nhận bản tin và file.
 * Tự động reconnect khi mất kết nối.
 */
public class SyncClient {
    private static final Logger LOGGER = Logger.getLogger(SyncClient.class.getName());
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private final String serverHost;
    private final int serverPort;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private InputStream rawInputStream;
    private OutputStream rawOutputStream;

    private String clientId;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running   = new AtomicBoolean(false);
    private long lastSyncTime = 0;

    // ===== LISTENERS =====
    /** Được gọi khi nhận bản tin từ server */
    private Consumer<Message> messageListener;
    /** Được gọi khi kết nối / ngắt kết nối */
    private Consumer<Boolean> connectionListener;
    /** Được gọi khi cần nhận file (trả về InputStream + metadata) */
    private FileReceiveCallback fileReceiveCallback;

    @FunctionalInterface
    public interface FileReceiveCallback {
        void onFileReceive(FileMetadata meta, InputStream rawIn) throws IOException;
    }

    public SyncClient(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    // ===================================================
    //  KẾT NỐI
    // ===================================================

    /**
     * Kết nối tới server. Chạy trong thread riêng.
     *
     * @return true nếu kết nối thành công
     */
    public boolean connect() {
        try {
            socket = new Socket(serverHost, serverPort);
            socket.setKeepAlive(true);
            socket.setSoTimeout(0);

            reader          = Utils.createReader(socket);
            writer          = Utils.createWriter(socket);
            rawInputStream  = socket.getInputStream();
            rawOutputStream = socket.getOutputStream();

            connected.set(true);
            running.set(true);

            LOGGER.info("SyncClient: Kết nối thành công → " + serverHost + ":" + serverPort);
            if (connectionListener != null) connectionListener.accept(true);
            return true;
        } catch (IOException e) {
            LOGGER.warning("SyncClient: Không thể kết nối: " + e.getMessage());
            connected.set(false);
            return false;
        }
    }

    /**
     * Bắt đầu lắng nghe bản tin từ server (blocking — gọi trong thread riêng).
     */
    public void startListening() {
        while (running.get()) {
            try {
                Message msg = Utils.receiveMessage(reader);
                if (msg == null) {
                    LOGGER.warning("SyncClient: Kết nối bị đứt (server đóng).");
                    break;
                }
                handleIncoming(msg);
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warning("SyncClient: Lỗi nhận bản tin: " + e.getMessage());
                }
                break;
            }
        }
        connected.set(false);
        if (connectionListener != null) connectionListener.accept(false);
    }

    /**
     * Xử lý bản tin đến từ server.
     */
    private void handleIncoming(Message msg) throws IOException {
        LOGGER.fine("SyncClient: Nhận " + msg.getType());

        // Khi nhận FILE_DATA_START → đọc bytes file ngay lập tức (blocking)
        if (msg.getType() == MessageType.FILE_DATA_START) {
            FileMetadata meta = msg.getPayloadAsFileMetadata();
            if (fileReceiveCallback != null) {
                fileReceiveCallback.onFileReceive(meta, rawInputStream);
            }
            return;
        }

        // Cập nhật lastSyncTime
        lastSyncTime = Math.max(lastSyncTime, msg.getTimestamp());

        // Chuyển cho SyncEngine xử lý
        if (messageListener != null) {
            messageListener.accept(msg);
        }
    }

    // ===================================================
    //  GỬI BẢN TIN
    // ===================================================

    /**
     * Gửi bản tin điều khiển (JSON only).
     */
    public synchronized void send(Message msg) {
        if (!connected.get() || writer == null) {
            LOGGER.warning("SyncClient: Chưa kết nối — bỏ qua bản tin " + msg.getType());
            return;
        }
        Utils.sendMessage(writer, msg);
    }

    /**
     * Gửi thông báo thêm/cập nhật file + upload bytes.
     *
     * @param type     FILE_ADDED hoặc FILE_UPDATED
     * @param meta     Metadata của file
     * @param file     File cần upload
     * @param progress Callback tiến trình (nullable)
     */
    public synchronized void sendFile(MessageType type, FileMetadata meta,
                                       File file, Utils.ProgressListener progress) throws IOException {
        if (!connected.get()) throw new IOException("Chưa kết nối tới server.");

        // Gửi header JSON
        send(Message.createFileChange(type, meta));

        // Gửi bytes file
        Utils.sendFile(rawOutputStream, file, progress);
        LOGGER.info("SyncClient: Đã upload → " + meta.getFilename() + " (" + meta.getReadableSize() + ")");
    }

    /**
     * Gửi yêu cầu đăng nhập.
     */
    public void login(String username, String password) {
        send(Message.createLogin(username, password));
        this.clientId = username.trim().toLowerCase();
    }

    /**
     * Gửi yêu cầu đăng ký.
     */
    public void register(String username, String password, String displayName) {
        send(Message.createRegister(username, password, displayName));
    }

    /**
     * Gửi yêu cầu tải file từ server.
     */
    public void requestFile(String filename, int version) {
        send(Message.createRequestFile(filename, version));
    }

    /**
     * Gửi yêu cầu đồng bộ bù sau khi reconnect.
     */
    public void requestSync() {
        send(Message.createRequestSync(lastSyncTime));
    }

    /**
     * Gửi thông báo file đã bị xóa.
     */
    public void sendFileDeleted(String filename) {
        send(Message.createFileDeleted(filename));
    }

    /**
     * Gửi thông báo đổi tên file.
     */
    public void sendFileRenamed(String oldName, String newName) {
        send(Message.createFileRenamed(oldName, newName));
    }

    /**
     * Gửi yêu cầu xem lịch sử phiên bản.
     */
    public void requestVersionHistory(String filename) {
        send(Message.createVersionHistoryRequest(filename));
    }

    /**
     * Gửi yêu cầu khôi phục phiên bản cũ.
     */
    public void restoreVersion(String filename, int version) {
        send(Message.createRestoreVersion(filename, version));
    }

    /**
     * Gửi heartbeat để giữ kết nối.
     */
    public void sendHeartbeat() {
        send(Message.createHeartbeat());
    }

    // ===================================================
    //  NGẮT KẾT NỐI
    // ===================================================

    public void disconnect() {
        running.set(false);
        connected.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                send(new Message(MessageType.LOGOUT));
            }
        } catch (Exception ignored) {}
        Utils.closeQuietly(socket);
        LOGGER.info("SyncClient: Đã ngắt kết nối.");
    }

    // ===== GETTERS/SETTERS =====
    public boolean isConnected() { return connected.get(); }
    public String getClientId() { return clientId; }
    public long getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(long t) { this.lastSyncTime = t; }

    public void setMessageListener(Consumer<Message> listener) { this.messageListener = listener; }
    public void setConnectionListener(Consumer<Boolean> listener) { this.connectionListener = listener; }
    public void setFileReceiveCallback(FileReceiveCallback callback) { this.fileReceiveCallback = callback; }
}

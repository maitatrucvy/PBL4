package com.lansync.server;

import com.lansync.common.Message;
import com.lansync.common.Utils;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Server trung tâm — lắng nghe kết nối TCP từ các Client.
 * Quản lý danh sách Client đang kết nối.
 * Điều phối broadcast bản tin giữa các Client.
 */
public class SyncServer {
    private static final Logger LOGGER = Logger.getLogger(SyncServer.class.getName());

    private final int port;
    private ServerSocket serverSocket;
    private final AuthManager authManager;
    private final MetadataManager metadataManager;

    /** Map: clientId → ClientHandler (thread-safe) */
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    /** Thread pool để xử lý kết nối Client */
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private volatile boolean running = false;

    /** Callback để thông báo GUI khi có thay đổi (client connect/disconnect) */
    private BiConsumer<String, Boolean> clientEventListener;

    /** Callback để thông báo GUI log */
    private java.util.function.Consumer<String> logListener;

    public SyncServer(int port) {
        this.port = port;
        this.authManager = new AuthManager();
        this.metadataManager = new MetadataManager();
    }

    /**
     * Khởi động server, lắng nghe kết nối mới.
     * Chạy blocking — nên gọi trong thread riêng.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;

        LOGGER.info("SyncServer: Đang lắng nghe tại cổng " + port + "...");
        log("✅ Server khởi động — cổng " + port);

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setKeepAlive(true);
                clientSocket.setSoTimeout(0); // Không timeout

                ClientHandler handler = new ClientHandler(
                    clientSocket, this, authManager, metadataManager
                );
                threadPool.submit(handler);

                log("🔌 Kết nối mới: " + clientSocket.getInetAddress().getHostAddress()
                        + ":" + clientSocket.getPort());
            } catch (IOException e) {
                if (running) {
                    LOGGER.warning("SyncServer: Lỗi chấp nhận kết nối: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Dừng server và giải phóng tài nguyên.
     */
    public void stop() {
        running = false;
        log("🛑 Server đang dừng...");

        // Ngắt kết nối tất cả client
        for (ClientHandler handler : clients.values()) {
            handler.stop();
        }
        clients.clear();

        Utils.closeQuietly(serverSocket);
        threadPool.shutdown();
        authManager.close();
        metadataManager.close();
        log("✅ Server đã dừng.");
    }

    // ===================================================
    //  QUẢN LÝ CLIENT
    // ===================================================

    /**
     * Đăng ký client sau khi đăng nhập thành công.
     */
    public void registerClient(String clientId, ClientHandler handler) {
        clients.put(clientId, handler);
        LOGGER.info("SyncServer: Client đăng ký → " + clientId + " (Tổng: " + clients.size() + ")");
        log("👤 Client online: " + clientId + " | Tổng: " + clients.size() + " máy");
    }

    /**
     * Hủy đăng ký client khi ngắt kết nối.
     */
    public void unregisterClient(String clientId) {
        clients.remove(clientId);
        LOGGER.info("SyncServer: Client offline → " + clientId + " (Còn: " + clients.size() + ")");
        log("👤 Client offline: " + clientId + " | Còn: " + clients.size() + " máy");
    }

    /**
     * Gửi bản tin đến một client cụ thể.
     */
    public void sendToClient(String clientId, Message msg) {
        ClientHandler handler = clients.get(clientId);
        if (handler != null) {
            handler.sendMessage(msg);
        }
    }

    /**
     * Broadcast bản tin tới tất cả Client đang kết nối.
     *
     * @param msg          Bản tin cần gửi
     * @param excludeClient ClientId cần loại trừ (null = gửi tất cả)
     */
    public void broadcast(Message msg, String excludeClient) {
        int count = 0;
        for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
            if (!entry.getKey().equals(excludeClient)) {
                entry.getValue().sendMessage(msg);
                count++;
            }
        }
        if (count > 0) {
            LOGGER.fine("SyncServer: Broadcast " + msg.getType() + " → " + count + " client(s)");
        }
    }

    /**
     * Thông báo GUI về sự kiện client connect/disconnect.
     */
    public void notifyClientEvent(String clientId, boolean connected) {
        if (clientEventListener != null) {
            clientEventListener.accept(clientId, connected);
        }
    }

    // ===================================================
    //  LOGGING & LISTENERS
    // ===================================================

    private void log(String message) {
        LOGGER.info(message);
        if (logListener != null) {
            logListener.accept(message);
        }
    }

    // ===================================================
    //  GETTERS
    // ===================================================

    public int getPort() { return port; }
    public boolean isRunning() { return running; }
    public int getClientCount() { return clients.size(); }
    public Collection<ClientHandler> getClients() { return clients.values(); }
    public AuthManager getAuthManager() { return authManager; }
    public MetadataManager getMetadataManager() { return metadataManager; }

    public void setClientEventListener(BiConsumer<String, Boolean> listener) {
        this.clientEventListener = listener;
    }

    public void setLogListener(java.util.function.Consumer<String> listener) {
        this.logListener = listener;
    }
}

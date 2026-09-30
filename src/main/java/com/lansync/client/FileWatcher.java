package com.lansync.client;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Giám sát thư mục chia sẻ sử dụng Java NIO WatchService.
 * Phát hiện các sự kiện: tạo mới, sửa đổi, xóa file.
 * Áp dụng debounce để tránh kích hoạt nhiều lần cho cùng 1 sự kiện.
 */
public class FileWatcher {
    private static final Logger LOGGER = Logger.getLogger(FileWatcher.class.getName());

    /** Thời gian debounce (ms) — chờ trước khi xử lý sự kiện */
    private static final long DEBOUNCE_MS = 800;

    public enum FileEvent { CREATED, MODIFIED, DELETED, RENAMED }

    private final Path watchDir;
    private WatchService watchService;
    private ScheduledExecutorService debounceExecutor;

    /** Map lưu task debounce đang chờ cho mỗi file */
    private final Map<String, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();

    /** Callback khi phát hiện thay đổi: (filename, event) */
    private BiConsumer<String, FileEvent> eventCallback;

    /** Cờ để tạm dừng FileWatcher khi đang nhận file từ server */
    private volatile boolean paused = false;

    /** Set các file đang được tạm thời bỏ qua (đang sync từ server) */
    private final java.util.Set<String> ignoredFiles = ConcurrentHashMap.newKeySet();

    private volatile boolean running = false;

    public FileWatcher(Path watchDir) {
        this.watchDir = watchDir;
        if (!Files.exists(watchDir)) {
            try { Files.createDirectories(watchDir); } catch (IOException e) {
                LOGGER.severe("FileWatcher: Không tạo được thư mục: " + e.getMessage());
            }
        }
    }

    /**
     * Bắt đầu giám sát thư mục. Gọi trong thread riêng.
     */
    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FileWatcher-Debounce");
                t.setDaemon(true);
                return t;
            });

            // Đăng ký các sự kiện cần theo dõi
            watchDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            );

            running = true;
            LOGGER.info("FileWatcher: Đang giám sát → " + watchDir.toAbsolutePath());

            while (running) {
                WatchKey key;
                try {
                    // Chờ sự kiện (blocking)
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    processWatchEvent(event);
                }

                boolean valid = key.reset();
                if (!valid) {
                    LOGGER.warning("FileWatcher: WatchKey không còn hiệu lực.");
                    break;
                }
            }
        } catch (IOException e) {
            LOGGER.severe("FileWatcher: Lỗi khởi động: " + e.getMessage());
        } finally {
            stop();
        }
    }

    /**
     * Xử lý một WatchEvent từ WatchService.
     */
    @SuppressWarnings("unchecked")
    private void processWatchEvent(WatchEvent<?> event) {
        if (paused) return;

        WatchEvent.Kind<?> kind = event.kind();
        if (kind == StandardWatchEventKinds.OVERFLOW) return;

        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
        Path fileName = pathEvent.context();
        String name = fileName.getFileName().toString();

        // Bỏ qua file ẩn, file tạm của hệ thống
        if (shouldIgnore(name)) return;

        // Bỏ qua file đang được sync từ server
        if (ignoredFiles.contains(name)) {
            LOGGER.fine("FileWatcher: Bỏ qua file đang sync: " + name);
            return;
        }

        FileEvent fileEvent;
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            fileEvent = FileEvent.CREATED;
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            fileEvent = FileEvent.MODIFIED;
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            fileEvent = FileEvent.DELETED;
        } else {
            return;
        }

        scheduleDebounced(name, fileEvent);
    }

    /**
     * Lên lịch xử lý sự kiện với debounce.
     * Nếu đã có task cho file này → hủy task cũ, tạo task mới.
     */
    private void scheduleDebounced(String filename, FileEvent event) {
        // Hủy task cũ nếu có
        ScheduledFuture<?> existing = pendingTasks.get(filename);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        // Lên lịch task mới sau DEBOUNCE_MS ms
        ScheduledFuture<?> future = debounceExecutor.schedule(() -> {
            pendingTasks.remove(filename);
            if (eventCallback != null) {
                LOGGER.info("FileWatcher: Sự kiện → " + event + " | " + filename);
                eventCallback.accept(filename, event);
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);

        pendingTasks.put(filename, future);
    }

    /**
     * Dừng giám sát thư mục.
     */
    public void stop() {
        running = false;
        if (debounceExecutor != null) {
            debounceExecutor.shutdownNow();
        }
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        LOGGER.info("FileWatcher: Đã dừng.");
    }

    /**
     * Tạm dừng giám sát (dùng khi đang nhận file từ server).
     */
    public void pause() { paused = true; }

    /**
     * Tiếp tục giám sát.
     */
    public void resume() { paused = false; }

    /**
     * Thêm file vào danh sách bỏ qua tạm thời.
     * Được gọi trước khi lưu file nhận từ server.
     */
    public void ignoreFile(String filename) {
        ignoredFiles.add(filename);
    }

    /**
     * Bỏ file khỏi danh sách bỏ qua.
     * Được gọi sau khi lưu xong file nhận từ server.
     */
    public void unignoreFile(String filename) {
        // Delay một chút để đảm bảo WatchService đã xử lý sự kiện
        debounceExecutor.schedule(() -> ignoredFiles.remove(filename),
            DEBOUNCE_MS * 2, TimeUnit.MILLISECONDS);
    }

    /**
     * Kiểm tra xem có nên bỏ qua file không.
     */
    private boolean shouldIgnore(String filename) {
        if (filename == null || filename.isEmpty()) return true;
        // Bỏ qua file ẩn (bắt đầu bằng .)
        if (filename.startsWith(".")) return true;
        // Bỏ qua file tạm của Windows/các ứng dụng
        if (filename.endsWith(".tmp") || filename.endsWith(".crdownload")
                || filename.endsWith("~") || filename.startsWith("~$")) return true;
        // Bỏ qua thư mục
        File f = watchDir.resolve(filename).toFile();
        if (f.exists() && f.isDirectory()) return true;
        return false;
    }

    /**
     * Lấy đường dẫn tuyệt đối của file trong thư mục giám sát.
     */
    public File getFile(String filename) {
        return watchDir.resolve(filename).toFile();
    }

    /**
     * Lấy thư mục đang giám sát.
     */
    public Path getWatchDir() { return watchDir; }

    /**
     * Đặt callback xử lý sự kiện file.
     */
    public void setEventCallback(BiConsumer<String, FileEvent> callback) {
        this.eventCallback = callback;
    }

    public boolean isRunning() { return running; }
}

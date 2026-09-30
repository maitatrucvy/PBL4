package com.lansync.common;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

/**
 * Lớp tiện ích chung dùng cho cả Server và Client.
 * Cung cấp các phương thức:
 * - Tính toán SHA-256 của file/bytes
 * - Gửi/nhận bản tin JSON qua socket
 * - Gửi/nhận dữ liệu binary (file) qua socket
 */
public class Utils {
    private static final Logger LOGGER = Logger.getLogger(Utils.class.getName());

    // Kích thước buffer đọc/ghi file (64 KB)
    public static final int BUFFER_SIZE = 65536;

    // Cổng mặc định
    public static final int SERVER_PORT = 9000;

    // Ký tự kết thúc bản tin JSON (line delimiter)
    public static final String MSG_DELIMITER = "\n";

    private Utils() { /* Không khởi tạo */ }

    // ===================================================
    //  PHẦN 1: TÍNH SHA-256
    // ===================================================

    /**
     * Tính SHA-256 hash của một file.
     *
     * @param file File cần tính hash
     * @return Chuỗi hex SHA-256 (64 ký tự), hoặc null nếu có lỗi
     */
    public static String sha256OfFile(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            LOGGER.severe("Lỗi tính SHA-256: " + e.getMessage());
            return null;
        }
    }

    /**
     * Tính SHA-256 hash của một mảng bytes.
     */
    public static String sha256OfBytes(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Tính SHA-256 của một chuỗi (dùng cho mật khẩu).
     */
    public static String sha256OfString(String text) {
        return sha256OfBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Chuyển mảng bytes thành chuỗi hex.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ===================================================
    //  PHẦN 2: GỬI/NHẬN BẢN TIN JSON QUA SOCKET
    // ===================================================

    /**
     * Gửi một bản tin JSON qua socket.
     * Định dạng: [JSON string]\n
     *
     * @param out     PrintWriter của socket output stream
     * @param message Bản tin cần gửi
     */
    public static void sendMessage(PrintWriter out, Message message) {
        String json = message.toJson();
        out.println(json);
        out.flush();
    }

    /**
     * Nhận một bản tin JSON từ socket.
     * Đọc một dòng (kết thúc bằng \n).
     *
     * @param in BufferedReader của socket input stream
     * @return Bản tin đã phân tích, hoặc null nếu kết nối đã đóng
     */
    public static Message receiveMessage(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) return null;
        return Message.fromJson(line);
    }

    /**
     * Tạo PrintWriter từ socket output stream (tự động flush).
     */
    public static PrintWriter createWriter(Socket socket) throws IOException {
        return new PrintWriter(
            new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            ),
            true
        );
    }

    /**
     * Tạo BufferedReader từ socket input stream.
     */
    public static BufferedReader createReader(Socket socket) throws IOException {
        return new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        );
    }

    // ===================================================
    //  PHẦN 3: TRUYỀN FILE QUA SOCKET
    // ===================================================

    /**
     * Gửi file qua socket output stream.
     * Phải gửi bản tin FILE_DATA_START trước khi gọi hàm này.
     *
     * Protocol:
     *   [8 bytes: file size][file bytes...]
     *
     * @param out      OutputStream của socket
     * @param file     File cần gửi
     * @param listener Callback cập nhật tiến trình (nullable)
     */
    public static void sendFile(OutputStream out, File file, ProgressListener listener) throws IOException {
        long fileSize = file.length();

        // Gửi kích thước file (8 bytes, big-endian)
        byte[] sizeBytes = ByteBuffer.allocate(8).putLong(fileSize).array();
        out.write(sizeBytes);
        out.flush();

        // Gửi nội dung file
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalSent = 0;
            while ((bytesRead = bis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
                if (listener != null) {
                    listener.onProgress(totalSent, fileSize);
                }
            }
            out.flush();
        }
    }

    /**
     * Nhận file từ socket input stream.
     * Phải đọc bản tin FILE_DATA_START trước khi gọi hàm này.
     *
     * @param in          InputStream của socket
     * @param destination File đích để lưu
     * @param listener    Callback cập nhật tiến trình (nullable)
     */
    public static void receiveFile(InputStream in, File destination, ProgressListener listener) throws IOException {
        // Đọc kích thước file (8 bytes)
        byte[] sizeBytes = new byte[8];
        readFully(in, sizeBytes);
        long fileSize = ByteBuffer.wrap(sizeBytes).getLong();

        // Đảm bảo thư mục đích tồn tại
        if (destination.getParentFile() != null) {
            destination.getParentFile().mkdirs();
        }

        // Đọc nội dung file
        try (FileOutputStream fos = new FileOutputStream(destination);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = fileSize;
            long totalReceived = 0;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int bytesRead = in.read(buffer, 0, toRead);
                if (bytesRead == -1) {
                    throw new IOException("Kết nối bị đứt khi đang nhận file. Đã nhận: " + totalReceived + "/" + fileSize);
                }
                bos.write(buffer, 0, bytesRead);
                remaining -= bytesRead;
                totalReceived += bytesRead;
                if (listener != null) {
                    listener.onProgress(totalReceived, fileSize);
                }
            }
            bos.flush();
        }
    }

    /**
     * Đọc đúng {@code length} bytes từ InputStream (blocking).
     */
    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) throw new IOException("Stream đã đóng trước khi đọc đủ dữ liệu.");
            offset += read;
        }
    }

    // ===================================================
    //  PHẦN 4: TIỆN ÍCH KHÁC
    // ===================================================

    /**
     * Tạo tên file không xung đột trong thư mục đích.
     * VD: "document.txt" → "document_conflict_1718000000.txt"
     */
    public static String createConflictFilename(String originalFilename, String clientId) {
        int dotIndex = originalFilename.lastIndexOf('.');
        long ts = System.currentTimeMillis();
        if (dotIndex >= 0) {
            String name = originalFilename.substring(0, dotIndex);
            String ext  = originalFilename.substring(dotIndex);
            return name + "_conflict_" + clientId + "_" + ts + ext;
        }
        return originalFilename + "_conflict_" + clientId + "_" + ts;
    }

    /**
     * Đóng socket an toàn (không throw exception).
     */
    public static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Đóng Closeable an toàn.
     */
    public static void closeQuietly(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Kiểm tra chuỗi có rỗng không.
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Interface callback để cập nhật tiến trình truyền file.
     */
    @FunctionalInterface
    public interface ProgressListener {
        /**
         * @param transferred Số bytes đã truyền
         * @param total       Tổng số bytes cần truyền
         */
        void onProgress(long transferred, long total);
    }
}

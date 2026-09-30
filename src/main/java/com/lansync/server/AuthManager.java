package com.lansync.server;

import com.lansync.common.Utils;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Quản lý xác thực người dùng sử dụng SQLite.
 * Cung cấp chức năng: đăng ký, đăng nhập, kiểm tra tồn tại.
 */
public class AuthManager {
    private static final Logger LOGGER = Logger.getLogger(AuthManager.class.getName());
    private static final String DB_FILE = "server_data/auth.db";
    private Connection connection;

    public AuthManager() {
        initDatabase();
        createDefaultAdmin();
    }

    /**
     * Khởi tạo cơ sở dữ liệu và tạo bảng nếu chưa tồn tại.
     */
    private void initDatabase() {
        try {
            File dbDir = new File("server_data");
            if (!dbDir.exists()) dbDir.mkdirs();

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
            connection.setAutoCommit(true);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        username    TEXT    NOT NULL UNIQUE,
                        password_hash TEXT  NOT NULL,
                        display_name TEXT,
                        created_at  INTEGER NOT NULL,
                        last_login  INTEGER,
                        is_active   INTEGER NOT NULL DEFAULT 1
                    )
                """);
            }
            LOGGER.info("AuthManager: Kết nối CSDL thành công → " + DB_FILE);
        } catch (ClassNotFoundException | SQLException e) {
            LOGGER.severe("AuthManager: Lỗi khởi tạo CSDL: " + e.getMessage());
            throw new RuntimeException("Không thể khởi tạo AuthManager", e);
        }
    }

    /**
     * Tạo tài khoản admin mặc định nếu chưa có tài khoản nào.
     */
    private void createDefaultAdmin() {
        try {
            if (getUserCount() == 0) {
                register("admin", "admin123", "Administrator");
                LOGGER.info("AuthManager: Đã tạo tài khoản mặc định admin/admin123");
            }
        } catch (Exception e) {
            LOGGER.warning("AuthManager: Không tạo được tài khoản mặc định: " + e.getMessage());
        }
    }

    /**
     * Đăng ký tài khoản mới.
     *
     * @return true nếu đăng ký thành công, false nếu username đã tồn tại
     */
    public boolean register(String username, String password, String displayName) {
        if (Utils.isBlank(username) || Utils.isBlank(password)) return false;

        String passwordHash = Utils.sha256OfString(password);
        String sql = "INSERT INTO users (username, password_hash, display_name, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.trim().toLowerCase());
            pstmt.setString(2, passwordHash);
            pstmt.setString(3, displayName != null ? displayName : username);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
            LOGGER.info("AuthManager: Đăng ký thành công → " + username);
            return true;
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE constraint failed")) {
                LOGGER.warning("AuthManager: Username đã tồn tại → " + username);
            } else {
                LOGGER.severe("AuthManager: Lỗi đăng ký: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Xác thực đăng nhập.
     *
     * @return true nếu đăng nhập thành công
     */
    public boolean authenticate(String username, String password) {
        if (Utils.isBlank(username) || Utils.isBlank(password)) return false;

        String passwordHash = Utils.sha256OfString(password);
        String sql = "SELECT id FROM users WHERE username = ? AND password_hash = ? AND is_active = 1";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.trim().toLowerCase());
            pstmt.setString(2, passwordHash);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                updateLastLogin(username);
                LOGGER.info("AuthManager: Đăng nhập thành công → " + username);
                return true;
            }
            LOGGER.warning("AuthManager: Sai tài khoản/mật khẩu → " + username);
            return false;
        } catch (SQLException e) {
            LOGGER.severe("AuthManager: Lỗi xác thực: " + e.getMessage());
            return false;
        }
    }

    /**
     * Kiểm tra username đã tồn tại chưa.
     */
    public boolean usernameExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.trim().toLowerCase());
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Lấy tên hiển thị của người dùng.
     */
    public String getDisplayName(String username) {
        String sql = "SELECT display_name FROM users WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.trim().toLowerCase());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("display_name");
        } catch (SQLException e) {
            LOGGER.warning("AuthManager: Không lấy được tên hiển thị: " + e.getMessage());
        }
        return username;
    }

    /**
     * Lấy danh sách tất cả người dùng.
     */
    public List<String[]> getAllUsers() {
        List<String[]> users = new ArrayList<>();
        String sql = "SELECT username, display_name, created_at, last_login FROM users ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                users.add(new String[]{
                    rs.getString("username"),
                    rs.getString("display_name"),
                    String.valueOf(rs.getLong("created_at")),
                    String.valueOf(rs.getLong("last_login"))
                });
            }
        } catch (SQLException e) {
            LOGGER.warning("AuthManager: Lỗi lấy danh sách user: " + e.getMessage());
        }
        return users;
    }

    /**
     * Cập nhật thời gian đăng nhập cuối.
     */
    private void updateLastLogin(String username) {
        String sql = "UPDATE users SET last_login = ? WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, System.currentTimeMillis());
            pstmt.setString(2, username.trim().toLowerCase());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warning("AuthManager: Không cập nhật last_login: " + e.getMessage());
        }
    }

    /**
     * Đếm tổng số người dùng trong hệ thống.
     */
    private int getUserCount() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Đóng kết nối database.
     */
    public void close() {
        Utils.closeQuietly(connection);
    }
}

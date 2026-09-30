package com.lansync.server;

import com.lansync.common.FileMetadata;
import com.lansync.common.Utils;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Quản lý metadata của tất cả file trong hệ thống đồng bộ.
 * Lưu trữ thông tin file vào SQLite: tên, hash, kích thước, phiên bản...
 * File thực tế được lưu tại thư mục server_store/
 */
public class MetadataManager {
    private static final Logger LOGGER = Logger.getLogger(MetadataManager.class.getName());
    private static final String DB_FILE = "server_data/metadata.db";

    /** Thư mục lưu trữ file tập trung tại Server */
    public static final String SERVER_STORE = "server_store";

    private Connection connection;

    public MetadataManager() {
        initDatabase();
        ensureStoreDirectory();
    }

    private void initDatabase() {
        try {
            File dbDir = new File("server_data");
            if (!dbDir.exists()) dbDir.mkdirs();

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
            connection.setAutoCommit(true);

            try (Statement stmt = connection.createStatement()) {
                // Bảng lưu metadata file (mỗi phiên bản là một dòng)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS file_metadata (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        filename      TEXT    NOT NULL,
                        sha256        TEXT    NOT NULL,
                        file_size     INTEGER NOT NULL DEFAULT 0,
                        last_modified INTEGER NOT NULL,
                        owner_client  TEXT,
                        version       INTEGER NOT NULL DEFAULT 1,
                        is_deleted    INTEGER NOT NULL DEFAULT 0,
                        synced_at     INTEGER NOT NULL,
                        stored_path   TEXT
                    )
                """);

                // Index tăng tốc tra cứu theo tên file
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_filename ON file_metadata(filename)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_synced_at ON file_metadata(synced_at)");
            }
            LOGGER.info("MetadataManager: Kết nối CSDL thành công → " + DB_FILE);
        } catch (ClassNotFoundException | SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi khởi tạo CSDL: " + e.getMessage());
            throw new RuntimeException("Không thể khởi tạo MetadataManager", e);
        }
    }

    private void ensureStoreDirectory() {
        File storeDir = new File(SERVER_STORE);
        if (!storeDir.exists()) storeDir.mkdirs();
    }

    // ===================================================
    //  THÊM / CẬP NHẬT METADATA
    // ===================================================

    /**
     * Thêm bản ghi metadata mới hoặc cập nhật phiên bản nếu file đã tồn tại.
     *
     * @return true nếu đây là file mới, false nếu là cập nhật file cũ
     */
    public boolean upsertFile(FileMetadata meta) {
        FileMetadata existing = getLatestVersion(meta.getFilename());
        boolean isNew = (existing == null);
        int nextVersion = isNew ? 1 : existing.getVersion() + 1;

        String sql = """
            INSERT INTO file_metadata
                (filename, sha256, file_size, last_modified, owner_client, version, is_deleted, synced_at, stored_path)
            VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
        """;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            String storedPath = SERVER_STORE + File.separator + meta.getFilename() + ".v" + nextVersion;
            pstmt.setString(1, meta.getFilename());
            pstmt.setString(2, meta.getSha256());
            pstmt.setLong(3, meta.getFileSize());
            pstmt.setLong(4, meta.getLastModified());
            pstmt.setString(5, meta.getOwnerClient());
            pstmt.setInt(6, nextVersion);
            pstmt.setLong(7, System.currentTimeMillis());
            pstmt.setString(8, storedPath);
            pstmt.executeUpdate();
            meta.setVersion(nextVersion);
            LOGGER.info("MetadataManager: Upsert → " + meta.getFilename() + " v" + nextVersion);
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi upsert: " + e.getMessage());
        }
        return isNew;
    }

    /**
     * Đánh dấu file đã bị xóa (soft delete).
     */
    public void markDeleted(String filename) {
        String sql = "UPDATE file_metadata SET is_deleted = 1 WHERE filename = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filename);
            pstmt.executeUpdate();
            LOGGER.info("MetadataManager: Đánh dấu xóa → " + filename);
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi đánh dấu xóa: " + e.getMessage());
        }
    }

    /**
     * Cập nhật tên file trong metadata (đổi tên).
     */
    public void renameFile(String oldName, String newName) {
        String sql = "UPDATE file_metadata SET filename = ? WHERE filename = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, newName);
            pstmt.setString(2, oldName);
            pstmt.executeUpdate();
            LOGGER.info("MetadataManager: Đổi tên → " + oldName + " → " + newName);
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi đổi tên: " + e.getMessage());
        }
    }

    // ===================================================
    //  TRUY VẤN METADATA
    // ===================================================

    /**
     * Lấy phiên bản mới nhất (không bị xóa) của một file.
     */
    public FileMetadata getLatestVersion(String filename) {
        String sql = """
            SELECT * FROM file_metadata
            WHERE filename = ? AND is_deleted = 0
            ORDER BY version DESC LIMIT 1
        """;
        return queryOne(sql, filename);
    }

    /**
     * Lấy một phiên bản cụ thể của file.
     */
    public FileMetadata getVersion(String filename, int version) {
        String sql = "SELECT * FROM file_metadata WHERE filename = ? AND version = ? LIMIT 1";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filename);
            pstmt.setInt(2, version);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return mapRow(rs);
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi lấy phiên bản: " + e.getMessage());
        }
        return null;
    }

    /**
     * Lấy danh sách tất cả phiên bản của một file (lịch sử).
     */
    public List<FileMetadata> getVersionHistory(String filename) {
        List<FileMetadata> versions = new ArrayList<>();
        String sql = "SELECT * FROM file_metadata WHERE filename = ? ORDER BY version DESC";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filename);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) versions.add(mapRow(rs));
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi lấy lịch sử: " + e.getMessage());
        }
        return versions;
    }

    /**
     * Lấy danh sách metadata của tất cả file hiện tại (chưa bị xóa).
     */
    public List<FileMetadata> getAllCurrentFiles() {
        List<FileMetadata> files = new ArrayList<>();
        String sql = """
            SELECT fm.* FROM file_metadata fm
            INNER JOIN (
                SELECT filename, MAX(version) AS max_ver
                FROM file_metadata
                WHERE is_deleted = 0
                GROUP BY filename
            ) latest ON fm.filename = latest.filename AND fm.version = latest.max_ver
            ORDER BY fm.synced_at DESC
        """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) files.add(mapRow(rs));
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi lấy danh sách file: " + e.getMessage());
        }
        return files;
    }

    /**
     * Lấy tất cả thay đổi (thêm/sửa) sau một thời điểm cụ thể.
     * Dùng để đồng bộ bù khi Client reconnect.
     */
    public List<FileMetadata> getChangesSince(long timestamp) {
        List<FileMetadata> changes = new ArrayList<>();
        String sql = """
            SELECT fm.* FROM file_metadata fm
            INNER JOIN (
                SELECT filename, MAX(version) AS max_ver
                FROM file_metadata
                WHERE synced_at > ?
                GROUP BY filename
            ) recent ON fm.filename = recent.filename AND fm.version = recent.max_ver
            ORDER BY fm.synced_at ASC
        """;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, timestamp);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) changes.add(mapRow(rs));
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi lấy thay đổi: " + e.getMessage());
        }
        return changes;
    }

    /**
     * Kiểm tra xung đột: file từ client có bị lỗi thời không?
     * Trả về true nếu server đã có phiên bản mới hơn (có thể là conflict).
     */
    public boolean isConflict(FileMetadata clientMeta) {
        FileMetadata serverMeta = getLatestVersion(clientMeta.getFilename());
        if (serverMeta == null) return false;
        // Conflict nếu: server có phiên bản mới hơn VÀ hash khác nhau
        return serverMeta.getLastModified() > clientMeta.getLastModified()
                && !serverMeta.hasSameContent(clientMeta);
    }

    /**
     * Lấy đường dẫn file vật lý trên server.
     */
    public String getStoredPath(String filename, int version) {
        String sql = "SELECT stored_path FROM file_metadata WHERE filename = ? AND version = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filename);
            pstmt.setInt(2, version);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("stored_path");
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi lấy stored_path: " + e.getMessage());
        }
        return null;
    }

    // ===================================================
    //  HELPER METHODS
    // ===================================================

    private FileMetadata queryOne(String sql, String filename) {
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filename);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return mapRow(rs);
        } catch (SQLException e) {
            LOGGER.severe("MetadataManager: Lỗi truy vấn: " + e.getMessage());
        }
        return null;
    }

    private FileMetadata mapRow(ResultSet rs) throws SQLException {
        FileMetadata meta = new FileMetadata();
        meta.setFilename(rs.getString("filename"));
        meta.setSha256(rs.getString("sha256"));
        meta.setFileSize(rs.getLong("file_size"));
        meta.setLastModified(rs.getLong("last_modified"));
        meta.setOwnerClient(rs.getString("owner_client"));
        meta.setVersion(rs.getInt("version"));
        meta.setDeleted(rs.getInt("is_deleted") == 1);
        meta.setSyncedAt(rs.getLong("synced_at"));
        return meta;
    }

    public void close() {
        Utils.closeQuietly(connection);
    }
}

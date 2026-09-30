package com.lansync.common;

/**
 * Lớp chứa metadata của một tập tin trong hệ thống đồng bộ.
 * Được dùng để truyền thông tin file giữa Server và Client mà không cần truyền toàn bộ nội dung.
 */
public class FileMetadata {
    /** Tên file (chỉ tên file, không bao gồm đường dẫn) */
    private String filename;

    /** Hàm băm SHA-256 của nội dung file */
    private String sha256;

    /** Kích thước file tính bằng bytes */
    private long fileSize;

    /** Thời điểm sửa đổi cuối (milliseconds Unix timestamp) */
    private long lastModified;

    /** Client ID sở hữu/tạo ra file này */
    private String ownerClient;

    /** Số phiên bản (tăng dần mỗi khi file được cập nhật) */
    private int version;

    /** Đánh dấu file đã bị xóa */
    private boolean deleted;

    /** Tên file hiển thị (có thể khác filename nếu là file conflict) */
    private String displayName;

    /** Thời điểm đồng bộ lần cuối lên server */
    private long syncedAt;

    public FileMetadata() {}

    public FileMetadata(String filename, String sha256, long fileSize, long lastModified, String ownerClient) {
        this.filename = filename;
        this.sha256 = sha256;
        this.fileSize = fileSize;
        this.lastModified = lastModified;
        this.ownerClient = ownerClient;
        this.version = 1;
        this.deleted = false;
        this.displayName = filename;
        this.syncedAt = System.currentTimeMillis();
    }

    /**
     * Kiểm tra xem hai file có cùng nội dung không (so sánh SHA-256).
     */
    public boolean hasSameContent(FileMetadata other) {
        if (this.sha256 == null || other.sha256 == null) return false;
        return this.sha256.equals(other.sha256);
    }

    /**
     * Trả về chuỗi kích thước file ở dạng dễ đọc.
     */
    public String getReadableSize() {
        if (fileSize < 1024) return fileSize + " B";
        else if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        else if (fileSize < 1024 * 1024 * 1024) return String.format("%.1f MB", fileSize / (1024.0 * 1024));
        else return String.format("%.1f GB", fileSize / (1024.0 * 1024 * 1024));
    }

    // ===== GETTERS/SETTERS =====

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }

    public String getOwnerClient() { return ownerClient; }
    public void setOwnerClient(String ownerClient) { this.ownerClient = ownerClient; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public String getDisplayName() { return displayName != null ? displayName : filename; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public long getSyncedAt() { return syncedAt; }
    public void setSyncedAt(long syncedAt) { this.syncedAt = syncedAt; }

    @Override
    public String toString() {
        return "FileMetadata{filename='" + filename + "', sha256='" +
               (sha256 != null ? sha256.substring(0, 8) + "..." : "null") +
               "', size=" + getReadableSize() +
               ", version=" + version +
               ", owner='" + ownerClient + "'}";
    }
}

package com.lansync.common;

/**
 * Enum định nghĩa tất cả các loại lệnh trong giao thức truyền thông.
 * Mỗi bản tin JSON sẽ có trường "type" tương ứng với một giá trị enum này.
 */
public enum MessageType {
    // ===== XÁC THỰC =====
    /** Client gửi thông tin đăng nhập lên Server */
    LOGIN,
    /** Server phản hồi đăng nhập thành công */
    LOGIN_OK,
    /** Server phản hồi đăng nhập thất bại */
    LOGIN_FAIL,
    /** Client gửi thông tin đăng ký tài khoản mới */
    REGISTER,
    /** Server phản hồi đăng ký thành công */
    REGISTER_OK,
    /** Server phản hồi đăng ký thất bại (username đã tồn tại) */
    REGISTER_FAIL,
    /** Client ngắt kết nối */
    LOGOUT,

    // ===== ĐỒNG BỘ FILE =====
    /** Client thông báo đã thêm file mới */
    FILE_ADDED,
    /** Client thông báo file đã được sửa đổi */
    FILE_UPDATED,
    /** Client thông báo file đã bị xóa */
    FILE_DELETED,
    /** Client thông báo file đã được đổi tên */
    FILE_RENAMED,
    /** Client yêu cầu danh sách metadata của tất cả file */
    FILE_LIST_REQUEST,
    /** Server gửi danh sách metadata của tất cả file */
    FILE_LIST_RESPONSE,

    // ===== TRUYỀN FILE =====
    /** Client yêu cầu tải xuống một file cụ thể từ Server */
    REQUEST_FILE,
    /** Server chuẩn bị gửi dữ liệu file (header trước khi gửi bytes) */
    FILE_DATA_START,
    /** Server/Client báo hiệu đã gửi xong dữ liệu file */
    FILE_DATA_END,

    // ===== THÔNG BÁO TỪ SERVER =====
    /** Server thông báo cho các Client khác cần đồng bộ một file */
    SYNC_NOTIFY,
    /** Server thông báo xung đột dữ liệu */
    CONFLICT_NOTIFY,
    /** Server thông báo một Client khác đã offline */
    CLIENT_DISCONNECTED,
    /** Server thông báo một Client khác đã online */
    CLIENT_CONNECTED,

    // ===== LỊCH SỬ PHIÊN BẢN =====
    /** Client yêu cầu xem lịch sử phiên bản của một file */
    VERSION_HISTORY_REQUEST,
    /** Server gửi danh sách lịch sử phiên bản */
    VERSION_HISTORY_RESPONSE,
    /** Client yêu cầu khôi phục về phiên bản cũ */
    RESTORE_VERSION,

    // ===== KẾT NỐI =====
    /** Kiểm tra kết nối còn sống (ping/pong) */
    HEARTBEAT,
    /** Phản hồi heartbeat */
    HEARTBEAT_ACK,
    /** Yêu cầu đồng bộ toàn bộ sau khi reconnect */
    REQUEST_SYNC,
    /** Phản hồi lỗi chung */
    ERROR
}

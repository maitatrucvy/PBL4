package com.lansync.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Lớp đại diện cho một bản tin trong giao thức truyền thông.
 * Mỗi bản tin gồm: type, timestamp, và payload (đối tượng JSON tùy chỉnh).
 *
 * <p>Định dạng JSON:
 * <pre>
 * {
 *   "type": "LOGIN",
 *   "timestamp": 1718000000000,
 *   "payload": { ... }
 * }
 * </pre>
 */
public class Message {
    private static final Gson GSON = new GsonBuilder().create();

    private MessageType type;
    private long timestamp;
    private JsonObject payload;

    public Message() {
        this.timestamp = System.currentTimeMillis();
        this.payload = new JsonObject();
    }

    public Message(MessageType type) {
        this();
        this.type = type;
    }

    // ===== FACTORY METHODS =====

    /** Tạo bản tin LOGIN */
    public static Message createLogin(String username, String password) {
        Message msg = new Message(MessageType.LOGIN);
        msg.payload.addProperty("username", username);
        msg.payload.addProperty("password", password);
        return msg;
    }

    /** Tạo bản tin REGISTER */
    public static Message createRegister(String username, String password, String displayName) {
        Message msg = new Message(MessageType.REGISTER);
        msg.payload.addProperty("username", username);
        msg.payload.addProperty("password", password);
        msg.payload.addProperty("displayName", displayName);
        return msg;
    }

    /** Tạo bản tin LOGIN_OK với danh sách file */
    public static Message createLoginOk(String clientId, String welcomeMessage) {
        Message msg = new Message(MessageType.LOGIN_OK);
        msg.payload.addProperty("clientId", clientId);
        msg.payload.addProperty("message", welcomeMessage);
        return msg;
    }

    /** Tạo bản tin LOGIN_FAIL */
    public static Message createLoginFail(String reason) {
        Message msg = new Message(MessageType.LOGIN_FAIL);
        msg.payload.addProperty("reason", reason);
        return msg;
    }

    /** Tạo bản tin thông báo thay đổi file */
    public static Message createFileChange(MessageType type, FileMetadata metadata) {
        Message msg = new Message(type);
        msg.payload = GSON.toJsonTree(metadata).getAsJsonObject();
        return msg;
    }

    /** Tạo bản tin SYNC_NOTIFY — thông báo client khác cần tải file */
    public static Message createSyncNotify(FileMetadata metadata, String sourceClient) {
        Message msg = new Message(MessageType.SYNC_NOTIFY);
        msg.payload = GSON.toJsonTree(metadata).getAsJsonObject();
        msg.payload.addProperty("sourceClient", sourceClient);
        return msg;
    }

    /** Tạo bản tin REQUEST_FILE */
    public static Message createRequestFile(String filename, int version) {
        Message msg = new Message(MessageType.REQUEST_FILE);
        msg.payload.addProperty("filename", filename);
        msg.payload.addProperty("version", version);
        return msg;
    }

    /** Tạo bản tin FILE_DATA_START — bắt đầu truyền file */
    public static Message createFileDataStart(FileMetadata metadata) {
        Message msg = new Message(MessageType.FILE_DATA_START);
        msg.payload = GSON.toJsonTree(metadata).getAsJsonObject();
        return msg;
    }

    /** Tạo bản tin CONFLICT_NOTIFY */
    public static Message createConflictNotify(String originalFile, String conflictFile, String reason) {
        Message msg = new Message(MessageType.CONFLICT_NOTIFY);
        msg.payload.addProperty("originalFile", originalFile);
        msg.payload.addProperty("conflictFile", conflictFile);
        msg.payload.addProperty("reason", reason);
        return msg;
    }

    /** Tạo bản tin FILE_LIST_RESPONSE */
    public static Message createFileListResponse(FileMetadata[] files) {
        Message msg = new Message(MessageType.FILE_LIST_RESPONSE);
        msg.payload.add("files", GSON.toJsonTree(files));
        return msg;
    }

    /** Tạo bản tin HEARTBEAT */
    public static Message createHeartbeat() {
        return new Message(MessageType.HEARTBEAT);
    }

    /** Tạo bản tin HEARTBEAT_ACK */
    public static Message createHeartbeatAck() {
        return new Message(MessageType.HEARTBEAT_ACK);
    }

    /** Tạo bản tin ERROR */
    public static Message createError(String errorMessage) {
        Message msg = new Message(MessageType.ERROR);
        msg.payload.addProperty("error", errorMessage);
        return msg;
    }

    /** Tạo bản tin REQUEST_SYNC (client yêu cầu đồng bộ bù sau reconnect) */
    public static Message createRequestSync(long lastSyncTime) {
        Message msg = new Message(MessageType.REQUEST_SYNC);
        msg.payload.addProperty("lastSyncTime", lastSyncTime);
        return msg;
    }

    /** Tạo bản tin FILE_DELETED */
    public static Message createFileDeleted(String filename) {
        Message msg = new Message(MessageType.FILE_DELETED);
        msg.payload.addProperty("filename", filename);
        return msg;
    }

    /** Tạo bản tin FILE_RENAMED */
    public static Message createFileRenamed(String oldName, String newName) {
        Message msg = new Message(MessageType.FILE_RENAMED);
        msg.payload.addProperty("oldName", oldName);
        msg.payload.addProperty("newName", newName);
        return msg;
    }

    /** Tạo bản tin VERSION_HISTORY_REQUEST */
    public static Message createVersionHistoryRequest(String filename) {
        Message msg = new Message(MessageType.VERSION_HISTORY_REQUEST);
        msg.payload.addProperty("filename", filename);
        return msg;
    }

    /** Tạo bản tin VERSION_HISTORY_RESPONSE */
    public static Message createVersionHistoryResponse(String filename, FileMetadata[] versions) {
        Message msg = new Message(MessageType.VERSION_HISTORY_RESPONSE);
        msg.payload.addProperty("filename", filename);
        msg.payload.add("versions", GSON.toJsonTree(versions));
        return msg;
    }

    /** Tạo bản tin RESTORE_VERSION */
    public static Message createRestoreVersion(String filename, int version) {
        Message msg = new Message(MessageType.RESTORE_VERSION);
        msg.payload.addProperty("filename", filename);
        msg.payload.addProperty("version", version);
        return msg;
    }

    // ===== SERIALIZATION =====

    /** Chuyển bản tin thành chuỗi JSON */
    public String toJson() {
        return GSON.toJson(this);
    }

    /** Phân tích chuỗi JSON thành bản tin */
    public static Message fromJson(String json) {
        return GSON.fromJson(json, Message.class);
    }

    // ===== PAYLOAD HELPERS =====

    /** Lấy giá trị chuỗi từ payload */
    public String getPayloadString(String key) {
        JsonElement el = payload.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }

    /** Lấy giá trị số nguyên từ payload */
    public int getPayloadInt(String key) {
        JsonElement el = payload.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsInt() : 0;
    }

    /** Lấy giá trị long từ payload */
    public long getPayloadLong(String key) {
        JsonElement el = payload.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsLong() : 0L;
    }

    /** Lấy FileMetadata từ payload */
    public FileMetadata getPayloadAsFileMetadata() {
        return GSON.fromJson(payload, FileMetadata.class);
    }

    /** Lấy mảng FileMetadata từ payload */
    public FileMetadata[] getPayloadFilesArray() {
        JsonElement el = payload.get("files");
        return (el != null) ? GSON.fromJson(el, FileMetadata[].class) : new FileMetadata[0];
    }

    /** Lấy mảng phiên bản từ payload */
    public FileMetadata[] getPayloadVersionsArray() {
        JsonElement el = payload.get("versions");
        return (el != null) ? GSON.fromJson(el, FileMetadata[].class) : new FileMetadata[0];
    }

    /** Thêm thuộc tính tùy chỉnh vào payload */
    public void addPayload(String key, String value) {
        payload.addProperty(key, value);
    }

    // ===== GETTERS/SETTERS =====

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public JsonObject getPayload() { return payload; }
    public void setPayload(JsonObject payload) { this.payload = payload; }

    @Override
    public String toString() {
        return "Message{type=" + type + ", timestamp=" + timestamp + ", payload=" + payload + "}";
    }
}

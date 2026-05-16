package com.localdrop.protocol;

public final class ProtocolConstants {
    public static final String CONTRACT_REVISION = "2026-05-16-r2";

    public static final int PROTOCOL_VERSION = 2;
    public static final int DISCOVERY_PORT = 45454;
    public static final int DEFAULT_TRANSFER_PORT = 45455;
    public static final int WINDOWS_TRANSFER_PORT_MAX = 45474;
    public static final int DISCOVERY_PACKET_MAX_BYTES = 16_384;
    public static final int MAX_HEADER_BYTES = 65_536;
    public static final int MAX_DEVICE_ID_LENGTH = 128;
    public static final int MAX_DEVICE_NAME_LENGTH = 64;
    public static final int MAX_DEVICE_TYPE_LENGTH = 32;
    public static final int MAX_STATUS_LENGTH = 64;
    public static final int MAX_FILE_NAME_LENGTH = 180;
    public static final int MAX_RELATIVE_PATH_LENGTH = 1024;
    public static final int MAX_RELATIVE_PATH_DEPTH = 12;
    public static final int MAX_FILES_PER_SESSION = 10_000;
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L * 1024L;
    public static final long MAX_TOTAL_SESSION_BYTES = 100L * 1024L * 1024L * 1024L;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int HEADER_READ_TIMEOUT_MS = 10_000;
    public static final int ACK_READ_TIMEOUT_MS = 10_000;
    public static final int FILE_TRANSFER_IDLE_TIMEOUT_MS = 30_000;
    public static final int CONTROL_READ_TIMEOUT_MS = HEADER_READ_TIMEOUT_MS;
    public static final int PAYLOAD_READ_TIMEOUT_MS = FILE_TRANSFER_IDLE_TIMEOUT_MS;
    public static final long PART_FILE_CLEANUP_TTL_MS = 86_400_000L;
    public static final long DISCOVERY_BROADCAST_INTERVAL_MILLIS = 4_000L;
    public static final long DISCOVERY_DEVICE_TIMEOUT_MILLIS = 30_000L;
    public static final long DIAGNOSTIC_RECENT_DEVICE_TTL_MS = 180_000L;
    public static final int MANUAL_REFRESH_BURST_COUNT = 4;
    public static final long MANUAL_REFRESH_BURST_DELAY_MS = 250L;
    public static final long MAIN_LIST_NOT_READY_GRACE_MS = 3_000L;
    public static final long MAIN_LIST_EXPIRE_GRACE_MS = 1_000L;
    public static final int DIAGNOSTIC_EVENT_BUFFER_SIZE = 200;
    public static final long DISCOVERY_RESPONSE_THROTTLE_MILLIS = 1500;

    public static final String TYPE_DISCOVERY = "DISCOVERY";
    public static final String TYPE_SESSION_START = "SESSION_START";
    public static final String TYPE_SESSION_ACCEPTED = "SESSION_ACCEPTED";
    public static final String TYPE_SESSION_REJECTED = "SESSION_REJECTED";
    public static final String TYPE_FILE_META = "FILE_META";
    public static final String TYPE_FILE_ACK = "FILE_ACK";
    public static final String TYPE_SESSION_FINISH = "SESSION_FINISH";
    public static final String TYPE_SESSION_FINISH_ACK = "SESSION_FINISH_ACK";

    public static final String STATUS_READY = "READY";
    public static final String STATUS_READY_COMPAT = "READY_COMPAT";
    public static final String STATUS_NOT_READY = "NOT_READY";
    public static final String STATUS_BUSY = "BUSY";
    public static final String STATUS_RECEIVE_FOLDER_NOT_SELECTED = "RECEIVE_FOLDER_NOT_SELECTED";
    public static final String STATUS_RECEIVE_FOLDER_NOT_WRITABLE = "RECEIVE_FOLDER_NOT_WRITABLE";
    public static final String STATUS_TRANSFER_PORT_UNAVAILABLE = "TRANSFER_PORT_UNAVAILABLE";
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String DEVICE_TYPE_WINDOWS = "WINDOWS";
    public static final String DEVICE_TYPE_ANDROID = "ANDROID";

    public static final String CAPABILITY_BATCH_TRANSFER_V1 = "BATCH_TRANSFER_V1";
    public static final String CAPABILITY_RELATIVE_PATHS = "RELATIVE_PATHS";
    public static final String CAPABILITY_OPEN_TRANSFER_V1 = "OPEN_TRANSFER_V1";

    public static final java.util.List<String> CAPABILITIES = java.util.List.of(
        CAPABILITY_BATCH_TRANSFER_V1,
        CAPABILITY_RELATIVE_PATHS,
        CAPABILITY_OPEN_TRANSFER_V1
    );

    public static final String ERROR_NONE = "NONE";
    public static final String ERROR_UNKNOWN = "UNKNOWN_ERROR";
    public static final String ERROR_PROTOCOL_VERSION_MISMATCH = "PROTOCOL_VERSION_MISMATCH";
    public static final String ERROR_MALFORMED_MESSAGE = "MALFORMED_MESSAGE";
    public static final String ERROR_INVALID_SESSION = "INVALID_SESSION";
    public static final String ERROR_INVALID_FILE_ID = "INVALID_FILE_ID";
    public static final String ERROR_ACK_MISMATCH = "ACK_MISMATCH";
    public static final String ERROR_RECEIVE_FOLDER_NOT_SELECTED = "RECEIVE_FOLDER_NOT_SELECTED";
    public static final String ERROR_RECEIVE_FOLDER_NOT_WRITABLE = "RECEIVE_FOLDER_NOT_WRITABLE";
    public static final String ERROR_DISK_SPACE_LOW = "DISK_SPACE_LOW";
    public static final String ERROR_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String ERROR_TOTAL_TRANSFER_TOO_LARGE = "TOTAL_TRANSFER_TOO_LARGE";
    public static final String ERROR_TOO_MANY_FILES = "TOO_MANY_FILES";
    public static final String ERROR_INVALID_FILE_PATH = "INVALID_FILE_PATH";
    public static final String ERROR_INVALID_FILE_NAME = "INVALID_FILE_NAME";
    public static final String ERROR_FILE_READ_ERROR = "FILE_READ_ERROR";
    public static final String ERROR_FILE_WRITE_ERROR = "FILE_WRITE_ERROR";
    public static final String ERROR_CONNECTION_TIMEOUT = "CONNECTION_TIMEOUT";
    public static final String ERROR_HEADER_TIMEOUT = "HEADER_TIMEOUT";
    public static final String ERROR_TRANSFER_TIMEOUT = "TRANSFER_TIMEOUT";
    public static final String ERROR_CONNECTION_LOST = "CONNECTION_LOST";
    public static final String ERROR_TRANSFER_CANCELLED = "TRANSFER_CANCELLED";
    public static final String ERROR_SESSION_BUSY = "SESSION_BUSY";
    public static final String ERROR_TRANSFER_PORT_UNAVAILABLE = "TRANSFER_PORT_UNAVAILABLE";

    public static final String DIAGNOSTIC_DISCOVERY_PORT_UNAVAILABLE = "DISCOVERY_PORT_UNAVAILABLE";
    public static final String DIAGNOSTIC_DISCOVERY_SOCKET_ERROR = "DISCOVERY_SOCKET_ERROR";
    public static final String DIAGNOSTIC_DISCOVERY_PACKET_MALFORMED = "DISCOVERY_PACKET_MALFORMED";
    public static final String DIAGNOSTIC_DISCOVERY_PACKET_REJECTED = "DISCOVERY_PACKET_REJECTED";
    public static final String DIAGNOSTIC_DISCOVERY_LISTENER_STOPPED = "DISCOVERY_LISTENER_STOPPED";
    public static final String DIAGNOSTIC_TRANSFER_SERVER_NOT_RUNNING = "TRANSFER_SERVER_NOT_RUNNING";
    public static final String DIAGNOSTIC_TRANSFER_SERVER_BUSY = "TRANSFER_SERVER_BUSY";
    public static final String DIAGNOSTIC_TRANSFER_CLIENT_NOT_STARTED = "TRANSFER_CLIENT_NOT_STARTED";
    public static final String DIAGNOSTIC_DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND";
    public static final String DIAGNOSTIC_DEVICE_NOT_LIVE = "DEVICE_NOT_LIVE";
    public static final String DIAGNOSTIC_DEVICE_NOT_READY = "DEVICE_NOT_READY";
    public static final String DIAGNOSTIC_STALE_DEVICE_ADDRESS = "STALE_DEVICE_ADDRESS";
    public static final String DIAGNOSTIC_LOCAL_NETWORK_UNAVAILABLE = "LOCAL_NETWORK_UNAVAILABLE";
    public static final String DIAGNOSTIC_LOCAL_IP_NOT_DETECTED = "LOCAL_IP_NOT_DETECTED";
    public static final String DIAGNOSTIC_POSSIBLE_FIREWALL_BLOCK = "POSSIBLE_FIREWALL_BLOCK";
    public static final String DIAGNOSTIC_POSSIBLE_PUBLIC_NETWORK = "POSSIBLE_PUBLIC_NETWORK";
    public static final String DIAGNOSTIC_POSSIBLE_VPN_ACTIVE = "POSSIBLE_VPN_ACTIVE";
    public static final String DIAGNOSTIC_POSSIBLE_CLIENT_ISOLATION = "POSSIBLE_CLIENT_ISOLATION";
    public static final String DIAGNOSTIC_EXPORT_FAILED = "DIAGNOSTICS_EXPORT_FAILED";

    private ProtocolConstants() {
    }
}

package com.localdrop.protocol;

public final class ProtocolConstants {
    public static final int PROTOCOL_VERSION = 2;
    public static final int DISCOVERY_PORT = 45454;
    public static final int DEFAULT_TRANSFER_PORT = 45455;
    public static final int DISCOVERY_PACKET_MAX_BYTES = 8192;
    public static final int MAX_HEADER_BYTES = 65_536;
    public static final int MAX_DEVICE_NAME_LENGTH = 64;
    public static final int MAX_FILE_NAME_LENGTH = 180;
    public static final int MAX_RELATIVE_PATH_LENGTH = 1024;
    public static final int MAX_RELATIVE_PATH_DEPTH = 12;
    public static final int MAX_FILES_PER_SESSION = 1000;
    public static final long MAX_FILE_SIZE_BYTES = 4L * 1024L * 1024L * 1024L;
    public static final long MAX_TOTAL_SESSION_BYTES = 20L * 1024L * 1024L * 1024L;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int CONTROL_READ_TIMEOUT_MS = 10_000;
    public static final int PAYLOAD_READ_TIMEOUT_MS = 30_000;
    public static final long DISCOVERY_BROADCAST_INTERVAL_SECONDS = 3;
    public static final long DISCOVERY_DEVICE_TIMEOUT_MILLIS = 20_000;
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

    public static final String ERROR_BUSY = "BUSY";
    public static final String ERROR_VERSION_UNSUPPORTED = "VERSION_UNSUPPORTED";
    public static final String ERROR_LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
    public static final String ERROR_INVALID_MESSAGE = "INVALID_MESSAGE";
    public static final String ERROR_RECEIVE_FOLDER_NOT_CONFIGURED = "RECEIVE_FOLDER_NOT_CONFIGURED";

    private ProtocolConstants() {
    }
}

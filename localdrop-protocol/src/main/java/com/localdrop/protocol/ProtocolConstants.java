package com.localdrop.protocol;

public final class ProtocolConstants {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DISCOVERY_PORT = 45454;
    public static final int DEFAULT_TRANSFER_PORT = 45455;
    public static final long DISCOVERY_BROADCAST_INTERVAL_SECONDS = 3;
    public static final long DISCOVERY_DEVICE_TIMEOUT_MILLIS = 10_000;

    public static final String TYPE_DISCOVERY = "DISCOVERY";
    public static final String TYPE_SESSION_START = "SESSION_START";
    public static final String TYPE_SESSION_ACCEPTED = "SESSION_ACCEPTED";
    public static final String TYPE_SESSION_REJECTED = "SESSION_REJECTED";
    public static final String TYPE_FILE_META = "FILE_META";
    public static final String TYPE_FILE_ACK = "FILE_ACK";
    public static final String TYPE_SESSION_END = "SESSION_END";
    public static final String TYPE_SESSION_CLOSED = "SESSION_CLOSED";

    public static final String STATUS_READY = "READY";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String DEVICE_TYPE_PC = "PC";
    public static final String DEVICE_TYPE_PHONE = "PHONE";

    private ProtocolConstants() {
    }
}

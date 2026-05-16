package com.localdrop.diagnostics;

public record DiagnosticDeviceEntry(
    String deviceId,
    String deviceName,
    String deviceType,
    String status,
    String hostAddress,
    int tcpPort,
    long lastSeenAt,
    String note
) {
}

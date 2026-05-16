package com.localdrop.diagnostics;

public record DiagnosticEvent(
    long timestamp,
    DiagnosticEventType eventType,
    DiagnosticDirection direction,
    String remoteAddress,
    String deviceId,
    String deviceName,
    String deviceType,
    String status,
    String errorCode,
    String message
) {
}

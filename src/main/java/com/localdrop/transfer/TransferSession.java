package com.localdrop.transfer;

public record TransferSession(
    String sessionId,
    String senderDeviceId,
    String senderDeviceName,
    int totalFiles,
    long totalSize
) {
}

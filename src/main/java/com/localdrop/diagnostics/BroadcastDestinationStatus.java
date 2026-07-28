package com.localdrop.diagnostics;

public record BroadcastDestinationStatus(
    String address,
    long lastSuccessAt,
    long lastFailureAt,
    String lastError,
    long successCount,
    long failureCount
) {
}

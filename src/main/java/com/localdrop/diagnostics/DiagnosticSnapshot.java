package com.localdrop.diagnostics;

import java.util.List;

public record DiagnosticSnapshot(
    String contractRevision,
    String appVersion,
    String osVersion,
    String localDeviceId,
    String localDeviceName,
    String localDeviceType,
    List<String> localIpAddresses,
    List<String> activeNetworkInterfaces,
    int discoveryPort,
    int transferPort,
    String discoveryStatus,
    boolean discoveryListenerBound,
    String transferServerStatus,
    long lastDiscoverySentAt,
    long lastDiscoveryReceivedAt,
    String lastDiscoveryReceivedFrom,
    String lastDiscoveryErrorCode,
    long lastDiscoveryErrorAt,
    long lastDiscoveryRecoveredAt,
    String lastTransferErrorCode,
    long lastTransferErrorAt,
    long lastTransferRecoveredAt,
    List<BroadcastDestinationStatus> broadcastDestinations,
    int liveDevicesCount,
    int mainListDevicesCount,
    int diagnosticDevicesCount,
    List<DiagnosticDeviceEntry> readyDevices,
    List<DiagnosticDeviceEntry> unavailableDevices,
    List<DiagnosticDeviceEntry> recentExpiredDevices,
    List<DiagnosticEvent> recentEvents
) {
}

package com.localdrop.diagnostics;

import java.util.List;

public record DiagnosticSnapshot(
    String contractRevision,
    String localDeviceId,
    String localDeviceName,
    String localDeviceType,
    List<String> localIpAddresses,
    List<String> activeNetworkInterfaces,
    int discoveryPort,
    int transferPort,
    String discoveryStatus,
    String transferServerStatus,
    long lastDiscoverySentAt,
    long lastDiscoveryReceivedAt,
    String lastDiscoveryReceivedFrom,
    String lastDiscoveryErrorCode,
    String lastTransferErrorCode,
    int liveDevicesCount,
    int mainListDevicesCount,
    int diagnosticDevicesCount,
    List<DiagnosticDeviceEntry> readyDevices,
    List<DiagnosticDeviceEntry> unavailableDevices,
    List<DiagnosticDeviceEntry> recentExpiredDevices,
    List<DiagnosticEvent> recentEvents
) {
}

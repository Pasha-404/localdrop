package com.localdrop.protocol.discovery;

import java.util.ArrayList;
import java.util.List;

public class DeviceInfo {
    private final String deviceId;
    private final String deviceName;
    private final String deviceType;
    private final String status;
    private final String hostAddress;
    private final int tcpPort;
    private final List<String> capabilities;
    private volatile long lastSeenAt;

    public DeviceInfo(
        String deviceId,
        String deviceName,
        String deviceType,
        String status,
        String hostAddress,
        int tcpPort,
        List<String> capabilities,
        long lastSeenAt
    ) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.status = status;
        this.hostAddress = hostAddress;
        this.tcpPort = tcpPort;
        this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        this.lastSeenAt = lastSeenAt;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public String getStatus() {
        return status;
    }

    public String getHostAddress() {
        return hostAddress;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public List<String> getCapabilities() {
        return new ArrayList<>(capabilities);
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getStatusLabel() {
        if (status == null || status.isBlank()) {
            return "Ready";
        }
        return switch (status.toUpperCase()) {
            case "READY", "READY_COMPAT" -> "Ready";
            case "BUSY" -> "Busy";
            default -> "Online";
        };
    }
}

package com.localdrop.protocol.discovery;

import com.localdrop.protocol.ProtocolConstants;

public class DeviceInfo {
    private final String deviceId;
    private final String deviceName;
    private final String deviceType;
    private final String status;
    private final String hostAddress;
    private final int tcpPort;
    private volatile long lastSeenAt;

    public DeviceInfo(
        String deviceId,
        String deviceName,
        String deviceType,
        String status,
        String hostAddress,
        int tcpPort,
        long lastSeenAt
    ) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.status = status;
        this.hostAddress = hostAddress;
        this.tcpPort = tcpPort;
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

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public String getStatusLabel() {
        return ProtocolConstants.STATUS_READY.equalsIgnoreCase(status) ? "Ready" : "Online";
    }
}

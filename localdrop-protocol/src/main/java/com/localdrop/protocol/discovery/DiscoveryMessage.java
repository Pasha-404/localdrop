package com.localdrop.protocol.discovery;

import com.localdrop.protocol.ProtocolConstants;

public class DiscoveryMessage {
    private String type;
    private int protocolVersion;
    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String status;
    private int tcpPort;
    private long timestamp;

    public DiscoveryMessage() {
    }

    public static DiscoveryMessage create(String deviceId, String deviceName, String deviceType, int tcpPort) {
        DiscoveryMessage message = new DiscoveryMessage();
        message.setType(ProtocolConstants.TYPE_DISCOVERY);
        message.setProtocolVersion(ProtocolConstants.PROTOCOL_VERSION);
        message.setDeviceId(deviceId);
        message.setDeviceName(deviceName);
        message.setDeviceType(deviceType);
        message.setStatus(ProtocolConstants.STATUS_READY);
        message.setTcpPort(tcpPort);
        message.setTimestamp(System.currentTimeMillis());
        return message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}

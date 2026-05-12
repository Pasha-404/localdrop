package com.localdrop.protocol.discovery;

import com.localdrop.protocol.ProtocolConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DiscoveryMessage {
    private String type;
    private Integer protocolVersion;
    private String messageId;
    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String status;
    private Integer tcpPort;
    private List<String> capabilities;
    private Long timestamp;

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
        message.setCapabilities(ProtocolConstants.CAPABILITIES);
        message.setMessageId(UUID.randomUUID().toString());
        message.setTimestamp(System.currentTimeMillis());
        return message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(Integer protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
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

    public Integer getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(Integer tcpPort) {
        this.tcpPort = tcpPort;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities == null ? null : new ArrayList<>(capabilities);
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

}

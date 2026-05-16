package com.localdrop.protocol.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.ProtocolJson;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProtocolMessage {
    private String type;
    private Integer protocolVersion;
    private String messageId;
    private String sessionId;
    private String fileId;
    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String status;
    private Integer tcpPort;
    private List<String> capabilities;
    private Long timestamp;
    private String relativePath;
    private String fileName;
    private Long size;
    private Long totalSize;
    private String sha256;
    private Long lastModified;
    private Integer totalFiles;
    private String reason;
    private String errorCode;
    private String errorMessage;
    private Boolean success;
    private Boolean checksumOk;
    private String savedAs;

    public static ProtocolMessage sessionStart(
        String sessionId,
        String senderDeviceId,
        String senderDeviceName,
        String senderDeviceType,
        int totalFiles,
        long totalSize,
        List<String> capabilities
    ) {
        ProtocolMessage message = base(ProtocolConstants.TYPE_SESSION_START);
        message.setSessionId(sessionId);
        message.setDeviceId(senderDeviceId);
        message.setDeviceName(senderDeviceName);
        message.setDeviceType(senderDeviceType);
        message.setTotalFiles(totalFiles);
        message.setTotalSize(totalSize);
        message.setCapabilities(capabilities);
        return message;
    }

    public static ProtocolMessage sessionAccepted(
        String sessionId,
        String localDeviceId,
        String localDeviceName,
        String localDeviceType
    ) {
        ProtocolMessage message = base(ProtocolConstants.TYPE_SESSION_ACCEPTED);
        message.setSessionId(sessionId);
        message.setDeviceId(localDeviceId);
        message.setDeviceName(localDeviceName);
        message.setDeviceType(localDeviceType);
        return message;
    }

    public static ProtocolMessage sessionRejected(String sessionId, String reason, String errorCode) {
        ProtocolMessage message = base(ProtocolConstants.TYPE_SESSION_REJECTED);
        message.setSessionId(sessionId);
        message.setReason(reason);
        message.setErrorCode(errorCode);
        message.setErrorMessage(reason);
        message.setSuccess(false);
        return message;
    }

    public static ProtocolMessage fileMeta(
        String sessionId,
        String fileId,
        String senderDeviceId,
        String relativePath,
        String fileName,
        long size,
        long lastModified
    ) {
        ProtocolMessage message = base(ProtocolConstants.TYPE_FILE_META);
        message.setSessionId(sessionId);
        message.setFileId(fileId);
        message.setDeviceId(senderDeviceId);
        message.setRelativePath(relativePath);
        message.setFileName(fileName);
        message.setSize(size);
        message.setLastModified(lastModified);
        return message;
    }

    public static ProtocolMessage fileAck(
        String sessionId,
        String fileId,
        String localDeviceId,
        String localDeviceName,
        String localDeviceType,
        boolean ok,
        String reason,
        String errorCode
    ) {
        ProtocolMessage message = base(ProtocolConstants.TYPE_FILE_ACK);
        message.setSessionId(sessionId);
        message.setFileId(fileId);
        message.setDeviceId(localDeviceId);
        message.setDeviceName(localDeviceName);
        message.setDeviceType(localDeviceType);
        message.setStatus(ok ? ProtocolConstants.STATUS_OK : ProtocolConstants.STATUS_ERROR);
        message.setSuccess(ok);
        message.setChecksumOk(ok);
        if (!ok) {
            message.setReason(reason);
            message.setErrorCode(errorCode);
            message.setErrorMessage(reason);
        }
        return message;
    }

    public static ProtocolMessage sessionFinish(String sessionId, String senderDeviceId) {
        ProtocolMessage message = base(ProtocolConstants.TYPE_SESSION_FINISH);
        message.setSessionId(sessionId);
        message.setDeviceId(senderDeviceId);
        return message;
    }

    public static ProtocolMessage sessionFinishAck(
        String sessionId,
        String localDeviceId,
        String localDeviceName,
        String localDeviceType
    ) {
        ProtocolMessage message = base(ProtocolConstants.TYPE_SESSION_FINISH_ACK);
        message.setSessionId(sessionId);
        message.setDeviceId(localDeviceId);
        message.setDeviceName(localDeviceName);
        message.setDeviceType(localDeviceType);
        return message;
    }

    public static ProtocolMessage read(DataInputStream inputStream) throws IOException {
        int headerLength;
        try {
            headerLength = inputStream.readInt();
        } catch (EOFException exception) {
            throw exception;
        }
        if (headerLength <= 0 || headerLength > ProtocolConstants.MAX_HEADER_BYTES) {
            throw new IOException("Invalid protocol header length: " + headerLength);
        }
        byte[] headerBytes = inputStream.readNBytes(headerLength);
        if (headerBytes.length != headerLength) {
            throw new EOFException("Unexpected end of stream while reading protocol header");
        }
        return ProtocolJson.mapper().readValue(headerBytes, ProtocolMessage.class);
    }

    public static void write(DataOutputStream outputStream, ProtocolMessage message) throws IOException {
        byte[] headerBytes = ProtocolJson.mapper().writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
        if (headerBytes.length > ProtocolConstants.MAX_HEADER_BYTES) {
            throw new IOException("Protocol header exceeds " + ProtocolConstants.MAX_HEADER_BYTES + " bytes.");
        }
        outputStream.writeInt(headerBytes.length);
        outputStream.write(headerBytes);
        outputStream.flush();
    }

    private static ProtocolMessage base(String type) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(type);
        message.setProtocolVersion(ProtocolConstants.PROTOCOL_VERSION);
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
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

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public Long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(Long totalSize) {
        this.totalSize = totalSize;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Long getLastModified() {
        return lastModified;
    }

    public void setLastModified(Long lastModified) {
        this.lastModified = lastModified;
    }

    public Integer getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(Integer totalFiles) {
        this.totalFiles = totalFiles;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Boolean getChecksumOk() {
        return checksumOk;
    }

    public void setChecksumOk(Boolean checksumOk) {
        this.checksumOk = checksumOk;
    }

    public String getSavedAs() {
        return savedAs;
    }

    public void setSavedAs(String savedAs) {
        this.savedAs = savedAs;
    }

    public String getSenderDeviceId() {
        return deviceId;
    }

    public void setSenderDeviceId(String senderDeviceId) {
        this.deviceId = senderDeviceId;
    }

    public String getSenderDeviceName() {
        return deviceName;
    }

    public void setSenderDeviceName(String senderDeviceName) {
        this.deviceName = senderDeviceName;
    }

    public Integer getFileCount() {
        return totalFiles;
    }

    public void setFileCount(Integer fileCount) {
        this.totalFiles = fileCount;
    }

    public String getMessage() {
        return errorMessage != null ? errorMessage : reason;
    }

    public void setMessage(String message) {
        this.reason = message;
        this.errorMessage = message;
    }
}

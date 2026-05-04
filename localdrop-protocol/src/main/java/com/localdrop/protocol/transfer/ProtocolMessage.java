package com.localdrop.protocol.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.ProtocolJson;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ProtocolMessage {
    private String type;
    private Integer protocolVersion;
    private String sessionId;
    @JsonAlias("deviceId")
    private String senderDeviceId;
    @JsonAlias("deviceName")
    private String senderDeviceName;
    @JsonAlias("totalFiles")
    private Integer fileCount;
    private String fileId;
    private String relativePath;
    private String fileName;
    private Long size;
    private Long lastModified;
    private String status;
    @JsonAlias("reason")
    private String message;
    private String savedAs;

    public static ProtocolMessage sessionStart(String sessionId, String senderDeviceId, String senderDeviceName, int fileCount) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(ProtocolConstants.TYPE_SESSION_START);
        message.setProtocolVersion(ProtocolConstants.PROTOCOL_VERSION);
        message.setSessionId(sessionId);
        message.setSenderDeviceId(senderDeviceId);
        message.setSenderDeviceName(senderDeviceName);
        message.setFileCount(fileCount);
        return message;
    }

    public static ProtocolMessage sessionAccepted(String sessionId) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(ProtocolConstants.TYPE_SESSION_ACCEPTED);
        message.setSessionId(sessionId);
        return message;
    }

    public static ProtocolMessage sessionRejected(String sessionId, String reason) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(ProtocolConstants.TYPE_SESSION_REJECTED);
        message.setSessionId(sessionId);
        message.setMessage(reason);
        return message;
    }

    public static ProtocolMessage fileMeta(
        String sessionId,
        String fileId,
        String relativePath,
        String fileName,
        long size,
        long lastModified
    ) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(ProtocolConstants.TYPE_FILE_META);
        message.setSessionId(sessionId);
        message.setFileId(fileId);
        message.setRelativePath(relativePath);
        message.setFileName(fileName);
        message.setSize(size);
        message.setLastModified(lastModified);
        return message;
    }

    public static ProtocolMessage fileAck(String sessionId, String fileId, boolean ok, String payloadMessage) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(ProtocolConstants.TYPE_FILE_ACK);
        message.setSessionId(sessionId);
        message.setFileId(fileId);
        message.setStatus(ok ? ProtocolConstants.STATUS_OK : ProtocolConstants.STATUS_ERROR);
        if (ok) {
            message.setSavedAs(payloadMessage);
        } else {
            message.setMessage(payloadMessage);
        }
        return message;
    }

    public static ProtocolMessage sessionEnd(String sessionId) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(ProtocolConstants.TYPE_SESSION_END);
        message.setSessionId(sessionId);
        return message;
    }

    public static ProtocolMessage sessionClosed(String sessionId) {
        ProtocolMessage message = new ProtocolMessage();
        message.setType(ProtocolConstants.TYPE_SESSION_CLOSED);
        message.setSessionId(sessionId);
        return message;
    }

    public static ProtocolMessage read(DataInputStream inputStream) throws IOException {
        int headerLength;
        try {
            headerLength = inputStream.readInt();
        } catch (EOFException exception) {
            throw exception;
        }
        if (headerLength <= 0 || headerLength > 1024 * 1024) {
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
        outputStream.writeInt(headerBytes.length);
        outputStream.write(headerBytes);
        outputStream.flush();
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSenderDeviceId() {
        return senderDeviceId;
    }

    public void setSenderDeviceId(String senderDeviceId) {
        this.senderDeviceId = senderDeviceId;
    }

    public String getSenderDeviceName() {
        return senderDeviceName;
    }

    public void setSenderDeviceName(String senderDeviceName) {
        this.senderDeviceName = senderDeviceName;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public void setFileCount(Integer fileCount) {
        this.fileCount = fileCount;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
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

    public Long getLastModified() {
        return lastModified;
    }

    public void setLastModified(Long lastModified) {
        this.lastModified = lastModified;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSavedAs() {
        return savedAs;
    }

    public void setSavedAs(String savedAs) {
        this.savedAs = savedAs;
    }
}

package com.localdrop.transfer;

import com.localdrop.diagnostics.DiagnosticDirection;
import com.localdrop.diagnostics.DiagnosticEventType;
import com.localdrop.diagnostics.DiagnosticsService;
import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.protocol.transfer.ProtocolMessage;
import com.localdrop.util.LogService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class TransferClient {
    private enum TransferPhase {
        CONNECT,
        SESSION_HEADER,
        FILE_PAYLOAD,
        FILE_ACK,
        FINISH_ACK
    }

    public interface Listener {
        void onItemStatusChanged(TransferQueueItem item, TransferStatus status, String message);

        void onItemProgress(TransferQueueItem item, double progress);

        void onItemAcknowledged(TransferQueueItem item);

        void onTransferIssue(String targetDeviceName, String details);

        void onReceiverRejected(String reason);

        void onTransferFinished();
    }

    private final Logger logger = LogService.getLogger(TransferClient.class);
    private final DiagnosticsService diagnosticsService;

    public TransferClient() {
        this(null);
    }

    public TransferClient(DiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    public void sendFiles(
        DeviceInfo target,
        String senderDeviceId,
        String senderDeviceName,
        String senderDeviceType,
        List<TransferQueueItem> items,
        Listener listener
    ) {
        if (items.isEmpty()) {
            listener.onTransferFinished();
            return;
        }

        long totalSize = items.stream().mapToLong(TransferQueueItem::getSize).sum();
        if (items.size() > ProtocolConstants.MAX_FILES_PER_SESSION) {
            markRemaining(items, TransferStatus.FAILED, ProtocolConstants.ERROR_TOO_MANY_FILES, listener);
            listener.onTransferFinished();
            return;
        }
        if (totalSize > ProtocolConstants.MAX_TOTAL_SESSION_BYTES) {
            markRemaining(items, TransferStatus.FAILED, ProtocolConstants.ERROR_TOTAL_TRANSFER_TOO_LARGE, listener);
            listener.onTransferFinished();
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        TransferQueueItem currentItem = null;
        TransferPhase phase = TransferPhase.CONNECT;

        try (Socket socket = new Socket()) {
            recordEvent(DiagnosticEventType.TRANSFER_CLIENT_CONNECTING, target, null, "Connecting to receiver.");
            socket.connect(new InetSocketAddress(target.getHostAddress(), target.getTcpPort()), ProtocolConstants.CONNECT_TIMEOUT_MS);
            recordEvent(DiagnosticEventType.TRANSFER_CLIENT_CONNECTED, target, null, "Connected to receiver.");
            socket.setSoTimeout(ProtocolConstants.HEADER_READ_TIMEOUT_MS);

            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                phase = TransferPhase.SESSION_HEADER;
                ProtocolMessage.write(output, ProtocolMessage.sessionStart(
                    sessionId,
                    senderDeviceId,
                    senderDeviceName,
                    senderDeviceType,
                    items.size(),
                    totalSize,
                    ProtocolConstants.CAPABILITIES
                ));

                ProtocolMessage response = ProtocolMessage.read(input);
                if (ProtocolConstants.TYPE_SESSION_REJECTED.equals(response.getType())) {
                    markRemaining(items, TransferStatus.WAITING_FOR_RETRY, "", listener);
                    listener.onReceiverRejected(resolveProtocolError(response));
                    recordEvent(DiagnosticEventType.TRANSFER_CLIENT_ERROR, target, response.getErrorCode(), "Receiver rejected the session.");
                    return;
                }
                if (!isExpectedResponse(response, ProtocolConstants.TYPE_SESSION_ACCEPTED, sessionId, null, target.getDeviceId())) {
                    markRemaining(items, TransferStatus.WAITING_FOR_RETRY, ProtocolConstants.ERROR_MALFORMED_MESSAGE, listener);
                    listener.onTransferIssue(target.getDeviceName(), ProtocolConstants.ERROR_MALFORMED_MESSAGE);
                    recordEvent(DiagnosticEventType.TRANSFER_CLIENT_ERROR, target, ProtocolConstants.ERROR_MALFORMED_MESSAGE, "Unexpected SESSION_ACCEPTED response.");
                    return;
                }

                logger.info("Started transfer session " + sessionId + " to " + target.getDeviceName());
                for (int index = 0; index < items.size(); index++) {
                    currentItem = items.get(index);
                    listener.onItemStatusChanged(currentItem, TransferStatus.SENDING, "");
                    listener.onItemProgress(currentItem, 0);
                    recordEvent(DiagnosticEventType.TRANSFER_FILE_STARTED, target, null, currentItem.getRelativePath());

                    ProtocolMessage.write(output, ProtocolMessage.fileMeta(
                        sessionId,
                        currentItem.getId(),
                        senderDeviceId,
                        currentItem.getRelativePath(),
                        currentItem.getSourcePath().getFileName().toString(),
                        currentItem.getSize(),
                        currentItem.getLastModified()
                    ));

                    phase = TransferPhase.FILE_PAYLOAD;
                    streamFile(currentItem, output, listener);

                    phase = TransferPhase.FILE_ACK;
                    socket.setSoTimeout(ProtocolConstants.ACK_READ_TIMEOUT_MS);
                    ProtocolMessage ack = ProtocolMessage.read(input);
                    String ackError = validateFileAck(ack, sessionId, currentItem.getId(), target.getDeviceId());
                    if (ackError != null) {
                        listener.onItemStatusChanged(currentItem, TransferStatus.FAILED, ackError);
                        markRemaining(items.subList(index + 1, items.size()), TransferStatus.WAITING_FOR_RETRY, "", listener);
                        listener.onTransferIssue(target.getDeviceName(), ackError);
                        recordEvent(DiagnosticEventType.TRANSFER_FILE_FAILED, target, ackError, currentItem.getRelativePath());
                        logger.warning("Transfer failed for " + currentItem.getRelativePath() + ": " + ackError);
                        return;
                    }

                    listener.onItemStatusChanged(currentItem, TransferStatus.SENT, currentItem.getSourcePath().getFileName().toString());
                    listener.onItemAcknowledged(currentItem);
                    recordEvent(DiagnosticEventType.TRANSFER_FILE_ACKED, target, null, currentItem.getRelativePath());
                    logger.info("Transferred file " + currentItem.getRelativePath() + " to " + target.getDeviceName());
                    currentItem = null;
                }

                phase = TransferPhase.FINISH_ACK;
                socket.setSoTimeout(ProtocolConstants.HEADER_READ_TIMEOUT_MS);
                ProtocolMessage.write(output, ProtocolMessage.sessionFinish(sessionId, senderDeviceId));
                ProtocolMessage finalResponse = ProtocolMessage.read(input);
                if (!isExpectedResponse(finalResponse, ProtocolConstants.TYPE_SESSION_FINISH_ACK, sessionId, null, target.getDeviceId())) {
                    listener.onTransferIssue(target.getDeviceName(), ProtocolConstants.ERROR_MALFORMED_MESSAGE);
                    recordEvent(DiagnosticEventType.TRANSFER_CLIENT_ERROR, target, ProtocolConstants.ERROR_MALFORMED_MESSAGE, "Receiver did not confirm session finish.");
                    return;
                }
                logger.info("Completed transfer session " + sessionId + " to " + target.getDeviceName());
            }
        } catch (TransferException exception) {
            handleFailure(target, items, currentItem, listener, exception.getErrorCode(), exception.getMessage());
        } catch (IOException exception) {
            handleFailure(target, items, currentItem, listener, mapIoErrorCode(exception, phase), resolveIoMessage(exception, phase));
        } finally {
            listener.onTransferFinished();
        }
    }

    private void streamFile(TransferQueueItem item, DataOutputStream output, Listener listener) throws IOException {
        long total = item.getSize();
        long sent = 0;
        byte[] buffer = new byte[64 * 1024];

        try (var inputStream = new BufferedInputStream(Files.newInputStream(item.getSourcePath()))) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                sent += read;
                listener.onItemProgress(item, total == 0 ? 1.0 : Math.min(1.0, (double) sent / total));
            }
            output.flush();
        } catch (IOException exception) {
            throw new TransferException(ProtocolConstants.ERROR_FILE_READ_ERROR, exception.getMessage());
        }
    }

    private boolean isExpectedResponse(
        ProtocolMessage message,
        String expectedType,
        String sessionId,
        String fileId,
        String expectedDeviceId
    ) {
        if (message == null || !expectedType.equals(message.getType())) {
            return false;
        }
        if (!Integer.valueOf(ProtocolConstants.PROTOCOL_VERSION).equals(message.getProtocolVersion())) {
            return false;
        }
        if (sessionId != null && !sessionId.equals(message.getSessionId())) {
            return false;
        }
        if (fileId != null && !fileId.equals(message.getFileId())) {
            return false;
        }
        return expectedDeviceId == null || expectedDeviceId.equals(message.getDeviceId());
    }

    private String validateFileAck(
        ProtocolMessage ack,
        String sessionId,
        String fileId,
        String expectedDeviceId
    ) {
        if (ack == null || !ProtocolConstants.TYPE_FILE_ACK.equals(ack.getType())) {
            return ProtocolConstants.ERROR_ACK_MISMATCH;
        }
        if (!Integer.valueOf(ProtocolConstants.PROTOCOL_VERSION).equals(ack.getProtocolVersion())) {
            return ProtocolConstants.ERROR_PROTOCOL_VERSION_MISMATCH;
        }
        if (!sessionId.equals(ack.getSessionId()) || !fileId.equals(ack.getFileId())) {
            return ProtocolConstants.ERROR_ACK_MISMATCH;
        }
        if (expectedDeviceId != null && !expectedDeviceId.equals(ack.getDeviceId())) {
            return ProtocolConstants.ERROR_ACK_MISMATCH;
        }
        boolean success = Boolean.TRUE.equals(ack.getSuccess())
            || Boolean.TRUE.equals(ack.getChecksumOk())
            || ProtocolConstants.STATUS_OK.equalsIgnoreCase(ack.getStatus());
        if (!success) {
            return resolveProtocolError(ack);
        }
        return null;
    }

    private void markRemaining(List<TransferQueueItem> items, TransferStatus status, String message, Listener listener) {
        for (TransferQueueItem item : items) {
            listener.onItemStatusChanged(item, status, message);
            listener.onItemProgress(item, 0);
        }
    }

    private void handleFailure(
        DeviceInfo target,
        List<TransferQueueItem> items,
        TransferQueueItem currentItem,
        Listener listener,
        String errorCode,
        String details
    ) {
        String message = details == null || details.isBlank() ? errorCode : details;
        if (currentItem != null) {
            listener.onItemStatusChanged(currentItem, TransferStatus.FAILED, message);
            int failedIndex = items.indexOf(currentItem);
            if (failedIndex >= 0 && failedIndex + 1 < items.size()) {
                markRemaining(items.subList(failedIndex + 1, items.size()), TransferStatus.WAITING_FOR_RETRY, "", listener);
            }
        } else {
            markRemaining(items, TransferStatus.WAITING_FOR_RETRY, "", listener);
        }
        listener.onTransferIssue(target.getDeviceName(), message);
        recordEvent(DiagnosticEventType.TRANSFER_CLIENT_ERROR, target, errorCode, message);
        logger.warning("Transfer session failed: " + message);
    }

    private String resolveProtocolError(ProtocolMessage message) {
        if (message == null) {
            return ProtocolConstants.ERROR_UNKNOWN;
        }
        if (message.getErrorCode() != null && !message.getErrorCode().isBlank()) {
            return message.getErrorCode();
        }
        if (message.getErrorMessage() != null && !message.getErrorMessage().isBlank()) {
            return message.getErrorMessage();
        }
        if (message.getReason() != null && !message.getReason().isBlank()) {
            return message.getReason();
        }
        if (message.getStatus() != null && !message.getStatus().isBlank()) {
            return "Receiver returned status: " + message.getStatus();
        }
        return ProtocolConstants.ERROR_UNKNOWN;
    }

    private String mapIoErrorCode(IOException exception, TransferPhase phase) {
        if (exception instanceof SocketTimeoutException) {
            return switch (phase) {
                case CONNECT -> ProtocolConstants.ERROR_CONNECTION_TIMEOUT;
                case SESSION_HEADER, FINISH_ACK -> ProtocolConstants.ERROR_HEADER_TIMEOUT;
                case FILE_PAYLOAD, FILE_ACK -> ProtocolConstants.ERROR_TRANSFER_TIMEOUT;
            };
        }
        if (exception instanceof EOFException) {
            return ProtocolConstants.ERROR_CONNECTION_LOST;
        }
        return ProtocolConstants.ERROR_UNKNOWN;
    }

    private String resolveIoMessage(IOException exception, TransferPhase phase) {
        String errorCode = mapIoErrorCode(exception, phase);
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return errorCode;
    }

    private void recordEvent(DiagnosticEventType eventType, DeviceInfo target, String errorCode, String message) {
        if (diagnosticsService == null) {
            return;
        }
        diagnosticsService.recordTransferEvent(
            eventType,
            DiagnosticDirection.OUT,
            target == null ? null : target.getHostAddress(),
            target == null ? null : target.getDeviceId(),
            target == null ? null : target.getDeviceName(),
            target == null ? null : target.getDeviceType(),
            target == null ? null : target.getStatus(),
            errorCode,
            message
        );
    }
}

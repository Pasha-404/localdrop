package com.localdrop.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.protocol.transfer.ProtocolMessage;
import com.localdrop.util.LogService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class TransferClient {
    public interface Listener {
        void onItemStatusChanged(TransferQueueItem item, TransferStatus status, String message);

        void onItemProgress(TransferQueueItem item, double progress);

        void onItemAcknowledged(TransferQueueItem item);

        void onTransferIssue(String targetDeviceName, String details);

        void onReceiverRejected(String reason);

        void onTransferFinished();
    }

    private final Logger logger = LogService.getLogger(TransferClient.class);

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
        if (items.size() > ProtocolConstants.MAX_FILES_PER_SESSION || totalSize > ProtocolConstants.MAX_TOTAL_SESSION_BYTES) {
            markRemaining(items, TransferStatus.FAILED, "Transfer exceeds configured limits.", listener);
            listener.onTransferFinished();
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        TransferQueueItem currentItem = null;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.getHostAddress(), target.getTcpPort()), ProtocolConstants.CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(ProtocolConstants.CONTROL_READ_TIMEOUT_MS);

            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
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
                if (!isExpectedResponse(response, ProtocolConstants.TYPE_SESSION_ACCEPTED, sessionId, null, target.getDeviceId())) {
                    markRemaining(items, TransferStatus.WAITING_FOR_RETRY, "", listener);
                    listener.onReceiverRejected(resolveProtocolError(response));
                    return;
                }

                logger.info("Started transfer session " + sessionId + " to " + target.getDeviceName());
                for (int index = 0; index < items.size(); index++) {
                    currentItem = items.get(index);
                    listener.onItemStatusChanged(currentItem, TransferStatus.SENDING, "");
                    listener.onItemProgress(currentItem, 0);

                    ProtocolMessage.write(output, ProtocolMessage.fileMeta(
                        sessionId,
                        currentItem.getId(),
                        senderDeviceId,
                        currentItem.getRelativePath(),
                        currentItem.getSourcePath().getFileName().toString(),
                        currentItem.getSize(),
                        currentItem.getLastModified()
                    ));

                    socket.setSoTimeout(ProtocolConstants.PAYLOAD_READ_TIMEOUT_MS);
                    streamFile(currentItem, output, listener);

                    socket.setSoTimeout(ProtocolConstants.CONTROL_READ_TIMEOUT_MS);
                    ProtocolMessage ack = ProtocolMessage.read(input);
                    if (!isExpectedResponse(ack, ProtocolConstants.TYPE_FILE_ACK, sessionId, currentItem.getId(), target.getDeviceId())
                        || Boolean.FALSE.equals(ack.getChecksumOk())
                        || ProtocolConstants.STATUS_ERROR.equalsIgnoreCase(ack.getStatus())) {
                        String error = resolveProtocolError(ack);
                        listener.onItemStatusChanged(currentItem, TransferStatus.FAILED, error);
                        markRemaining(items.subList(index + 1, items.size()), TransferStatus.WAITING_FOR_RETRY, "", listener);
                        listener.onTransferIssue(target.getDeviceName(), error);
                        logger.warning("Transfer failed for " + currentItem.getRelativePath() + ": " + error);
                        return;
                    }

                    listener.onItemStatusChanged(currentItem, TransferStatus.SENT, currentItem.getSourcePath().getFileName().toString());
                    listener.onItemAcknowledged(currentItem);
                    logger.info("Transferred file " + currentItem.getRelativePath() + " to " + target.getDeviceName());
                    currentItem = null;
                }

                ProtocolMessage.write(output, ProtocolMessage.sessionFinish(sessionId, senderDeviceId));
                ProtocolMessage finalResponse = ProtocolMessage.read(input);
                if (!isExpectedResponse(finalResponse, ProtocolConstants.TYPE_SESSION_FINISH_ACK, sessionId, null, target.getDeviceId())) {
                    listener.onTransferIssue(target.getDeviceName(), "Receiver did not confirm session finish.");
                    return;
                }
                logger.info("Completed transfer session " + sessionId + " to " + target.getDeviceName());
            }
        } catch (IOException exception) {
            if (currentItem != null) {
                listener.onItemStatusChanged(currentItem, TransferStatus.FAILED, exception.getMessage());
                int failedIndex = items.indexOf(currentItem);
                if (failedIndex >= 0 && failedIndex + 1 < items.size()) {
                    markRemaining(items.subList(failedIndex + 1, items.size()), TransferStatus.WAITING_FOR_RETRY, "", listener);
                }
            } else {
                markRemaining(items, TransferStatus.WAITING_FOR_RETRY, "", listener);
            }
            listener.onTransferIssue(target.getDeviceName(), exception.getMessage());
            logger.warning("Transfer session failed: " + exception.getMessage());
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

    private void markRemaining(List<TransferQueueItem> items, TransferStatus status, String message, Listener listener) {
        for (TransferQueueItem item : items) {
            listener.onItemStatusChanged(item, status, message);
            listener.onItemProgress(item, 0);
        }
    }

    private String resolveProtocolError(ProtocolMessage message) {
        if (message == null) {
            return "Receiver returned an empty response.";
        }
        if (message.getErrorCode() != null && !message.getErrorCode().isBlank()) {
            return message.getErrorCode();
        }
        if (message.getReason() != null && !message.getReason().isBlank()) {
            return message.getReason();
        }
        if (message.getStatus() != null && !message.getStatus().isBlank()) {
            return "Receiver returned status: " + message.getStatus();
        }
        return "Unexpected response from receiver: " + message.getType();
    }
}

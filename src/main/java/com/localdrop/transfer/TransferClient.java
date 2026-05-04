package com.localdrop.transfer;

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
        List<TransferQueueItem> items,
        Listener listener
    ) {
        if (items.isEmpty()) {
            listener.onTransferFinished();
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        TransferQueueItem currentItem = null;
        boolean allFilesAcknowledged = false;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.getHostAddress(), target.getTcpPort()), 5_000);
            socket.setSoTimeout(30_000);

            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                ProtocolMessage.write(output, ProtocolMessage.sessionStart(sessionId, senderDeviceId, senderDeviceName, items.size()));
                ProtocolMessage response = ProtocolMessage.read(input);
                if (!ProtocolConstants.TYPE_SESSION_ACCEPTED.equals(response.getType())) {
                    markRemaining(items, TransferStatus.WAITING_FOR_RETRY, "", listener);
                    listener.onReceiverRejected(response.getMessage());
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
                        currentItem.getRelativePath(),
                        currentItem.getSourcePath().getFileName().toString(),
                        currentItem.getSize(),
                        currentItem.getLastModified()
                    ));
                    streamFile(currentItem, output, listener);

                    ProtocolMessage ack = ProtocolMessage.read(input);
                    boolean lastItem = index == items.size() - 1;
                    if (ProtocolConstants.TYPE_SESSION_CLOSED.equals(ack.getType()) && lastItem) {
                        String savedAs = currentItem.getSourcePath().getFileName().toString();
                        listener.onItemStatusChanged(currentItem, TransferStatus.SENT, savedAs);
                        listener.onItemAcknowledged(currentItem);
                        logger.info("Transferred file " + currentItem.getRelativePath()
                            + " to " + target.getDeviceName()
                            + " using terminal SESSION_CLOSED compatibility mode");
                        currentItem = null;
                        allFilesAcknowledged = true;
                        logger.info("Completed transfer session " + sessionId + " to " + target.getDeviceName());
                        return;
                    }

                    if (!isSuccessfulFileAck(ack)) {
                        String error = resolveAckError(ack);
                        listener.onItemStatusChanged(currentItem, TransferStatus.FAILED, error);
                        markRemaining(items.subList(index + 1, items.size()), TransferStatus.WAITING_FOR_RETRY, "", listener);
                        listener.onTransferIssue(target.getDeviceName(), error);
                        logger.warning("Transfer failed for " + currentItem.getRelativePath() + ": " + error
                            + " [type=" + ack.getType()
                            + ", status=" + ack.getStatus()
                            + ", message=" + ack.getMessage()
                            + ", savedAs=" + ack.getSavedAs() + "]");
                        return;
                    }

                    String savedAs = ack.getSavedAs() == null || ack.getSavedAs().isBlank()
                        ? currentItem.getSourcePath().getFileName().toString()
                        : ack.getSavedAs();
                    listener.onItemStatusChanged(currentItem, TransferStatus.SENT, savedAs);
                    listener.onItemAcknowledged(currentItem);
                    logger.info("Transferred file " + currentItem.getRelativePath() + " to " + target.getDeviceName());
                    currentItem = null;
                }

                allFilesAcknowledged = true;
                ProtocolMessage.write(output, ProtocolMessage.sessionEnd(sessionId));
                try {
                    ProtocolMessage finalResponse = ProtocolMessage.read(input);
                    if (!ProtocolConstants.TYPE_SESSION_CLOSED.equals(finalResponse.getType())) {
                        logger.warning("Transfer session " + sessionId + " completed with unexpected final response: " + finalResponse.getType());
                    }
                } catch (EOFException | SocketTimeoutException ignored) {
                    // SESSION_CLOSED is optional for the sender.
                }
                logger.info("Completed transfer session " + sessionId + " to " + target.getDeviceName());
            }
        } catch (IOException exception) {
            if (allFilesAcknowledged) {
                logger.warning("Transfer session " + sessionId + " completed, but final confirmation was not received: " + exception.getMessage());
                return;
            }
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

    private void markRemaining(List<TransferQueueItem> items, TransferStatus status, String message, Listener listener) {
        for (TransferQueueItem item : items) {
            listener.onItemStatusChanged(item, status, message);
            listener.onItemProgress(item, 0);
        }
    }

    private boolean isSuccessfulFileAck(ProtocolMessage ack) {
        if (!ProtocolConstants.TYPE_FILE_ACK.equals(ack.getType())) {
            return false;
        }

        String status = ack.getStatus();
        if (status == null || status.isBlank()) {
            return true;
        }

        return !ProtocolConstants.STATUS_ERROR.equalsIgnoreCase(status)
            && !"FAILED".equalsIgnoreCase(status)
            && !"REJECTED".equalsIgnoreCase(status);
    }

    private String resolveAckError(ProtocolMessage ack) {
        if (ack.getMessage() != null && !ack.getMessage().isBlank()) {
            return ack.getMessage();
        }
        if (!ProtocolConstants.TYPE_FILE_ACK.equals(ack.getType())) {
            return "Unexpected response from receiver: " + ack.getType();
        }
        if (ack.getStatus() != null && !ack.getStatus().isBlank()) {
            return "Receiver returned status: " + ack.getStatus();
        }
        return "Transfer failed.";
    }
}

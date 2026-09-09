package com.localdrop.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.protocol.transfer.ProtocolMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferClientTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsContradictoryFileAckEvenWhenAnotherFieldLooksSuccessful() {
        ProtocolMessage ack = ProtocolMessage.fileAck(
            "session-1",
            "file-1",
            "receiver-1",
            "Receiver",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            true,
            "OK",
            null
        );
        ack.setSuccess(false);
        ack.setErrorCode(ProtocolConstants.ERROR_FILE_WRITE_ERROR);

        assertEquals(
            ProtocolConstants.ERROR_FILE_WRITE_ERROR,
            TransferClient.validateFileAck(ack, "session-1", "file-1", "receiver-1")
        );
    }

    @Test
    void rejectsExplicitFileAckErrorEvenWhenAllSuccessMarkersArePositive() {
        ProtocolMessage ack = ProtocolMessage.fileAck(
            "session-1",
            "file-1",
            "receiver-1",
            "Receiver",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            true,
            "OK",
            null
        );
        ack.setErrorCode(ProtocolConstants.ERROR_FILE_WRITE_ERROR);

        assertEquals(
            ProtocolConstants.ERROR_FILE_WRITE_ERROR,
            TransferClient.validateFileAck(ack, "session-1", "file-1", "receiver-1")
        );
    }

    @Test
    void rejectsAckWithExplicitChecksumFailure() {
        ProtocolMessage ack = ProtocolMessage.fileAck(
            "session-1",
            "file-1",
            "receiver-1",
            "Receiver",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            true,
            "OK",
            null
        );
        ack.setChecksumOk(false);
        ack.setErrorCode(ProtocolConstants.ERROR_FILE_WRITE_ERROR);

        assertEquals(
            ProtocolConstants.ERROR_FILE_WRITE_ERROR,
            TransferClient.validateFileAck(ack, "session-1", "file-1", "receiver-1")
        );
    }

    @Test
    void marksDeliveryUnknownWhenPeerDisconnectsAfterPayloadBeforeFileAck() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "payload that may already be saved");
        TransferQueueItem item = queueItem(source);
        AtomicReference<TransferStatus> finalStatus = new AtomicReference<>();
        AtomicBoolean payloadReceived = new AtomicBoolean(false);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread receiver = new Thread(() -> {
                try (Socket socket = serverSocket.accept();
                     DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                     DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
                    ProtocolMessage sessionStart = ProtocolMessage.read(input);
                    ProtocolMessage.write(output, ProtocolMessage.sessionAccepted(
                        sessionStart.getSessionId(),
                        "receiver-id",
                        "Receiver",
                        ProtocolConstants.DEVICE_TYPE_WINDOWS
                    ));
                    ProtocolMessage fileMeta = ProtocolMessage.read(input);
                    input.readNBytes(Math.toIntExact(fileMeta.getSize()));
                    payloadReceived.set(true);
                    // Closing here models a receiver that finalized the file but lost its FILE_ACK path.
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });
            receiver.start();

            new TransferClient().sendFiles(
                device(serverSocket.getLocalPort()),
                "sender-id",
                "Sender",
                ProtocolConstants.DEVICE_TYPE_WINDOWS,
                List.of(item),
                statusListener(finalStatus, new AtomicBoolean())
            );
            receiver.join(5_000);

            assertFalse(receiver.isAlive());
            assertTrue(payloadReceived.get());
            assertEquals(TransferStatus.DELIVERY_UNKNOWN, finalStatus.get());
        }
    }

    @Test
    void keepsAcknowledgedFilesConfirmedWhenOnlySessionFinishAckIsLost() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "confirmed payload");
        TransferQueueItem item = queueItem(source);
        AtomicReference<TransferStatus> finalStatus = new AtomicReference<>();
        AtomicBoolean sessionFinishUnconfirmed = new AtomicBoolean(false);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread receiver = new Thread(() -> {
                try (Socket socket = serverSocket.accept();
                     DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                     DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
                    ProtocolMessage sessionStart = ProtocolMessage.read(input);
                    ProtocolMessage.write(output, ProtocolMessage.sessionAccepted(
                        sessionStart.getSessionId(),
                        "receiver-id",
                        "Receiver",
                        ProtocolConstants.DEVICE_TYPE_WINDOWS
                    ));
                    ProtocolMessage fileMeta = ProtocolMessage.read(input);
                    input.readNBytes(Math.toIntExact(fileMeta.getSize()));
                    ProtocolMessage.write(output, ProtocolMessage.fileAck(
                        sessionStart.getSessionId(),
                        fileMeta.getFileId(),
                        "receiver-id",
                        "Receiver",
                        ProtocolConstants.DEVICE_TYPE_WINDOWS,
                        true,
                        "OK",
                        null
                    ));
                    ProtocolMessage.read(input);
                    // The file was acknowledged, but the terminal SESSION_FINISH_ACK never reaches the sender.
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });
            receiver.start();

            new TransferClient().sendFiles(
                device(serverSocket.getLocalPort()),
                "sender-id",
                "Sender",
                ProtocolConstants.DEVICE_TYPE_WINDOWS,
                List.of(item),
                statusListener(finalStatus, sessionFinishUnconfirmed)
            );
            receiver.join(5_000);

            assertFalse(receiver.isAlive());
            assertEquals(TransferStatus.SENT, finalStatus.get());
            assertTrue(sessionFinishUnconfirmed.get());
        }
    }

    @Test
    void fallsBackToAnotherFreshEndpointBeforeSendingPayload() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "endpoint fallback payload");
        TransferQueueItem item = queueItem(source);
        AtomicReference<TransferStatus> finalStatus = new AtomicReference<>();
        AtomicBoolean payloadReceived = new AtomicBoolean(false);

        try (ServerSocket unavailablePortReservation = new ServerSocket(0)) {
            int unavailablePort = unavailablePortReservation.getLocalPort();
            unavailablePortReservation.close();
            try (ServerSocket serverSocket = new ServerSocket(0)) {
                Thread receiver = new Thread(() -> {
                    try (Socket socket = serverSocket.accept();
                         DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                         DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
                        ProtocolMessage sessionStart = ProtocolMessage.read(input);
                        ProtocolMessage.write(output, ProtocolMessage.sessionAccepted(
                            sessionStart.getSessionId(),
                            "receiver-id",
                            "Receiver",
                            ProtocolConstants.DEVICE_TYPE_WINDOWS
                        ));
                        ProtocolMessage fileMeta = ProtocolMessage.read(input);
                        input.readNBytes(Math.toIntExact(fileMeta.getSize()));
                        payloadReceived.set(true);
                        ProtocolMessage.write(output, ProtocolMessage.fileAck(
                            sessionStart.getSessionId(),
                            fileMeta.getFileId(),
                            "receiver-id",
                            "Receiver",
                            ProtocolConstants.DEVICE_TYPE_WINDOWS,
                            true,
                            "OK",
                            null
                        ));
                        ProtocolMessage finish = ProtocolMessage.read(input);
                        ProtocolMessage.write(output, ProtocolMessage.sessionFinishAck(
                            finish.getSessionId(),
                            "receiver-id",
                            "Receiver",
                            ProtocolConstants.DEVICE_TYPE_WINDOWS
                        ));
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    }
                });
                receiver.start();

                DeviceInfo target = new DeviceInfo(
                    "receiver-id",
                    "Receiver",
                    ProtocolConstants.DEVICE_TYPE_WINDOWS,
                    ProtocolConstants.STATUS_READY,
                    "127.0.0.1",
                    unavailablePort,
                    ProtocolConstants.CAPABILITIES,
                    System.currentTimeMillis(),
                    List.of(
                        new DeviceInfo.TransferEndpoint("127.0.0.1", unavailablePort),
                        new DeviceInfo.TransferEndpoint("127.0.0.1", serverSocket.getLocalPort())
                    )
                );
                new TransferClient().sendFiles(
                    target,
                    "sender-id",
                    "Sender",
                    ProtocolConstants.DEVICE_TYPE_WINDOWS,
                    List.of(item),
                    statusListener(finalStatus, new AtomicBoolean())
                );
                receiver.join(5_000);

                assertFalse(receiver.isAlive());
                assertTrue(payloadReceived.get());
                assertEquals(TransferStatus.SENT, finalStatus.get());
            }
        }
    }

    private TransferQueueItem queueItem(Path source) throws IOException {
        return new TransferQueueItem(source, source.getFileName().toString(), Files.size(source), Files.getLastModifiedTime(source).toMillis());
    }

    private DeviceInfo device(int port) {
        return new DeviceInfo(
            "receiver-id",
            "Receiver",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            ProtocolConstants.STATUS_READY,
            "127.0.0.1",
            port,
            ProtocolConstants.CAPABILITIES,
            System.currentTimeMillis()
        );
    }

    private TransferClient.Listener statusListener(
        AtomicReference<TransferStatus> finalStatus,
        AtomicBoolean sessionFinishUnconfirmed
    ) {
        return new TransferClient.Listener() {
            @Override
            public void onItemStatusChanged(TransferQueueItem item, TransferStatus status, String message) {
                finalStatus.set(status);
            }

            @Override
            public void onItemProgress(TransferQueueItem item, double progress) {
                // This test observes terminal status only.
            }

            @Override
            public void onItemAcknowledged(TransferQueueItem item) {
                // Status is captured before the acknowledged item leaves the UI queue.
            }

            @Override
            public void onTransferIssue(String targetDeviceName, String details) {
                // A lost FILE_ACK remains visible through DELIVERY_UNKNOWN.
            }

            @Override
            public void onReceiverRejected(String reason) {
                // The fake receiver accepts the session.
            }

            @Override
            public void onSessionFinishUnconfirmed(String targetDeviceName, String details) {
                sessionFinishUnconfirmed.set(true);
            }

            @Override
            public void onTransferFinished() {
                // sendFiles is synchronous.
            }
        };
    }
}

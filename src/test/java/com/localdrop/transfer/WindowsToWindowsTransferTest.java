package com.localdrop.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsToWindowsTransferTest {
    @TempDir
    Path tempDir;

    @Test
    void transfersFileBetweenWindowsEndpointsWithoutPairing() throws IOException {
        Path senderDir = tempDir.resolve("sender");
        Path receiverDir = tempDir.resolve("receiver");
        Files.createDirectories(senderDir);
        Files.createDirectories(receiverDir);

        Path sourceFile = senderDir.resolve("hello.txt");
        Files.writeString(sourceFile, "Hello from Windows");

        AtomicReference<RecentlyReceivedItem> receivedItem = new AtomicReference<>();
        TransferServer server = new TransferServer(
            () -> receiverDir,
            "receiver-windows-id",
            "Receiver Windows",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            new TransferServer.Listener() {
                @Override
                public void onReceiveCompleted(RecentlyReceivedItem item) {
                    receivedItem.set(item);
                }

                @Override
                public void onReadyToReceive() {
                    // No UI in this integration test.
                }

                @Override
                public void onReceivingFrom(String senderDeviceName) {
                    // No UI in this integration test.
                }
            }
        );

        try {
            server.start();
            DeviceInfo target = new DeviceInfo(
                "receiver-windows-id",
                "Receiver Windows",
                ProtocolConstants.DEVICE_TYPE_WINDOWS,
                ProtocolConstants.STATUS_READY,
                "127.0.0.1",
                server.getBoundPort(),
                ProtocolConstants.CAPABILITIES,
                System.currentTimeMillis()
            );
            TransferQueueItem item = new TransferQueueItem(
                sourceFile,
                "hello.txt",
                Files.size(sourceFile),
                Files.getLastModifiedTime(sourceFile).toMillis()
            );

            AtomicReference<String> transferIssue = new AtomicReference<>();
            new TransferClient().sendFiles(
                target,
                "sender-windows-id",
                "Sender Windows",
                ProtocolConstants.DEVICE_TYPE_WINDOWS,
                List.of(item),
                new TransferClient.Listener() {
                    @Override
                    public void onItemStatusChanged(TransferQueueItem item, TransferStatus status, String message) {
                        item.setStatus(status);
                        item.setMessage(message);
                    }

                    @Override
                    public void onItemProgress(TransferQueueItem item, double progress) {
                        item.setProgress(progress);
                    }

                    @Override
                    public void onItemAcknowledged(TransferQueueItem item) {
                        // The UI removes acknowledged items; this test checks the file on disk.
                    }

                    @Override
                    public void onTransferIssue(String targetDeviceName, String details) {
                        transferIssue.set(details);
                    }

                    @Override
                    public void onReceiverRejected(String reason) {
                        transferIssue.set(reason);
                    }

                    @Override
                    public void onTransferFinished() {
                        // sendFiles is synchronous.
                    }
                }
            );

            Path receivedFile = receiverDir.resolve("hello.txt");
            assertNull(transferIssue.get());
            assertTrue(Files.exists(receivedFile));
            assertEquals("Hello from Windows", Files.readString(receivedFile));
            assertEquals(TransferStatus.SENT, item.getStatus());
            assertEquals("hello.txt", receivedItem.get().name());
        } finally {
            server.stop();
        }
    }
}

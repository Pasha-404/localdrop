package com.localdrop.transfer;

import com.localdrop.diagnostics.DiagnosticsService;
import com.localdrop.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.Socket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferServerLifecycleTest {
    @TempDir
    Path receiveFolder;

    @Test
    void stopClosesClientsThatAreBlockedBeforeTheSessionHeader() throws Exception {
        TransferServer server = new TransferServer(
            () -> receiveFolder,
            "receiver-id",
            "Receiver",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            new NoOpListener(),
            new DiagnosticsService("receiver-id", "Receiver", ProtocolConstants.DEVICE_TYPE_WINDOWS)
        );

        server.start();
        try (Socket client = new Socket("127.0.0.1", server.getBoundPort())) {
            awaitActiveConnection(server);

            server.stop();

            assertFalse(server.isRunning());
            assertEquals(0, server.activeConnectionCount());
            assertTrue(client.isClosed() || client.getInputStream().read() == -1);
        } finally {
            server.stop();
        }
    }

    private void awaitActiveConnection(TransferServer server) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (server.activeConnectionCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.activeConnectionCount() > 0, "TCP client was not registered as active");
    }

    private static final class NoOpListener implements TransferServer.Listener {
        @Override
        public void onReceiveCompleted(RecentlyReceivedItem item) {
        }

        @Override
        public void onReadyToReceive() {
        }

        @Override
        public void onReceivingFrom(String senderDeviceName) {
        }
    }
}

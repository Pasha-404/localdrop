package com.localdrop.discovery;

import com.localdrop.diagnostics.DiagnosticsService;
import com.localdrop.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryServiceRecoveryTest {
    @Test
    void closedDiscoverySocketIsReboundWithoutRestartingTheService() throws Exception {
        int discoveryPort = reserveUdpPort();
        DiagnosticsService diagnostics = new DiagnosticsService(
            "windows-device",
            "Windows device",
            ProtocolConstants.DEVICE_TYPE_WINDOWS
        );
        DiscoveryService service = new DiscoveryService(
            "windows-device",
            "Windows device",
            ProtocolConstants.DEVICE_TYPE_WINDOWS,
            ProtocolConstants.DEFAULT_TRANSFER_PORT,
            discoveryPort,
            () -> ProtocolConstants.STATUS_READY,
            ignored -> { },
            diagnostics
        );

        service.start();
        try {
            service.closeSocketForTest();
            awaitRecovery(service, diagnostics);

            assertTrue(service.isRunning());
            assertTrue(service.isSocketOpen());
            assertTrue(diagnostics.snapshot().lastDiscoveryRecoveredAt() > 0);
        } finally {
            service.stop();
        }
    }

    private int reserveUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitRecovery(DiscoveryService service, DiagnosticsService diagnostics) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while ((diagnostics.snapshot().lastDiscoveryRecoveredAt() == 0 || !service.isSocketOpen())
            && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(diagnostics.snapshot().lastDiscoveryRecoveredAt() > 0, "Discovery socket was not recovered");
    }
}

package com.localdrop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceServiceTest {
    @Test
    void verifiedSecondInstanceActivatesTheFirst() throws Exception {
        int port = reserveFreePort();
        try (SingleInstanceService first = SingleInstanceService.acquireOrNotifyExisting(port)) {
            CountDownLatch activated = new CountDownLatch(1);
            first.setActivationHandler(activated::countDown);

            assertNull(SingleInstanceService.acquireOrNotifyExisting(port));
            assertTrue(activated.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void unrelatedLoopbackListenerDoesNotMasqueradeAsLocalDrop() throws Exception {
        try (ServerSocket unrelatedListener = new ServerSocket(reserveFreePort())) {
            assertThrows(IOException.class, () -> SingleInstanceService.acquireOrNotifyExisting(unrelatedListener.getLocalPort()));
        }
    }

    @Test
    void userSessionDerivedPortIsStableAndSessionScoped() {
        int first = SingleInstanceService.portForUserSession("alice", "C:/Users/Alice", "Console");
        assertEquals(first, SingleInstanceService.portForUserSession("alice", "C:/Users/Alice", "Console"));
        assertNotEquals(first, SingleInstanceService.portForUserSession("alice", "C:/Users/Alice", "RDP-Tcp#1"));
    }

    private int reserveFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

package com.localdrop;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates one LocalDrop process per Windows user session through loopback only. */
public final class SingleInstanceService implements AutoCloseable {
    private static final int LOOPBACK_PORT = 47_225;
    private static final int CONNECT_TIMEOUT_MILLIS = 750;

    private final ServerSocket serverSocket;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Runnable activationHandler;

    private SingleInstanceService(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        Thread.ofPlatform()
            .daemon(true)
            .name("localdrop-single-instance")
            .start(this::listenForActivations);
    }

    /** Returns the guard for the first process. A later process activates the first one and gets {@code null}. */
    public static SingleInstanceService acquireOrNotifyExisting() throws IOException {
        try {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), LOOPBACK_PORT));
            return new SingleInstanceService(server);
        } catch (BindException bindException) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), LOOPBACK_PORT), CONNECT_TIMEOUT_MILLIS);
                socket.getOutputStream().write(1);
                socket.getOutputStream().flush();
                return null;
            } catch (IOException notificationError) {
                throw new IOException("LocalDrop already owns the single-instance port, but could not be activated.", notificationError);
            }
        }
    }

    public void setActivationHandler(Runnable activationHandler) {
        this.activationHandler = activationHandler;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing the loopback listener is best effort during shutdown.
        }
    }

    private void listenForActivations() {
        while (!closed.get()) {
            try (Socket ignored = serverSocket.accept()) {
                Runnable handler = activationHandler;
                if (handler != null) {
                    handler.run();
                }
            } catch (IOException ignored) {
                if (!closed.get()) {
                    // The launcher continues to own the guard after a transient loopback error.
                }
            } catch (RuntimeException ignored) {
                // UI activation must not terminate the single-instance listener.
            }
        }
    }
}

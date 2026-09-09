package com.localdrop;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates one LocalDrop process per Windows user session through loopback only. */
public final class SingleInstanceService implements AutoCloseable {
    private static final int PORT_RANGE_START = 49_152;
    private static final int PORT_RANGE_END = 65_535;
    private static final int CONNECT_TIMEOUT_MILLIS = 750;
    private static final int HANDSHAKE_MAGIC = 0x4C_44_52_50;
    private static final int HANDSHAKE_ACK = 0x4C_44_4F_4B;

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
        return acquireOrNotifyExisting(resolveCurrentUserSessionPort());
    }

    static SingleInstanceService acquireOrNotifyExisting(int loopbackPort) throws IOException {
        try {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), loopbackPort));
            return new SingleInstanceService(server);
        } catch (BindException bindException) {
            notifyExisting(loopbackPort);
            return null;
        }
    }

    static int portForUserSession(String userName, String userHome, String sessionName) {
        int range = PORT_RANGE_END - PORT_RANGE_START + 1;
        return PORT_RANGE_START + Math.floorMod(Objects.hash(userName, userHome, sessionName), range);
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
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                if (input.readInt() != HANDSHAKE_MAGIC) {
                    continue;
                }
                output.writeInt(HANDSHAKE_ACK);
                output.flush();
                Runnable handler = activationHandler;
                if (handler != null) {
                    handler.run();
                }
            } catch (SocketTimeoutException ignored) {
                // A non-LocalDrop local connection cannot keep the activation listener blocked indefinitely.
            } catch (IOException ignored) {
                if (!closed.get()) {
                    // The launcher continues to own the guard after a transient loopback error.
                }
            } catch (RuntimeException ignored) {
                // UI activation must not terminate the single-instance listener.
            }
        }
    }

    private static int resolveCurrentUserSessionPort() {
        return portForUserSession(
            System.getProperty("user.name", "unknown-user"),
            System.getProperty("user.home", "unknown-home"),
            System.getenv().getOrDefault("SESSIONNAME", "default-session")
        );
    }

    private static void notifyExisting(int loopbackPort) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), loopbackPort), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(HANDSHAKE_MAGIC);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            if (input.readInt() != HANDSHAKE_ACK) {
                throw new IOException("The loopback port is occupied by a process that is not LocalDrop.");
            }
        } catch (IOException notificationError) {
            throw new IOException("The LocalDrop single-instance port is unavailable and the existing app could not be verified.", notificationError);
        }
    }
}

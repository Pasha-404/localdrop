package com.localdrop.transfer;

import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.transfer.ProtocolMessage;
import com.localdrop.util.FileUtils;
import com.localdrop.util.LogService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class TransferServer {
    public static final int DEFAULT_PORT = ProtocolConstants.DEFAULT_TRANSFER_PORT;

    public interface Listener {
        void onReceiveCompleted(RecentlyReceivedItem item);

        void onReadyToReceive();

        void onReceivingFrom(String senderDeviceName);
    }

    private final Logger logger = LogService.getLogger(TransferServer.class);
    private final Supplier<Path> receiveFolderSupplier;
    private final Listener listener;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "localdrop-transfer-accept"));
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "localdrop-transfer-client"));

    private volatile boolean running;
    private volatile int boundPort;
    private ServerSocket serverSocket;

    public TransferServer(Supplier<Path> receiveFolderSupplier, Listener listener) {
        this.receiveFolderSupplier = receiveFolderSupplier;
        this.listener = listener;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }

        IOException lastError = null;
        for (int port = DEFAULT_PORT; port < DEFAULT_PORT + 20; port++) {
            try {
                serverSocket = new ServerSocket(port);
                boundPort = port;
                break;
            } catch (IOException exception) {
                lastError = exception;
            }
        }

        if (serverSocket == null) {
            throw Objects.requireNonNullElseGet(lastError, () -> new IOException("Unable to bind TCP server"));
        }

        running = true;
        acceptExecutor.submit(this::acceptLoop);
        logger.info("TCP transfer server started on port " + boundPort);
    }

    public int getBoundPort() {
        if (!running) {
            throw new IllegalStateException("Transfer server has not been started.");
        }
        return boundPort;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Ignore shutdown errors.
        }
        acceptExecutor.shutdownNow();
        clientExecutor.shutdownNow();
        logger.info("TCP transfer server stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clientExecutor.submit(() -> handleClient(socket));
            } catch (IOException exception) {
                if (running) {
                    logger.warning("Failed to accept incoming transfer: " + exception.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        String remoteAddress = socket.getInetAddress().getHostAddress();
        try (socket;
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setSoTimeout(30_000);
            ProtocolMessage sessionStart = ProtocolMessage.read(input);
            if (!ProtocolConstants.TYPE_SESSION_START.equals(sessionStart.getType())) {
                throw new IOException("Expected SESSION_START but received " + sessionStart.getType());
            }

            Path receiveFolder = receiveFolderSupplier.get();
            Files.createDirectories(receiveFolder);
            if (!Files.isWritable(receiveFolder)) {
                ProtocolMessage.write(output, ProtocolMessage.sessionRejected(sessionStart.getSessionId(), "Receive folder is not writable"));
                return;
            }

            TransferSession session = new TransferSession(
                sessionStart.getSessionId(),
                sessionStart.getSenderDeviceId(),
                sessionStart.getSenderDeviceName(),
                sessionStart.getFileCount() == null ? 0 : sessionStart.getFileCount()
            );
            logger.info("Accepted transfer session " + session.sessionId() + " from " + session.senderDeviceName() + " (" + remoteAddress + ")");
            listener.onReceivingFrom(session.senderDeviceName());
            ProtocolMessage.write(output, ProtocolMessage.sessionAccepted(session.sessionId()));

            while (running) {
                ProtocolMessage message = ProtocolMessage.read(input);
                if (ProtocolConstants.TYPE_SESSION_END.equals(message.getType())) {
                    ProtocolMessage.write(output, ProtocolMessage.sessionClosed(session.sessionId()));
                    listener.onReadyToReceive();
                    logger.info("Closed transfer session " + session.sessionId());
                    return;
                }
                if (!ProtocolConstants.TYPE_FILE_META.equals(message.getType())) {
                    throw new IOException("Unexpected protocol message " + message.getType());
                }
                receiveSingleFile(session, message, input, output, receiveFolder);
            }
        } catch (EOFException exception) {
            listener.onReadyToReceive();
            logger.warning("Transfer connection closed unexpectedly from " + remoteAddress);
        } catch (IOException exception) {
            listener.onReadyToReceive();
            logger.warning("Failed to receive file from " + remoteAddress + ": " + exception.getMessage());
        }
    }

    private void receiveSingleFile(
        TransferSession session,
        ProtocolMessage fileMeta,
        DataInputStream input,
        DataOutputStream output,
        Path receiveFolder
    ) throws IOException {
        Path relativePath = FileUtils.sanitizeRelativePath(fileMeta.getRelativePath(), fileMeta.getFileName());
        Path targetPath = receiveFolder.resolve(relativePath).normalize();
        if (!targetPath.startsWith(receiveFolder.normalize())) {
            ProtocolMessage.write(output, ProtocolMessage.fileAck(session.sessionId(), fileMeta.getFileId(), false, "Invalid relative path"));
            return;
        }

        Path targetParent = targetPath.getParent() == null ? receiveFolder : targetPath.getParent();
        Files.createDirectories(targetParent);
        Path finalPath = FileNameResolver.resolve(targetPath);
        Path tempPath = Path.of(finalPath.toString() + ".localdrop-part");
        Path tempParent = tempPath.getParent() == null ? receiveFolder : tempPath.getParent();
        Files.createDirectories(tempParent);

        long remaining = fileMeta.getSize() == null ? 0 : fileMeta.getSize();
        try (var fileOutputStream = new BufferedOutputStream(Files.newOutputStream(tempPath))) {
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                int chunkSize = (int) Math.min(buffer.length, remaining);
                int read = input.read(buffer, 0, chunkSize);
                if (read < 0) {
                    throw new EOFException("Stream ended during file payload");
                }
                fileOutputStream.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException exception) {
            Files.deleteIfExists(tempPath);
            ProtocolMessage.write(output, ProtocolMessage.fileAck(session.sessionId(), fileMeta.getFileId(), false, "Cannot write file"));
            throw exception;
        }

        FileUtils.moveAtomicallyOrReplace(tempPath, finalPath);
        if (fileMeta.getLastModified() != null) {
            FileUtils.applyLastModified(finalPath, fileMeta.getLastModified());
        }
        ProtocolMessage.write(output, ProtocolMessage.fileAck(session.sessionId(), fileMeta.getFileId(), true, finalPath.getFileName().toString()));

        logger.info("Received file " + relativePath + " into " + finalPath);
        listener.onReceiveCompleted(new RecentlyReceivedItem(relativePath.toString(), Files.size(finalPath), LocalTime.now()));
    }
}

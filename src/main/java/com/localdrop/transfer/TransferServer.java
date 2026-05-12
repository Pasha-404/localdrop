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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final String localDeviceId;
    private final String localDeviceName;
    private final String localDeviceType;
    private final Listener listener;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "localdrop-transfer-accept"));
    private final ExecutorService clientExecutor = Executors.newFixedThreadPool(4, r -> new Thread(r, "localdrop-transfer-client"));
    private final AtomicBoolean activeIncomingTransfer = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile int boundPort;
    private ServerSocket serverSocket;

    public TransferServer(
        Supplier<Path> receiveFolderSupplier,
        String localDeviceId,
        String localDeviceName,
        String localDeviceType,
        Listener listener
    ) {
        this.receiveFolderSupplier = receiveFolderSupplier;
        this.localDeviceId = localDeviceId;
        this.localDeviceName = localDeviceName;
        this.localDeviceType = localDeviceType;
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
        logger.info("TCP transfer server v2-open started on port " + boundPort);
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
            socket.setSoTimeout(ProtocolConstants.CONTROL_READ_TIMEOUT_MS);
            ProtocolMessage sessionStart = ProtocolMessage.read(input);
            if (!Integer.valueOf(ProtocolConstants.PROTOCOL_VERSION).equals(sessionStart.getProtocolVersion())) {
                writeSessionRejection(output, sessionStart.getSessionId(), ProtocolConstants.ERROR_VERSION_UNSUPPORTED);
                return;
            }
            if (!ProtocolConstants.TYPE_SESSION_START.equals(sessionStart.getType())) {
                writeSessionRejection(output, sessionStart.getSessionId(), ProtocolConstants.ERROR_INVALID_MESSAGE);
                return;
            }
            if (!activeIncomingTransfer.compareAndSet(false, true)) {
                writeSessionRejection(output, sessionStart.getSessionId(), ProtocolConstants.ERROR_BUSY);
                return;
            }

            try {
                TransferSession session;
                Path receiveFolder;
                try {
                    session = validateSessionStart(sessionStart);
                    receiveFolder = prepareReceiveFolder();
                } catch (IOException exception) {
                    writeSessionRejection(output, sessionStart.getSessionId(), errorCodeFor(exception));
                    throw exception;
                }

                ProtocolMessage.write(output, ProtocolMessage.sessionAccepted(
                    session.sessionId(),
                    localDeviceId,
                    localDeviceName,
                    localDeviceType
                ));

                logger.info("Accepted transfer session " + session.sessionId() + " from " + session.senderDeviceName() + " (" + remoteAddress + ")");
                listener.onReceivingFrom(session.senderDeviceName());
                receiveTransferLoop(session, input, output, socket, receiveFolder);
            } finally {
                activeIncomingTransfer.set(false);
                listener.onReadyToReceive();
            }
        } catch (EOFException exception) {
            logger.warning("Transfer connection closed unexpectedly from " + remoteAddress);
        } catch (IOException exception) {
            logger.warning("Failed to receive file from " + remoteAddress + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            logger.warning("Rejected malformed TCP client from " + remoteAddress + ": " + exception.getMessage());
        }
    }

    private TransferSession validateSessionStart(ProtocolMessage sessionStart) throws IOException {
        if (isBlank(sessionStart.getSessionId()) || isBlank(sessionStart.getDeviceId()) || isBlank(sessionStart.getDeviceName())) {
            throw new IOException("Invalid session start message.");
        }
        int totalFiles = sessionStart.getTotalFiles() == null ? 0 : sessionStart.getTotalFiles();
        long totalSize = sessionStart.getTotalSize() == null ? -1 : sessionStart.getTotalSize();
        if (totalFiles < 1 || totalFiles > ProtocolConstants.MAX_FILES_PER_SESSION
            || totalSize < 0 || totalSize > ProtocolConstants.MAX_TOTAL_SESSION_BYTES) {
            throw new IOException("Transfer session exceeds configured limits.");
        }
        return new TransferSession(
            sessionStart.getSessionId(),
            sessionStart.getDeviceId(),
            sessionStart.getDeviceName(),
            totalFiles,
            totalSize
        );
    }

    private Path prepareReceiveFolder() throws IOException {
        Path receiveFolder = receiveFolderSupplier.get();
        if (receiveFolder == null) {
            throw new IOException(ProtocolConstants.ERROR_RECEIVE_FOLDER_NOT_CONFIGURED);
        }
        Files.createDirectories(receiveFolder);
        if (!Files.isWritable(receiveFolder)) {
            throw new IOException(ProtocolConstants.ERROR_RECEIVE_FOLDER_NOT_CONFIGURED);
        }
        return receiveFolder.toAbsolutePath().normalize();
    }

    private void receiveTransferLoop(
        TransferSession session,
        DataInputStream input,
        DataOutputStream output,
        Socket socket,
        Path receiveFolder
    ) throws IOException {
        int receivedFiles = 0;
        long receivedBytes = 0;

        while (running) {
            socket.setSoTimeout(ProtocolConstants.CONTROL_READ_TIMEOUT_MS);
            ProtocolMessage message = ProtocolMessage.read(input);
            if (ProtocolConstants.TYPE_SESSION_FINISH.equals(message.getType())) {
                validateFinishMessage(session, message);
                ProtocolMessage.write(output, ProtocolMessage.sessionFinishAck(
                    session.sessionId(),
                    localDeviceId,
                    localDeviceName,
                    localDeviceType
                ));
                logger.info("Finished transfer session " + session.sessionId());
                return;
            }
            if (!ProtocolConstants.TYPE_FILE_META.equals(message.getType())) {
                throw new IOException("Unexpected protocol message " + message.getType());
            }

            try {
                validateFileMeta(session, message, receivedFiles, receivedBytes);
            } catch (IOException exception) {
                writeFileAck(output, session.sessionId(), message.getFileId(), false, errorCodeFor(exception));
                throw exception;
            }
            socket.setSoTimeout(ProtocolConstants.PAYLOAD_READ_TIMEOUT_MS);
            receiveSingleFile(session, message, input, output, receiveFolder);
            receivedFiles++;
            receivedBytes += message.getSize();
        }
    }

    private void validateFinishMessage(TransferSession session, ProtocolMessage message) throws IOException {
        if (!session.sessionId().equals(message.getSessionId())
            || !session.senderDeviceId().equals(message.getDeviceId())) {
            throw new IOException("SESSION_FINISH has invalid identifiers.");
        }
    }

    private void validateFileMeta(
        TransferSession session,
        ProtocolMessage fileMeta,
        int receivedFiles,
        long receivedBytes
    ) throws IOException {
        if (!session.sessionId().equals(fileMeta.getSessionId()) || isBlank(fileMeta.getFileId())) {
            throw new IOException("Invalid FILE_META identifiers.");
        }
        if (!session.senderDeviceId().equals(fileMeta.getDeviceId())) {
            throw new IOException("FILE_META came from a different device.");
        }
        long size = fileMeta.getSize() == null ? -1 : fileMeta.getSize();
        if (size < 0 || size > ProtocolConstants.MAX_FILE_SIZE_BYTES) {
            throw new IOException("File size exceeds configured limits.");
        }
        if (receivedFiles + 1 > session.totalFiles()
            || receivedBytes + size > ProtocolConstants.MAX_TOTAL_SESSION_BYTES
            || receivedBytes + size > session.totalSize()) {
            throw new IOException("Transfer session exceeds configured limits.");
        }
    }

    private void receiveSingleFile(
        TransferSession session,
        ProtocolMessage fileMeta,
        DataInputStream input,
        DataOutputStream output,
        Path receiveFolder
    ) throws IOException {
        Path relativePath;
        try {
            relativePath = FileUtils.sanitizeReceivedRelativePath(fileMeta.getRelativePath(), fileMeta.getFileName());
        } catch (IOException exception) {
            drainPayload(input, fileMeta.getSize());
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_INVALID_MESSAGE);
            throw exception;
        }
        Path targetPath = receiveFolder.resolve(relativePath).normalize();
        if (!targetPath.startsWith(receiveFolder)) {
            drainPayload(input, fileMeta.getSize());
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_INVALID_MESSAGE);
            throw new IOException("Resolved target path leaves receive folder.");
        }

        Path targetParent = targetPath.getParent() == null ? receiveFolder : targetPath.getParent();
        Files.createDirectories(targetParent);
        Path finalPath = FileNameResolver.resolve(targetPath).normalize();
        if (!finalPath.startsWith(receiveFolder)) {
            drainPayload(input, fileMeta.getSize());
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_INVALID_MESSAGE);
            throw new IOException("Final target path leaves receive folder.");
        }

        Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + ".localdrop-part");
        long remaining = fileMeta.getSize();
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
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_INVALID_MESSAGE);
            throw exception;
        }

        FileUtils.moveAtomicallyOrReplace(tempPath, finalPath);
        if (fileMeta.getLastModified() != null) {
            FileUtils.applyLastModified(finalPath, fileMeta.getLastModified());
        }
        writeFileAck(output, session.sessionId(), fileMeta.getFileId(), true, null);

        logger.info("Received file " + relativePath + " from " + session.senderDeviceName());
        listener.onReceiveCompleted(new RecentlyReceivedItem(relativePath.toString(), Files.size(finalPath), LocalTime.now()));
    }

    private void drainPayload(DataInputStream input, Long size) throws IOException {
        long remaining = Math.max(0, size == null ? 0 : size);
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int chunkSize = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, chunkSize);
            if (read < 0) {
                throw new EOFException("Stream ended while draining rejected file payload");
            }
            remaining -= read;
        }
    }

    private void writeFileAck(
        DataOutputStream output,
        String sessionId,
        String fileId,
        boolean ok,
        String errorCode
    ) throws IOException {
        ProtocolMessage.write(output, ProtocolMessage.fileAck(
            sessionId,
            fileId,
            localDeviceId,
            localDeviceName,
            localDeviceType,
            ok,
            errorCode,
            errorCode
        ));
    }

    private void writeSessionRejection(DataOutputStream output, String sessionId, String errorCode) throws IOException {
        ProtocolMessage.write(output, ProtocolMessage.sessionRejected(sessionId, errorCode, errorCode));
    }

    private String errorCodeFor(IOException exception) {
        String message = exception.getMessage();
        if (ProtocolConstants.ERROR_RECEIVE_FOLDER_NOT_CONFIGURED.equals(message)) {
            return ProtocolConstants.ERROR_RECEIVE_FOLDER_NOT_CONFIGURED;
        }
        if (message != null && message.toLowerCase().contains("limit")) {
            return ProtocolConstants.ERROR_LIMIT_EXCEEDED;
        }
        return ProtocolConstants.ERROR_INVALID_MESSAGE;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

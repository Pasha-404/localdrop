package com.localdrop.transfer;

import com.localdrop.diagnostics.DiagnosticDirection;
import com.localdrop.diagnostics.DiagnosticEventType;
import com.localdrop.diagnostics.DiagnosticsService;
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
import java.net.SocketTimeoutException;
import java.nio.file.FileStore;
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
    private final DiagnosticsService diagnosticsService;
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
        Listener listener,
        DiagnosticsService diagnosticsService
    ) {
        this.receiveFolderSupplier = receiveFolderSupplier;
        this.localDeviceId = localDeviceId;
        this.localDeviceName = localDeviceName;
        this.localDeviceType = localDeviceType;
        this.listener = listener;
        this.diagnosticsService = diagnosticsService;
    }

    public void start() throws IOException {
        if (running) {
            return;
        }

        IOException lastError = null;
        for (int port = DEFAULT_PORT; port <= ProtocolConstants.WINDOWS_TRANSFER_PORT_MAX; port++) {
            try {
                serverSocket = new ServerSocket(port);
                boundPort = port;
                break;
            } catch (IOException exception) {
                lastError = exception;
            }
        }

        if (serverSocket == null) {
            diagnosticsService.setTransferServerStatus("ERROR", ProtocolConstants.ERROR_TRANSFER_PORT_UNAVAILABLE);
            diagnosticsService.recordTransferEvent(
                DiagnosticEventType.TRANSFER_SERVER_ERROR,
                DiagnosticDirection.INTERNAL,
                null,
                localDeviceId,
                localDeviceName,
                localDeviceType,
                ProtocolConstants.STATUS_TRANSFER_PORT_UNAVAILABLE,
                ProtocolConstants.ERROR_TRANSFER_PORT_UNAVAILABLE,
                "Unable to bind TCP receive server."
            );
            throw Objects.requireNonNullElseGet(lastError, () -> new IOException("Unable to bind TCP server"));
        }

        running = true;
        cleanupPartialFiles();
        diagnosticsService.setTransferPort(boundPort);
        diagnosticsService.setTransferServerStatus("RUNNING", null);
        diagnosticsService.recordTransferEvent(
            DiagnosticEventType.TRANSFER_SERVER_STARTED,
            DiagnosticDirection.INTERNAL,
            null,
            localDeviceId,
            localDeviceName,
            localDeviceType,
            ProtocolConstants.STATUS_READY,
            null,
            "TCP transfer server started."
        );
        acceptExecutor.submit(this::acceptLoop);
        logger.info("TCP transfer server v2-open started on port " + boundPort);
    }

    public int getBoundPort() {
        if (!running) {
            throw new IllegalStateException("Transfer server has not been started.");
        }
        return boundPort;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isBusy() {
        return activeIncomingTransfer.get();
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
        diagnosticsService.setTransferServerStatus("STOPPED", null);
        diagnosticsService.recordTransferEvent(
            DiagnosticEventType.TRANSFER_SERVER_STOPPED,
            DiagnosticDirection.INTERNAL,
            null,
            localDeviceId,
            localDeviceName,
            localDeviceType,
            null,
            null,
            "TCP transfer server stopped."
        );
        logger.info("TCP transfer server stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clientExecutor.submit(() -> handleClient(socket));
            } catch (IOException exception) {
                if (running) {
                    diagnosticsService.setTransferServerStatus("ERROR", ProtocolConstants.ERROR_CONNECTION_LOST);
                    diagnosticsService.recordTransferEvent(
                        DiagnosticEventType.TRANSFER_SERVER_ERROR,
                        DiagnosticDirection.INTERNAL,
                        null,
                        localDeviceId,
                        localDeviceName,
                        localDeviceType,
                        null,
                        ProtocolConstants.ERROR_CONNECTION_LOST,
                        exception.getMessage()
                    );
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
            socket.setSoTimeout(ProtocolConstants.HEADER_READ_TIMEOUT_MS);
            ProtocolMessage sessionStart = ProtocolMessage.read(input);
            if (!Integer.valueOf(ProtocolConstants.PROTOCOL_VERSION).equals(sessionStart.getProtocolVersion())) {
                writeSessionRejection(output, sessionStart.getSessionId(), ProtocolConstants.ERROR_PROTOCOL_VERSION_MISMATCH, "Protocol version mismatch.");
                return;
            }
            if (!ProtocolConstants.TYPE_SESSION_START.equals(sessionStart.getType())) {
                writeSessionRejection(output, sessionStart.getSessionId(), ProtocolConstants.ERROR_MALFORMED_MESSAGE, "Expected SESSION_START.");
                return;
            }
            if (!activeIncomingTransfer.compareAndSet(false, true)) {
                writeSessionRejection(output, sessionStart.getSessionId(), ProtocolConstants.ERROR_SESSION_BUSY, "Receiver is busy.");
                return;
            }

            try {
                TransferSession session;
                Path receiveFolder;
                try {
                    session = validateSessionStart(sessionStart);
                    receiveFolder = prepareReceiveFolder();
                    ensureUsableSpace(receiveFolder, session.totalSize());
                } catch (TransferException exception) {
                    writeSessionRejection(output, sessionStart.getSessionId(), exception.getErrorCode(), exception.getMessage());
                    throw exception;
                }

                ProtocolMessage.write(output, ProtocolMessage.sessionAccepted(
                    session.sessionId(),
                    localDeviceId,
                    localDeviceName,
                    localDeviceType
                ));

                diagnosticsService.recordTransferEvent(
                    DiagnosticEventType.TRANSFER_CLIENT_CONNECTED,
                    DiagnosticDirection.IN,
                    remoteAddress,
                    session.senderDeviceId(),
                    session.senderDeviceName(),
                    null,
                    ProtocolConstants.STATUS_READY,
                    null,
                    "Incoming transfer session accepted."
                );
                logger.info("Accepted transfer session " + session.sessionId() + " from " + session.senderDeviceName() + " (" + remoteAddress + ")");
                listener.onReceivingFrom(session.senderDeviceName());
                receiveTransferLoop(session, input, output, socket, receiveFolder, remoteAddress);
            } finally {
                activeIncomingTransfer.set(false);
                listener.onReadyToReceive();
            }
        } catch (SocketTimeoutException exception) {
            diagnosticsService.recordTransferEvent(
                DiagnosticEventType.TRANSFER_SERVER_ERROR,
                DiagnosticDirection.INTERNAL,
                remoteAddress,
                null,
                null,
                null,
                null,
                ProtocolConstants.ERROR_HEADER_TIMEOUT,
                exception.getMessage()
            );
            logger.warning("Transfer header timed out from " + remoteAddress + ": " + exception.getMessage());
        } catch (EOFException exception) {
            diagnosticsService.recordTransferEvent(
                DiagnosticEventType.TRANSFER_SERVER_ERROR,
                DiagnosticDirection.INTERNAL,
                remoteAddress,
                null,
                null,
                null,
                null,
                ProtocolConstants.ERROR_CONNECTION_LOST,
                exception.getMessage()
            );
            logger.warning("Transfer connection closed unexpectedly from " + remoteAddress);
        } catch (TransferException exception) {
            diagnosticsService.recordTransferEvent(
                DiagnosticEventType.TRANSFER_FILE_FAILED,
                DiagnosticDirection.INTERNAL,
                remoteAddress,
                null,
                null,
                null,
                null,
                exception.getErrorCode(),
                exception.getMessage()
            );
            logger.warning("Failed to receive file from " + remoteAddress + ": " + exception.getMessage());
        } catch (IOException exception) {
            diagnosticsService.recordTransferEvent(
                DiagnosticEventType.TRANSFER_SERVER_ERROR,
                DiagnosticDirection.INTERNAL,
                remoteAddress,
                null,
                null,
                null,
                null,
                ProtocolConstants.ERROR_UNKNOWN,
                exception.getMessage()
            );
            logger.warning("Failed to receive file from " + remoteAddress + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            diagnosticsService.recordTransferEvent(
                DiagnosticEventType.TRANSFER_SERVER_ERROR,
                DiagnosticDirection.INTERNAL,
                remoteAddress,
                null,
                null,
                null,
                null,
                ProtocolConstants.ERROR_MALFORMED_MESSAGE,
                exception.getMessage()
            );
            logger.warning("Rejected malformed TCP client from " + remoteAddress + ": " + exception.getMessage());
        }
    }

    private TransferSession validateSessionStart(ProtocolMessage sessionStart) throws TransferException {
        if (isBlank(sessionStart.getSessionId())) {
            throw new TransferException(ProtocolConstants.ERROR_INVALID_SESSION, "Session id is missing.");
        }
        if (isBlank(sessionStart.getDeviceId()) || isBlank(sessionStart.getDeviceName())) {
            throw new TransferException(ProtocolConstants.ERROR_MALFORMED_MESSAGE, "Sender information is incomplete.");
        }
        int totalFiles = sessionStart.getTotalFiles() == null ? 0 : sessionStart.getTotalFiles();
        long totalSize = sessionStart.getTotalSize() == null ? -1 : sessionStart.getTotalSize();
        if (totalFiles < 1) {
            throw new TransferException(ProtocolConstants.ERROR_MALFORMED_MESSAGE, "Total file count is invalid.");
        }
        if (totalFiles > ProtocolConstants.MAX_FILES_PER_SESSION) {
            throw new TransferException(ProtocolConstants.ERROR_TOO_MANY_FILES, "Transfer session exceeds the file count limit.");
        }
        if (totalSize < 0) {
            throw new TransferException(ProtocolConstants.ERROR_MALFORMED_MESSAGE, "Total size is invalid.");
        }
        if (totalSize > ProtocolConstants.MAX_TOTAL_SESSION_BYTES) {
            throw new TransferException(ProtocolConstants.ERROR_TOTAL_TRANSFER_TOO_LARGE, "Transfer session exceeds the total size limit.");
        }
        return new TransferSession(
            sessionStart.getSessionId(),
            sessionStart.getDeviceId(),
            sessionStart.getDeviceName(),
            totalFiles,
            totalSize
        );
    }

    private Path prepareReceiveFolder() throws TransferException {
        Path receiveFolder = receiveFolderSupplier.get();
        if (receiveFolder == null) {
            throw new TransferException(ProtocolConstants.ERROR_RECEIVE_FOLDER_NOT_SELECTED, "Receive folder is not selected.");
        }

        try {
            Files.createDirectories(receiveFolder);
        } catch (IOException exception) {
            throw new TransferException(ProtocolConstants.ERROR_RECEIVE_FOLDER_NOT_WRITABLE, "Receive folder cannot be created.");
        }
        if (!Files.isWritable(receiveFolder)) {
            throw new TransferException(ProtocolConstants.ERROR_RECEIVE_FOLDER_NOT_WRITABLE, "Receive folder is not writable.");
        }
        return receiveFolder.toAbsolutePath().normalize();
    }

    private void ensureUsableSpace(Path receiveFolder, long expectedBytes) throws TransferException {
        try {
            FileStore fileStore = Files.getFileStore(receiveFolder);
            if (fileStore.getUsableSpace() < expectedBytes) {
                throw new TransferException(ProtocolConstants.ERROR_DISK_SPACE_LOW, "Not enough free disk space.");
            }
        } catch (IOException exception) {
            if (exception instanceof TransferException transferException) {
                throw transferException;
            }
            // FileStore probing is best-effort only.
        }
    }

    private void receiveTransferLoop(
        TransferSession session,
        DataInputStream input,
        DataOutputStream output,
        Socket socket,
        Path receiveFolder,
        String remoteAddress
    ) throws IOException {
        int receivedFiles = 0;
        long receivedBytes = 0;

        while (running) {
            socket.setSoTimeout(ProtocolConstants.HEADER_READ_TIMEOUT_MS);
            ProtocolMessage message = ProtocolMessage.read(input);
            if (ProtocolConstants.TYPE_SESSION_FINISH.equals(message.getType())) {
                validateFinishMessage(session, message, receivedFiles, receivedBytes);
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
                throw new TransferException(ProtocolConstants.ERROR_MALFORMED_MESSAGE, "Unexpected protocol message " + message.getType());
            }

            validateFileMeta(session, message, receivedFiles, receivedBytes);
            socket.setSoTimeout(ProtocolConstants.FILE_TRANSFER_IDLE_TIMEOUT_MS);
            receiveSingleFile(session, message, input, output, receiveFolder, remoteAddress);
            receivedFiles++;
            receivedBytes += message.getSize();
        }
    }

    private void validateFinishMessage(
        TransferSession session,
        ProtocolMessage message,
        int receivedFiles,
        long receivedBytes
    ) throws TransferException {
        if (!session.sessionId().equals(message.getSessionId())
            || !session.senderDeviceId().equals(message.getDeviceId())) {
            throw new TransferException(ProtocolConstants.ERROR_INVALID_SESSION, "SESSION_FINISH has invalid identifiers.");
        }
        if (receivedFiles != session.totalFiles() || receivedBytes != session.totalSize()) {
            throw new TransferException(ProtocolConstants.ERROR_INVALID_SESSION, "SESSION_FINISH arrived before all declared files were received.");
        }
    }

    private void validateFileMeta(
        TransferSession session,
        ProtocolMessage fileMeta,
        int receivedFiles,
        long receivedBytes
    ) throws TransferException {
        if (!session.sessionId().equals(fileMeta.getSessionId())) {
            throw new TransferException(ProtocolConstants.ERROR_INVALID_SESSION, "FILE_META session id does not match the active session.");
        }
        if (isBlank(fileMeta.getFileId())) {
            throw new TransferException(ProtocolConstants.ERROR_INVALID_FILE_ID, "FILE_META file id is missing.");
        }
        if (!session.senderDeviceId().equals(fileMeta.getDeviceId())) {
            throw new TransferException(ProtocolConstants.ERROR_INVALID_SESSION, "FILE_META came from a different device.");
        }
        long size = fileMeta.getSize() == null ? -1 : fileMeta.getSize();
        if (size < 0) {
            throw new TransferException(ProtocolConstants.ERROR_MALFORMED_MESSAGE, "FILE_META size is invalid.");
        }
        if (size > ProtocolConstants.MAX_FILE_SIZE_BYTES) {
            throw new TransferException(ProtocolConstants.ERROR_FILE_TOO_LARGE, "File size exceeds the configured limit.");
        }
        if (receivedFiles + 1 > session.totalFiles()) {
            throw new TransferException(ProtocolConstants.ERROR_TOO_MANY_FILES, "The sender exceeded the declared file count.");
        }
        if (receivedBytes + size > ProtocolConstants.MAX_TOTAL_SESSION_BYTES
            || receivedBytes + size > session.totalSize()) {
            throw new TransferException(ProtocolConstants.ERROR_TOTAL_TRANSFER_TOO_LARGE, "The sender exceeded the declared total size.");
        }
    }

    private void receiveSingleFile(
        TransferSession session,
        ProtocolMessage fileMeta,
        DataInputStream input,
        DataOutputStream output,
        Path receiveFolder,
        String remoteAddress
    ) throws IOException {
        diagnosticsService.recordTransferEvent(
            DiagnosticEventType.TRANSFER_FILE_STARTED,
            DiagnosticDirection.IN,
            remoteAddress,
            session.senderDeviceId(),
            session.senderDeviceName(),
            null,
            null,
            null,
            fileMeta.getRelativePath()
        );

        Path relativePath;
        try {
            relativePath = FileUtils.sanitizeReceivedRelativePath(fileMeta.getRelativePath(), fileMeta.getFileName());
        } catch (TransferException exception) {
            drainPayload(input, fileMeta.getSize());
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, exception.getErrorCode(), exception.getMessage());
            throw exception;
        }

        Path targetPath = receiveFolder.resolve(relativePath).normalize();
        if (!targetPath.startsWith(receiveFolder)) {
            drainPayload(input, fileMeta.getSize());
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_INVALID_FILE_PATH, "Resolved target path leaves the receive folder.");
            throw new TransferException(ProtocolConstants.ERROR_INVALID_FILE_PATH, "Resolved target path leaves the receive folder.");
        }

        Path targetParent = targetPath.getParent() == null ? receiveFolder : targetPath.getParent();
        Files.createDirectories(targetParent);
        ensureUsableSpace(receiveFolder, fileMeta.getSize());
        Path finalPath = FileNameResolver.resolve(targetPath).normalize();
        if (!finalPath.startsWith(receiveFolder)) {
            drainPayload(input, fileMeta.getSize());
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_INVALID_FILE_PATH, "Final target path leaves the receive folder.");
            throw new TransferException(ProtocolConstants.ERROR_INVALID_FILE_PATH, "Final target path leaves the receive folder.");
        }

        Path tempPath = finalPath.resolveSibling(finalPath.getFileName() + ".localdrop-part");
        TransferException writeFailure = null;
        long remaining = fileMeta.getSize();
        try (var fileOutputStream = new BufferedOutputStream(Files.newOutputStream(tempPath))) {
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                int chunkSize = (int) Math.min(buffer.length, remaining);
                int read;
                try {
                    read = input.read(buffer, 0, chunkSize);
                } catch (SocketTimeoutException exception) {
                    Files.deleteIfExists(tempPath);
                    writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_TRANSFER_TIMEOUT, "File transfer timed out.");
                    throw new TransferException(ProtocolConstants.ERROR_TRANSFER_TIMEOUT, "File transfer timed out.");
                }
                if (read < 0) {
                    Files.deleteIfExists(tempPath);
                    throw new TransferException(ProtocolConstants.ERROR_CONNECTION_LOST, "Stream ended during file payload.");
                }
                if (writeFailure == null) {
                    try {
                        fileOutputStream.write(buffer, 0, read);
                    } catch (IOException exception) {
                        writeFailure = new TransferException(ProtocolConstants.ERROR_FILE_WRITE_ERROR, "Cannot write the received file.");
                    }
                }
                remaining -= read;
            }
        } catch (TransferException exception) {
            Files.deleteIfExists(tempPath);
            throw exception;
        } catch (IOException exception) {
            Files.deleteIfExists(tempPath);
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, ProtocolConstants.ERROR_FILE_WRITE_ERROR, "Cannot write the received file.");
            throw new TransferException(ProtocolConstants.ERROR_FILE_WRITE_ERROR, exception.getMessage());
        }

        if (writeFailure != null) {
            Files.deleteIfExists(tempPath);
            writeFileAck(output, session.sessionId(), fileMeta.getFileId(), false, writeFailure.getErrorCode(), writeFailure.getMessage());
            throw writeFailure;
        }

        FileUtils.moveAtomicallyOrReplace(tempPath, finalPath);
        if (fileMeta.getLastModified() != null) {
            FileUtils.applyLastModified(finalPath, fileMeta.getLastModified());
        }
        writeFileAck(output, session.sessionId(), fileMeta.getFileId(), true, ProtocolConstants.ERROR_NONE, "OK");

        diagnosticsService.recordTransferEvent(
            DiagnosticEventType.TRANSFER_FILE_ACKED,
            DiagnosticDirection.OUT,
            remoteAddress,
            session.senderDeviceId(),
            session.senderDeviceName(),
            null,
            ProtocolConstants.STATUS_OK,
            null,
            fileMeta.getRelativePath()
        );
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
        String errorCode,
        String errorMessage
    ) throws IOException {
        ProtocolMessage.write(output, ProtocolMessage.fileAck(
            sessionId,
            fileId,
            localDeviceId,
            localDeviceName,
            localDeviceType,
            ok,
            errorMessage,
            ok ? null : errorCode
        ));
    }

    private void writeSessionRejection(
        DataOutputStream output,
        String sessionId,
        String errorCode,
        String errorMessage
    ) throws IOException {
        ProtocolMessage.write(output, ProtocolMessage.sessionRejected(sessionId, errorMessage, errorCode));
    }

    private void cleanupPartialFiles() {
        try {
            FileUtils.cleanupPartialFiles(receiveFolderSupplier.get(), ProtocolConstants.PART_FILE_CLEANUP_TTL_MS);
        } catch (RuntimeException ignored) {
            // Cleanup is best-effort only.
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

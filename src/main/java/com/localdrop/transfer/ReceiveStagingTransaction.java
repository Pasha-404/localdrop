package com.localdrop.transfer;

import com.localdrop.util.FileUtils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

/**
 * Owns one receive-side staging file. Only a valid operation record can authorize its cleanup.
 */
final class ReceiveStagingTransaction {
    private static final String STAGING_DIRECTORY_NAME = ".localdrop-staging";
    private static final String OPERATION_FILE_NAME = "operation.properties";
    private static final String STAGING_FILE_NAME = "payload.bin";
    private static final String OPERATION_ID_KEY = "operationId";
    private static final String STAGING_FILE_KEY = "stagingFile";
    private static final int MAX_PUBLICATION_ATTEMPTS = 10_000;

    private final Path receiveRoot;
    private final Path operationDirectory;
    private final Path operationFile;
    private final Path stagingFile;
    private ReceiveStagingTransaction(Path receiveRoot, Path operationDirectory) {
        this.receiveRoot = receiveRoot;
        this.operationDirectory = operationDirectory;
        this.operationFile = operationDirectory.resolve(OPERATION_FILE_NAME);
        this.stagingFile = operationDirectory.resolve(STAGING_FILE_NAME);
    }

    static Path prepareReceiveRoot(Path configuredRoot) throws IOException {
        if (configuredRoot == null) {
            throw new IOException("Receive root is not selected.");
        }

        Path absoluteRoot = configuredRoot.toAbsolutePath().normalize();
        Files.createDirectories(absoluteRoot);
        if (!Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Receive root is not a directory.");
        }
        return absoluteRoot.toRealPath();
    }

    static Path prepareTargetPath(Path receiveRoot, Path relativePath) throws IOException {
        Path trustedRoot = prepareReceiveRoot(receiveRoot);
        if (relativePath == null || relativePath.isAbsolute()) {
            throw new IOException("Receive path is not relative.");
        }

        Path targetPath = trustedRoot.resolve(relativePath).normalize();
        if (!targetPath.startsWith(trustedRoot) || targetPath.getFileName() == null) {
            throw new IOException("Receive path leaves the configured root.");
        }

        Path trustedParent = ensureSafeDirectoryChain(trustedRoot, targetPath.getParent());
        return trustedParent.resolve(targetPath.getFileName());
    }

    static ReceiveStagingTransaction create(Path receiveRoot) throws IOException {
        Path trustedRoot = prepareReceiveRoot(receiveRoot);
        Path stagingRoot = ensureSafeDirectoryChain(trustedRoot, trustedRoot.resolve(STAGING_DIRECTORY_NAME));

        for (int attempt = 0; attempt < 100; attempt++) {
            String operationId = UUID.randomUUID().toString();
            Path operationDirectory = stagingRoot.resolve(operationId);
            try {
                Files.createDirectory(operationDirectory);
            } catch (FileAlreadyExistsException ignored) {
                continue;
            }

            ReceiveStagingTransaction transaction = new ReceiveStagingTransaction(trustedRoot, operationDirectory);
            try {
                transaction.writeOperationRecord(operationId);
                try (FileChannel ignored = FileChannel.open(
                    transaction.stagingFile,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
                )) {
                    // Reserve the exact staging file before any payload bytes are read.
                }
                return transaction;
            } catch (IOException exception) {
                transaction.cleanupQuietly();
                throw exception;
            }
        }

        throw new IOException("Could not reserve a unique staging operation.");
    }

    FileChannel openWriteChannel() throws IOException {
        verifyOwnedStagingFile();
        return FileChannel.open(stagingFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    void verifySize(long expectedSize) throws IOException {
        verifyOwnedStagingFile();
        if (Files.size(stagingFile) != expectedSize) {
            throw new IOException("Staged file size does not match the announced payload size.");
        }
    }

    Path publish(Path requestedTarget) throws IOException {
        verifyOwnedStagingFile();
        Path candidate = prepareTargetPath(receiveRoot, relativeToReceiveRoot(requestedTarget));

        for (int attempt = 0; attempt < MAX_PUBLICATION_ATTEMPTS; attempt++) {
            candidate = prepareTargetPath(receiveRoot, relativeToReceiveRoot(candidate));
            try {
                // Hard-link creation is an exclusive final-name reservation and never replaces a user file.
                Files.createLink(candidate, stagingFile);
                cleanupQuietly();
                return candidate;
            } catch (FileAlreadyExistsException ignored) {
                candidate = FileNameResolver.resolve(candidate);
            }
        }

        throw new IOException("Could not reserve a unique final filename.");
    }

    void cleanupQuietly() {
        try {
            cleanupOwnedOperation(operationDirectory, operationFile, stagingFile);
        } catch (IOException ignored) {
            // A later startup cleanup can remove only this recorded operation.
        }
    }

    static void cleanupAbandoned(Path receiveRoot, long olderThanMillis) {
        if (receiveRoot == null) {
            return;
        }

        try {
            Path trustedRoot = prepareReceiveRoot(receiveRoot);
            Path stagingRoot = trustedRoot.resolve(STAGING_DIRECTORY_NAME);
            if (!Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            ensureSafeDirectory(stagingRoot, trustedRoot);
            Instant cutoff = Instant.now().minusMillis(Math.max(0, olderThanMillis));

            try (DirectoryStream<Path> operations = Files.newDirectoryStream(stagingRoot)) {
                for (Path operationDirectory : operations) {
                    cleanupAbandonedOperation(trustedRoot, operationDirectory, cutoff);
                }
            }
        } catch (IOException ignored) {
            // Cleanup is best-effort and never expands beyond proven operation directories.
        }
    }

    private static void cleanupAbandonedOperation(Path receiveRoot, Path operationDirectory, Instant cutoff) throws IOException {
        if (!Files.isDirectory(operationDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        ensureSafeDirectory(operationDirectory, receiveRoot);

        String operationId = operationDirectory.getFileName().toString();
        if (!isUuid(operationId)) {
            return;
        }

        Path operationFile = operationDirectory.resolve(OPERATION_FILE_NAME);
        Path stagingFile = operationDirectory.resolve(STAGING_FILE_NAME);
        if (!isValidOperationRecord(operationFile, operationId)) {
            return;
        }
        if (Files.getLastModifiedTime(operationFile, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(cutoff)) {
            return;
        }
        if (Files.exists(stagingFile, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        cleanupOwnedOperation(operationDirectory, operationFile, stagingFile);
    }

    private void writeOperationRecord(String operationId) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(OPERATION_ID_KEY, operationId);
        properties.setProperty(STAGING_FILE_KEY, STAGING_FILE_NAME);
        try (FileChannel channel = FileChannel.open(
            operationFile,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        )) {
            properties.store(java.nio.channels.Channels.newOutputStream(channel), "LocalDrop receive staging operation");
            channel.force(true);
        }
    }

    private void verifyOwnedStagingFile() throws IOException {
        String operationId = operationDirectory.getFileName().toString();
        if (!isUuid(operationId)
            || !isValidOperationRecord(operationFile, operationId)
            || !Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Staging operation ownership cannot be verified.");
        }
        ensureSafeDirectory(operationDirectory, receiveRoot);
    }

    private static void cleanupOwnedOperation(Path operationDirectory, Path operationFile, Path stagingFile) throws IOException {
        if (Files.exists(stagingFile, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(stagingFile);
        }
        if (Files.exists(operationFile, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(operationFile, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(operationFile);
        }
        Files.deleteIfExists(operationDirectory);
    }

    private static Path ensureSafeDirectoryChain(Path receiveRoot, Path requestedDirectory) throws IOException {
        Path trustedRoot = prepareReceiveRoot(receiveRoot);
        Path absoluteDirectory = requestedDirectory.toAbsolutePath().normalize();
        if (!absoluteDirectory.startsWith(trustedRoot)) {
            throw new IOException("Directory leaves the configured receive root.");
        }

        Path current = trustedRoot;
        for (Path segment : trustedRoot.relativize(absoluteDirectory)) {
            Path next = current.resolve(segment);
            if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                ensureSafeDirectory(next, trustedRoot);
            } else {
                try {
                    Files.createDirectory(next);
                } catch (FileAlreadyExistsException ignored) {
                    // Another receiver may have created the directory first; validate it before use.
                }
                ensureSafeDirectory(next, trustedRoot);
            }
            current = next.toRealPath();
        }
        return current;
    }

    private static void ensureSafeDirectory(Path directory, Path receiveRoot) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Receive path contains a link or non-directory object.");
        }

        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(receiveRoot)) {
            throw new IOException("Receive path resolves outside the configured root.");
        }
    }

    private Path relativeToReceiveRoot(Path target) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path fileName = absoluteTarget.getFileName();
        Path parent = absoluteTarget.getParent();
        if (fileName == null || parent == null) {
            throw new IOException("Receive target has no parent directory.");
        }

        // Resolve the existing parent rather than trusting the spelling of a Windows path.
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(receiveRoot)) {
            throw new IOException("Receive target leaves the configured root.");
        }
        return receiveRoot.relativize(realParent).resolve(fileName);
    }

    private static boolean isValidOperationRecord(Path operationFile, String expectedOperationId) {
        if (!Files.isRegularFile(operationFile, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (var input = Files.newInputStream(operationFile, LinkOption.NOFOLLOW_LINKS)) {
            Properties properties = new Properties();
            properties.load(input);
            return expectedOperationId.equals(properties.getProperty(OPERATION_ID_KEY))
                && STAGING_FILE_NAME.equals(properties.getProperty(STAGING_FILE_KEY));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}

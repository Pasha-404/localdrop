package com.localdrop.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ReceiveStagingTransactionTest {
    @TempDir
    Path tempDir;

    @Test
    void publicationPreservesAnExistingFinalFileAndUsesASafeUniqueName() throws IOException {
        Path receiveRoot = Files.createDirectories(tempDir.resolve("receive"));
        Path existingFile = receiveRoot.resolve("report.txt");
        Files.writeString(existingFile, "USER ORIGINAL");

        ReceiveStagingTransaction transaction = ReceiveStagingTransaction.create(receiveRoot);
        writePayload(transaction, "INCOMING DATA");

        Path published = transaction.publish(existingFile);

        assertEquals("USER ORIGINAL", Files.readString(existingFile));
        assertEquals("INCOMING DATA", Files.readString(published));
        assertEquals("report (1).txt", published.getFileName().toString());
        assertNoOperationDirectories(receiveRoot);
    }

    @Test
    void concurrentPublicationsNeverReplaceEachOther() throws Exception {
        Path receiveRoot = Files.createDirectories(tempDir.resolve("receive"));
        ReceiveStagingTransaction first = ReceiveStagingTransaction.create(receiveRoot);
        ReceiveStagingTransaction second = ReceiveStagingTransaction.create(receiveRoot);
        writePayload(first, "FIRST");
        writePayload(second, "SECOND");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Path> firstResult = executor.submit(() -> publishWhenReleased(first, receiveRoot.resolve("same.txt"), ready, start));
            Future<Path> secondResult = executor.submit(() -> publishWhenReleased(second, receiveRoot.resolve("same.txt"), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Path firstPath = firstResult.get(5, TimeUnit.SECONDS);
            Path secondPath = secondResult.get(5, TimeUnit.SECONDS);
            assertFalse(firstPath.equals(secondPath));
            assertEquals(Set.of("FIRST", "SECOND"), Set.of(Files.readString(firstPath), Files.readString(secondPath)));
            assertNoOperationDirectories(receiveRoot);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cleanupPreservesUserSuffixFilesAndDeletesOnlyExpiredRecordedOperations() throws IOException {
        Path receiveRoot = Files.createDirectories(tempDir.resolve("receive"));
        Path userFile = receiveRoot.resolve("archive.localdrop-part");
        Files.writeString(userFile, "USER DATA");
        Files.setLastModifiedTime(userFile, FileTime.from(Instant.now().minusSeconds(2 * 24 * 60 * 60)));

        ReceiveStagingTransaction transaction = ReceiveStagingTransaction.create(receiveRoot);
        writePayload(transaction, "INCOMPLETE");
        Path operationDirectory = onlyOperationDirectory(receiveRoot);
        Files.setLastModifiedTime(
            operationDirectory.resolve("operation.properties"),
            FileTime.from(Instant.now().minusSeconds(2 * 24 * 60 * 60))
        );

        ReceiveStagingTransaction.cleanupAbandoned(receiveRoot, 24L * 60L * 60L * 1000L);
        ReceiveStagingTransaction.cleanupAbandoned(receiveRoot, 24L * 60L * 60L * 1000L);

        assertEquals("USER DATA", Files.readString(userFile));
        assertFalse(Files.exists(operationDirectory));
    }

    @Test
    void interruptedOperationLeavesNoFinalFileAndCleansOnlyItsOwnStagingFiles() throws IOException {
        Path receiveRoot = Files.createDirectories(tempDir.resolve("receive"));
        Path userFile = receiveRoot.resolve("draft.localdrop-part");
        Files.writeString(userFile, "USER DATA");

        ReceiveStagingTransaction transaction = ReceiveStagingTransaction.create(receiveRoot);
        writePayload(transaction, "PARTIAL");
        transaction.cleanupQuietly();

        assertFalse(Files.exists(receiveRoot.resolve("draft.txt")));
        assertEquals("USER DATA", Files.readString(userFile));
        assertNoOperationDirectories(receiveRoot);
    }

    @Test
    void rejectsAReceivePathWhoseJunctionEscapesTheReceiveRoot() throws Exception {
        Path receiveRoot = Files.createDirectories(tempDir.resolve("receive"));
        Path outsideRoot = Files.createDirectories(tempDir.resolve("outside"));
        Path junction = receiveRoot.resolve("outside-link");
        createJunction(junction, outsideRoot);

        assertThrows(IOException.class, () -> ReceiveStagingTransaction.prepareTargetPath(
            receiveRoot,
            Path.of("outside-link", "escaped.txt")
        ));
        assertFalse(Files.exists(outsideRoot.resolve("escaped.txt")));
    }

    private void writePayload(ReceiveStagingTransaction transaction, String content) throws IOException {
        try (FileChannel channel = transaction.openWriteChannel()) {
            ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        transaction.verifySize(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private Path publishWhenReleased(
        ReceiveStagingTransaction transaction,
        Path target,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IOException("Concurrent publication test was not released.");
        }
        return transaction.publish(target);
    }

    private Path onlyOperationDirectory(Path receiveRoot) throws IOException {
        try (var operations = Files.list(receiveRoot.resolve(".localdrop-staging"))) {
            return operations.findFirst().orElseThrow();
        }
    }

    private void assertNoOperationDirectories(Path receiveRoot) throws IOException {
        try (var operations = Files.list(receiveRoot.resolve(".localdrop-staging"))) {
            assertEquals(0, operations.count());
        }
    }

    private void createJunction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder(
            "cmd.exe",
            "/d",
            "/c",
            "mklink /J \"%s\" \"%s\"".formatted(link, target)
        ).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out while creating a disposable test junction.");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}

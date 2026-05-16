package com.localdrop.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileUtilsTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsSafeRelativePaths() throws IOException {
        Path path = FileUtils.sanitizeReceivedRelativePath("photos/trip/image.jpg", "image.jpg");

        assertEquals(Path.of("photos", "trip", "image.jpg"), path);
    }

    @Test
    void rejectsPathTraversal() {
        assertThrows(IOException.class, () -> FileUtils.sanitizeReceivedRelativePath("../secret.txt", "secret.txt"));
    }

    @Test
    void rejectsReservedWindowsNames() {
        assertThrows(IOException.class, () -> FileUtils.sanitizeReceivedRelativePath("safe/CON.txt", "CON.txt"));
    }

    @Test
    void cleansUpExpiredPartialFiles() throws IOException {
        Path oldPart = tempDir.resolve("old.localdrop-part");
        Path freshPart = tempDir.resolve("fresh.localdrop-part");
        Files.writeString(oldPart, "old");
        Files.writeString(freshPart, "fresh");
        Files.setLastModifiedTime(oldPart, FileTime.from(Instant.now().minusSeconds(2 * 24 * 60 * 60)));

        FileUtils.cleanupPartialFiles(tempDir, 24L * 60L * 60L * 1000L);

        assertFalse(Files.exists(oldPart));
        assertEquals("fresh", Files.readString(freshPart));
    }
}

package com.localdrop.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void rejectsRelativePathWhoseLeafDoesNotMatchFileName() {
        assertThrows(IOException.class, () -> FileUtils.sanitizeReceivedRelativePath("safe/other.txt", "file.txt"));
    }

    @Test
    void excludesInternalReceiveStagingFromFolderTransfers() throws IOException {
        Path selectedFolder = Files.createDirectories(tempDir.resolve("selected"));
        Files.writeString(selectedFolder.resolve("keep.txt"), "user file");
        Path stagingPayload = selectedFolder.resolve(".localdrop-staging").resolve("operation").resolve("payload.bin");
        Files.createDirectories(stagingPayload.getParent());
        Files.writeString(stagingPayload, "incomplete internal payload");

        List<FileUtils.TransferSource> sources = FileUtils.collectTransferSources(selectedFolder);

        assertEquals(List.of("selected/keep.txt"), sources.stream().map(FileUtils.TransferSource::relativePath).toList());
    }

}

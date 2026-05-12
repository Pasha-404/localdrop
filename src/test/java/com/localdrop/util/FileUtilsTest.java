package com.localdrop.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileUtilsTest {
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
}

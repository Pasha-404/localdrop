package com.localdrop.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPathsTest {
    @TempDir
    Path tempDir;

    @Test
    void copiesLegacyConfigWhenStandardConfigIsMissing() throws IOException {
        Path legacyConfig = tempDir.resolve("legacy").resolve("config.json");
        Path standardConfig = tempDir.resolve("standard").resolve("config.json");
        Files.createDirectories(legacyConfig.getParent());
        Files.writeString(legacyConfig, "{\"deviceId\":\"existing-device\"}");

        assertTrue(AppPaths.copyLegacyConfigIfMissing(legacyConfig, standardConfig));
        assertEquals("{\"deviceId\":\"existing-device\"}", Files.readString(standardConfig));
        assertTrue(Files.exists(legacyConfig));
    }

    @Test
    void neverOverwritesAnExistingStandardConfig() throws IOException {
        Path legacyConfig = tempDir.resolve("legacy").resolve("config.json");
        Path standardConfig = tempDir.resolve("standard").resolve("config.json");
        Files.createDirectories(legacyConfig.getParent());
        Files.createDirectories(standardConfig.getParent());
        Files.writeString(legacyConfig, "legacy");
        Files.writeString(standardConfig, "current");

        assertFalse(AppPaths.copyLegacyConfigIfMissing(legacyConfig, standardConfig));
        assertEquals("current", Files.readString(standardConfig));
    }
}

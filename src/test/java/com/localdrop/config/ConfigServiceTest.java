package com.localdrop.config;

import com.localdrop.i18n.AppLanguage;
import com.localdrop.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void malformedConfigIsPreservedBeforeDefaultsAreWritten() throws Exception {
        Path configFile = temporaryDirectory.resolve("config.json");
        byte[] invalidBytes = "{\"language\": ".getBytes(StandardCharsets.UTF_8);
        Files.write(configFile, invalidBytes);

        ConfigService service = new ConfigService(configFile, false);
        AppConfig loaded = service.load();

        List<Path> recoveryFiles;
        try (var files = Files.list(temporaryDirectory)) {
            recoveryFiles = files
                .filter(path -> path.getFileName().toString().startsWith("config.json.invalid-"))
                .toList();
        }
        assertEquals(1, recoveryFiles.size());
        assertArrayEquals(invalidBytes, Files.readAllBytes(recoveryFiles.getFirst()));
        assertNotNull(loaded.getDeviceId());
        assertNotNull(JsonUtils.read(configFile, AppConfig.class));
    }

    @Test
    void failedUpdateDoesNotMutateTheLoadedConfiguration() throws Exception {
        Path configFile = temporaryDirectory.resolve("config.json");
        ConfigService service = new ConfigService(configFile, false);
        AppConfig loaded = service.load();
        String originalLanguage = loaded.getLanguage();

        Files.delete(configFile);
        Files.createDirectory(configFile);

        assertThrows(IOException.class, () -> service.updateLanguage(AppLanguage.RUSSIAN));
        assertEquals(originalLanguage, loaded.getLanguage());
        assertEquals(originalLanguage, service.getConfig().getLanguage());
    }
}

package com.localdrop.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class AppPaths {
    private static final String PUBLISHER_DIRECTORY = "PashaApps";
    private static final String APP_DIRECTORY = "LocalDrop";

    private AppPaths() {
    }

    public static Path localAppDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData);
        }
        return Paths.get(System.getProperty("user.home"), "AppData", "Local");
    }

    public static Path roamingAppDataDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData);
        }
        return Paths.get(System.getProperty("user.home"), "AppData", "Roaming");
    }

    public static Path configDirectory() {
        return roamingAppDataDirectory().resolve(PUBLISHER_DIRECTORY).resolve(APP_DIRECTORY);
    }

    public static Path localDataDirectory() {
        return localAppDataDirectory().resolve(PUBLISHER_DIRECTORY).resolve(APP_DIRECTORY);
    }

    /**
     * Kept as a compatibility alias for code that stores user configuration.
     */
    public static Path appDirectory() {
        return configDirectory();
    }

    public static Path configFile() {
        return configDirectory().resolve("config.json");
    }

    public static Path logsDirectory() {
        return localDataDirectory().resolve("logs");
    }

    public static Path legacyConfigFile() {
        return localAppDataDirectory().resolve(APP_DIRECTORY).resolve("config.json");
    }

    /**
     * Preserves settings from pre-2.3.0 versions without altering their original file.
     */
    public static boolean migrateLegacyConfigIfNeeded() throws IOException {
        return copyLegacyConfigIfMissing(legacyConfigFile(), configFile());
    }

    static boolean copyLegacyConfigIfMissing(Path legacyConfigFile, Path targetConfigFile) throws IOException {
        if (!Files.isRegularFile(legacyConfigFile) || Files.exists(targetConfigFile)) {
            return false;
        }

        Files.createDirectories(targetConfigFile.getParent());
        Files.copy(legacyConfigFile, targetConfigFile, StandardCopyOption.COPY_ATTRIBUTES);
        return true;
    }
}

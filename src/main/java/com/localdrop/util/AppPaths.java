package com.localdrop.util;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AppPaths {
    private static final String APP_DIR_NAME = "LocalDrop";

    private AppPaths() {
    }

    public static Path localAppDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData);
        }
        return Paths.get(System.getProperty("user.home"), "AppData", "Local");
    }

    public static Path appDirectory() {
        return localAppDataDirectory().resolve(APP_DIR_NAME);
    }

    public static Path configFile() {
        return appDirectory().resolve("config.json");
    }

    public static Path logsDirectory() {
        return appDirectory().resolve("logs");
    }
}

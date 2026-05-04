package com.localdrop.config;

import com.localdrop.i18n.AppLanguage;
import com.localdrop.util.AppPaths;
import com.localdrop.util.JsonUtils;
import com.localdrop.util.LogService;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.logging.Logger;

public class ConfigService {
    private final Logger logger = LogService.getLogger(ConfigService.class);
    private final Path configFile = AppPaths.configFile();
    private AppConfig config;

    public AppConfig load() throws IOException {
        Files.createDirectories(AppPaths.appDirectory());
        if (Files.exists(configFile)) {
            try {
                config = JsonUtils.read(configFile, AppConfig.class);
            } catch (IOException exception) {
                logger.warning("Failed to read config, recreating defaults: " + exception.getMessage());
                config = new AppConfig();
            }
        } else {
            config = new AppConfig();
        }

        normalize();
        save();
        return config;
    }

    public AppConfig getConfig() {
        if (config == null) {
            throw new IllegalStateException("Config has not been loaded yet.");
        }
        return config;
    }

    public Path getReceiveFolder() {
        return Paths.get(getConfig().getReceiveFolder());
    }

    public void updateReceiveFolder(Path receiveFolder) throws IOException {
        getConfig().setReceiveFolder(receiveFolder.toAbsolutePath().normalize().toString());
        save();
    }

    public void updateWindowSize(double width, double height) throws IOException {
        getConfig().setWindowWidth(width);
        getConfig().setWindowHeight(height);
        save();
    }

    public void updateLanguage(AppLanguage language) throws IOException {
        getConfig().setLanguage(language.getCode());
        save();
    }

    public void save() throws IOException {
        Files.createDirectories(configFile.getParent());
        JsonUtils.writePretty(configFile, getConfig());
    }

    public static String resolveDeviceName() {
        String envName = System.getenv("COMPUTERNAME");
        if (envName != null && !envName.isBlank()) {
            return envName.trim();
        }

        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            if (hostName != null && !hostName.isBlank()) {
                return hostName.trim();
            }
        } catch (IOException ignored) {
            // Fall through to the generic label.
        }
        return "This PC";
    }

    public static Path defaultDownloadsFolder() {
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            return Paths.get(userProfile, "Downloads");
        }
        return Paths.get(System.getProperty("user.home"), "Downloads");
    }

    private void normalize() {
        if (config.getDeviceId() == null || config.getDeviceId().isBlank()) {
            config.setDeviceId(UUID.randomUUID().toString());
        }
        if (config.getReceiveFolder() == null || config.getReceiveFolder().isBlank()) {
            config.setReceiveFolder(defaultDownloadsFolder().toAbsolutePath().normalize().toString());
        }
        if (config.getLanguage() == null || config.getLanguage().isBlank()) {
            config.setLanguage(AppLanguage.detectDefault().getCode());
        }
        if (config.getWindowWidth() < 1200) {
            config.setWindowWidth(1400);
        }
        if (config.getWindowHeight() < 720) {
            config.setWindowHeight(800);
        }
    }
}

package com.localdrop.config;

import com.localdrop.i18n.AppLanguage;
import com.localdrop.util.AppPaths;
import com.localdrop.util.JsonUtils;
import com.localdrop.util.LogService;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ConfigService {
    private final Logger logger = LogService.getLogger(ConfigService.class);
    private final Path configFile;
    private final boolean migrateLegacyConfig;
    private AppConfig config;

    public ConfigService() {
        this(AppPaths.configFile(), true);
    }

    ConfigService(Path configFile, boolean migrateLegacyConfig) {
        this.configFile = configFile;
        this.migrateLegacyConfig = migrateLegacyConfig;
    }

    public synchronized AppConfig load() throws IOException {
        Files.createDirectories(configFile.getParent());
        if (migrateLegacyConfig) {
            try {
                if (AppPaths.migrateLegacyConfigIfNeeded()) {
                    logger.info("Migrated configuration from the legacy LocalDrop data directory.");
                }
            } catch (IOException exception) {
                logger.warning("Failed to migrate legacy configuration: " + exception.getMessage());
            }
        }

        if (Files.exists(configFile)) {
            try {
                config = JsonUtils.read(configFile, AppConfig.class);
            } catch (JsonProcessingException exception) {
                preserveInvalidConfig();
                logger.warning("Invalid config was preserved for recovery: " + exception.getMessage());
                config = new AppConfig();
            } catch (IOException exception) {
                throw new IOException("Unable to read the existing configuration without risking data loss.", exception);
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

    public synchronized void updateReceiveFolder(Path receiveFolder) throws IOException {
        updateAndSave(candidate -> candidate.setReceiveFolder(receiveFolder.toAbsolutePath().normalize().toString()));
    }

    public synchronized void updateWindowSize(double width, double height) throws IOException {
        updateAndSave(candidate -> {
            candidate.setWindowWidth(width);
            candidate.setWindowHeight(height);
            candidate.setWindowSizeConfigured(true);
        });
    }

    public synchronized void updateWindowState(double x, double y, double width, double height, boolean maximized) throws IOException {
        updateAndSave(candidate -> {
            candidate.setWindowX(x);
            candidate.setWindowY(y);
            candidate.setWindowWidth(width);
            candidate.setWindowHeight(height);
            candidate.setWindowPositionConfigured(true);
            candidate.setWindowSizeConfigured(true);
            candidate.setWindowMaximized(maximized);
        });
    }

    public synchronized void updateLanguage(AppLanguage language) throws IOException {
        updateAndSave(candidate -> candidate.setLanguage(language.getCode()));
    }

    public synchronized void save() throws IOException {
        writeAtomically(getConfig());
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
        if (config.getLogLevel() == null || config.getLogLevel().isBlank()) {
            config.setLogLevel("INFO");
        }
        if (!Double.isFinite(config.getWindowWidth()) || config.getWindowWidth() <= 0) {
            config.setWindowWidth(1400);
        }
        if (!Double.isFinite(config.getWindowHeight()) || config.getWindowHeight() <= 0) {
            config.setWindowHeight(800);
        }
        if (!Double.isFinite(config.getWindowX()) || !Double.isFinite(config.getWindowY())) {
            config.setWindowPositionConfigured(false);
            config.setWindowX(0);
            config.setWindowY(0);
        }
        if (!config.isWindowSizeConfigured() && config.getWindowHeight() == 640) {
            // Older configurations cannot distinguish the previous first-run default from a manual 640 px choice.
            config.setWindowHeight(800);
        }
    }

    private void updateAndSave(Consumer<AppConfig> mutation) throws IOException {
        AppConfig candidate = getConfig().copy();
        mutation.accept(candidate);
        writeAtomically(candidate);
        getConfig().copyFrom(candidate);
    }

    private void writeAtomically(AppConfig value) throws IOException {
        Files.createDirectories(configFile.getParent());
        byte[] json = JsonUtils.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Path temporaryFile = Files.createTempFile(
            configFile.getParent(),
            "." + configFile.getFileName() + ".",
            ".tmp"
        );
        boolean published = false;
        try {
            try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporaryFile, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private void preserveInvalidConfig() throws IOException {
        Path recoveryFile = configFile.resolveSibling(
            configFile.getFileName() + ".invalid-" + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')
                + "-" + UUID.randomUUID() + ".json"
        );
        // Preserve the unreadable user data before creating defaults. Metadata is not needed for recovery and
        // COPY_ATTRIBUTES can itself fail on otherwise usable Windows file systems.
        Files.copy(configFile, recoveryFile);
    }
}

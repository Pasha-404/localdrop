package com.localdrop.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class LogService {
    private static final Logger ROOT_LOGGER = Logger.getLogger("com.localdrop");
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Duration RETENTION = Duration.ofHours(24);
    private static volatile boolean initialized;

    private LogService() {
    }

    public static synchronized void initialize() throws IOException {
        if (initialized) {
            return;
        }

        Path logsDirectory = AppPaths.logsDirectory();
        Files.createDirectories(logsDirectory);
        cleanupOldLogs(logsDirectory);

        ROOT_LOGGER.setUseParentHandlers(false);
        ROOT_LOGGER.setLevel(Level.INFO);
        for (Handler handler : ROOT_LOGGER.getHandlers()) {
            ROOT_LOGGER.removeHandler(handler);
        }

        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                LocalDateTime timestamp = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(record.getMillis()),
                    java.time.ZoneId.systemDefault()
                );
                String thrown = record.getThrown() == null ? "" : System.lineSeparator() + stackTrace(record.getThrown());
                return "%s [%s] %s%n%s".formatted(
                    timestamp,
                    record.getLevel().getName(),
                    formatMessage(record),
                    thrown
                );
            }
        };

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(formatter);
        ROOT_LOGGER.addHandler(consoleHandler);

        String logFileName = "localdrop-" + FILE_FORMAT.format(LocalDateTime.now()) + ".log";
        FileHandler fileHandler = new FileHandler(logsDirectory.resolve(logFileName).toString(), true);
        fileHandler.setLevel(Level.ALL);
        fileHandler.setFormatter(formatter);
        ROOT_LOGGER.addHandler(fileHandler);

        initialized = true;
        ROOT_LOGGER.info("Logging initialized");
    }

    public static Logger getLogger(Class<?> type) {
        return Logger.getLogger("com.localdrop." + type.getSimpleName());
    }

    public static synchronized void shutdown() {
        for (Handler handler : ROOT_LOGGER.getHandlers()) {
            handler.flush();
            handler.close();
            ROOT_LOGGER.removeHandler(handler);
        }
        initialized = false;
    }

    private static void cleanupOldLogs(Path logsDirectory) throws IOException {
        Instant cutoff = Instant.now().minus(RETENTION);
        try (Stream<Path> stream = Files.list(logsDirectory)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("localdrop-"))
                .sorted(Comparator.naturalOrder())
                .forEach(path -> deleteIfOld(path, cutoff));
        }
    }

    private static void deleteIfOld(Path path, Instant cutoff) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(path);
            if (lastModified.toInstant().isBefore(cutoff)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best-effort cleanup; logging is not configured yet.
        }
    }

    private static String stackTrace(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        builder.append(throwable).append(System.lineSeparator());
        for (StackTraceElement element : throwable.getStackTrace()) {
            builder.append("    at ").append(element).append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}

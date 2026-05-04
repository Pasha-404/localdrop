package com.localdrop.util;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class BootstrapDiagnostics {
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BootstrapDiagnostics() {
    }

    public static void installGlobalHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
            reportFailure("Unexpected application error", throwable)
        );
    }

    public static void reportFailure(String title, Throwable throwable) {
        Path logFile = writeFailureLog(title, throwable);
        String message = """
            LocalDrop could not start correctly.

            %s

            Diagnostic log:
            %s
            """.formatted(throwable == null ? "No additional error details." : throwable.toString(), logFile.toAbsolutePath());

        try {
            SwingUtilities.invokeAndWait(() ->
                JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
            );
        } catch (Exception ignored) {
            // If even the fallback dialog fails, the diagnostic file is still written when possible.
        }
    }

    private static Path writeFailureLog(String title, Throwable throwable) {
        Path directory;
        try {
            directory = AppPaths.logsDirectory();
            Files.createDirectories(directory);
        } catch (Exception exception) {
            directory = Path.of(System.getProperty("java.io.tmpdir"), "LocalDrop");
            try {
                Files.createDirectories(directory);
            } catch (Exception ignored) {
                directory = Path.of(System.getProperty("java.io.tmpdir"));
            }
        }

        Path logFile = directory.resolve("startup-error-" + FILE_FORMAT.format(LocalDateTime.now()) + ".log");
        StringWriter stackTrace = new StringWriter();
        if (throwable != null) {
            throwable.printStackTrace(new PrintWriter(stackTrace));
        }

        String contents = """
            LocalDrop bootstrap error
            Title: %s
            Time: %s

            %s
            """.formatted(title, LocalDateTime.now(), stackTrace);

        try {
            Files.writeString(logFile, contents, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Best effort only.
        }
        return logFile;
    }
}

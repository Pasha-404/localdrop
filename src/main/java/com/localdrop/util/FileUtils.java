package com.localdrop.util;

import java.awt.Desktop;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class FileUtils {
    private FileUtils() {
    }

    public static List<TransferSource> collectTransferSources(Path source) throws IOException {
        if (!Files.exists(source)) {
            return List.of();
        }

        if (Files.isRegularFile(source)) {
            return List.of(createTransferSource(source, source.getFileName().toString()));
        }

        if (!Files.isDirectory(source)) {
            return List.of();
        }

        Path rootName = Objects.requireNonNullElse(source.getFileName(), source);
        List<TransferSource> files = new ArrayList<>();
        try (var stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder())
                .forEach(file -> {
                    try {
                        String relativePath = rootName.resolve(source.relativize(file)).toString().replace('\\', '/');
                        files.add(createTransferSource(file, relativePath));
                    } catch (IOException ignored) {
                        // The caller logs skipped files after the scan.
                    }
                });
        }
        return files;
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = -1;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format(Locale.US, value >= 10 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
    }

    public static Path sanitizeRelativePath(String relativePath, String fallbackFileName) {
        String candidate = relativePath == null || relativePath.isBlank() ? fallbackFileName : relativePath;
        String normalizedSeparators = candidate.replace('\\', '/');
        Path normalized = Paths.get(normalizedSeparators).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            return Paths.get(fallbackFileName).normalize();
        }
        return normalized;
    }

    public static Path ensureUniqueFile(Path target) {
        if (!Files.exists(target)) {
            return target;
        }

        String fileName = target.getFileName().toString();
        int extensionSeparator = fileName.lastIndexOf('.');
        String baseName = extensionSeparator > 0 ? fileName.substring(0, extensionSeparator) : fileName;
        String extension = extensionSeparator > 0 ? fileName.substring(extensionSeparator) : "";

        Path parent = target.getParent();
        if (parent == null) {
            parent = target.toAbsolutePath().getParent();
        }
        if (parent == null) {
            throw new IllegalArgumentException("Target path must have a parent: " + target);
        }
        int suffix = 1;
        while (true) {
            Path candidate = parent.resolve("%s (%d)%s".formatted(baseName, suffix, extension));
            if (!Files.exists(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    public static void moveAtomicallyOrReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void openDirectory(Path directory) throws IOException {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(directory.toFile());
        }
    }

    public static String detectNetworkName() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback() && !networkInterface.isVirtual()) {
                    return networkInterface.getDisplayName();
                }
            }
        } catch (SocketException ignored) {
            // Fall through to the default label.
        }
        return "Local network";
    }

    public static void applyLastModified(Path file, long lastModified) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochMilli(lastModified)));
        } catch (IOException ignored) {
            // Restoring timestamps is best-effort only.
        }
    }

    private static TransferSource createTransferSource(Path absolutePath, String relativePath) throws IOException {
        return new TransferSource(
            absolutePath.toAbsolutePath().normalize(),
            relativePath.replace('\\', '/'),
            Files.size(absolutePath),
            Files.getLastModifiedTime(absolutePath).toMillis()
        );
    }

    public record TransferSource(Path absolutePath, String relativePath, long size, long lastModified) {
    }
}

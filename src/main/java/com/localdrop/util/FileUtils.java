package com.localdrop.util;

import com.localdrop.protocol.ProtocolConstants;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class FileUtils {
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

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

    public static Path sanitizeReceivedRelativePath(String relativePath, String fileName) throws IOException {
        validateFileName(fileName);
        String candidate = relativePath == null || relativePath.isBlank() ? fileName : relativePath.trim();
        if (candidate.length() > ProtocolConstants.MAX_RELATIVE_PATH_LENGTH) {
            throw new IOException("Relative path is too long.");
        }
        if (candidate.indexOf('\0') >= 0 || candidate.startsWith("/") || candidate.startsWith("\\")) {
            throw new IOException("Relative path is not safe.");
        }
        if (candidate.matches("^[A-Za-z]:.*") || candidate.startsWith("//") || candidate.startsWith("\\\\")) {
            throw new IOException("Absolute paths are not allowed.");
        }

        String normalizedSeparators = candidate.replace('\\', '/');
        Path normalized = Paths.get(normalizedSeparators).normalize();
        if (normalized.isAbsolute() || normalized.getNameCount() == 0 || normalized.startsWith("..")) {
            throw new IOException("Relative path is not safe.");
        }
        if (normalized.getNameCount() > ProtocolConstants.MAX_RELATIVE_PATH_DEPTH) {
            throw new IOException("Relative path is too deep.");
        }

        Set<String> seenSegments = new HashSet<>();
        for (Path segmentPath : normalized) {
            String segment = segmentPath.toString();
            validatePathSegment(segment);
            seenSegments.add(segment);
        }
        if (seenSegments.isEmpty()) {
            throw new IOException("Relative path is empty.");
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

    private static void validateFileName(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()) {
            throw new IOException("File name is empty.");
        }
        if (fileName.length() > ProtocolConstants.MAX_FILE_NAME_LENGTH) {
            throw new IOException("File name is too long.");
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.indexOf('\0') >= 0) {
            throw new IOException("File name contains invalid characters.");
        }
        validatePathSegment(fileName);
    }

    private static void validatePathSegment(String segment) throws IOException {
        if (segment == null || segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
            throw new IOException("Path contains an empty or unsafe segment.");
        }
        if (segment.endsWith(" ") || segment.endsWith(".")) {
            throw new IOException("Windows path segment cannot end with a space or dot.");
        }
        for (int index = 0; index < segment.length(); index++) {
            char ch = segment.charAt(index);
            if (Character.isISOControl(ch) || "<>:\"|?*".indexOf(ch) >= 0) {
                throw new IOException("Path segment contains invalid characters.");
            }
        }
        String stem = segment;
        int extensionIndex = stem.indexOf('.');
        if (extensionIndex >= 0) {
            stem = stem.substring(0, extensionIndex);
        }
        if (WINDOWS_RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
            throw new IOException("Path segment uses a reserved Windows name.");
        }
    }

    public record TransferSource(Path absolutePath, String relativePath, long size, long lastModified) {
    }
}

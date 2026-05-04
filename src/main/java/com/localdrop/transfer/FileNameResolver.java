package com.localdrop.transfer;

import com.localdrop.util.FileUtils;

import java.nio.file.Path;

public final class FileNameResolver {
    private FileNameResolver() {
    }

    public static Path resolve(Path targetPath) {
        return FileUtils.ensureUniqueFile(targetPath);
    }
}

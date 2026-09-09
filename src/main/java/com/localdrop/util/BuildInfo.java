package com.localdrop.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads release metadata embedded by Gradle, including when classes are run outside the packaged JAR. */
public final class BuildInfo {
    private static final String RESOURCE_PATH = "/com/localdrop/build-info.properties";
    private static final Properties PROPERTIES = loadProperties();

    private BuildInfo() {
    }

    public static String version() {
        return PROPERTIES.getProperty("version", "development");
    }

    public static String appId() {
        return PROPERTIES.getProperty("appId", "unknown");
    }

    public static String repositoryUrl() {
        return PROPERTIES.getProperty("repositoryUrl", "unknown");
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            // Diagnostics must remain available even if the optional build metadata resource is unreadable.
        }
        return properties;
    }
}

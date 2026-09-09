package com.localdrop.config;

public class AppConfig {
    private String deviceId;
    private String receiveFolder;
    private String language;
    private String logLevel;
    private double windowWidth = 1400;
    private double windowHeight = 800;
    private boolean windowSizeConfigured;
    private double windowX;
    private double windowY;
    private boolean windowPositionConfigured;
    private boolean windowMaximized;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getReceiveFolder() {
        return receiveFolder;
    }

    public void setReceiveFolder(String receiveFolder) {
        this.receiveFolder = receiveFolder;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public double getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(double windowWidth) {
        this.windowWidth = windowWidth;
    }

    public double getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(double windowHeight) {
        this.windowHeight = windowHeight;
    }

    public boolean isWindowSizeConfigured() {
        return windowSizeConfigured;
    }

    public void setWindowSizeConfigured(boolean windowSizeConfigured) {
        this.windowSizeConfigured = windowSizeConfigured;
    }

    public double getWindowX() {
        return windowX;
    }

    public void setWindowX(double windowX) {
        this.windowX = windowX;
    }

    public double getWindowY() {
        return windowY;
    }

    public void setWindowY(double windowY) {
        this.windowY = windowY;
    }

    public boolean isWindowPositionConfigured() {
        return windowPositionConfigured;
    }

    public void setWindowPositionConfigured(boolean windowPositionConfigured) {
        this.windowPositionConfigured = windowPositionConfigured;
    }

    public boolean isWindowMaximized() {
        return windowMaximized;
    }

    public void setWindowMaximized(boolean windowMaximized) {
        this.windowMaximized = windowMaximized;
    }

    public AppConfig copy() {
        AppConfig copy = new AppConfig();
        copy.deviceId = deviceId;
        copy.receiveFolder = receiveFolder;
        copy.language = language;
        copy.logLevel = logLevel;
        copy.windowWidth = windowWidth;
        copy.windowHeight = windowHeight;
        copy.windowSizeConfigured = windowSizeConfigured;
        copy.windowX = windowX;
        copy.windowY = windowY;
        copy.windowPositionConfigured = windowPositionConfigured;
        copy.windowMaximized = windowMaximized;
        return copy;
    }

    public void copyFrom(AppConfig source) {
        deviceId = source.deviceId;
        receiveFolder = source.receiveFolder;
        language = source.language;
        logLevel = source.logLevel;
        windowWidth = source.windowWidth;
        windowHeight = source.windowHeight;
        windowSizeConfigured = source.windowSizeConfigured;
        windowX = source.windowX;
        windowY = source.windowY;
        windowPositionConfigured = source.windowPositionConfigured;
        windowMaximized = source.windowMaximized;
    }
}

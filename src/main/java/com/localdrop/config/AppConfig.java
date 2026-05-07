package com.localdrop.config;

public class AppConfig {
    private String deviceId;
    private String receiveFolder;
    private String language;
    private String logLevel;
    private double windowWidth = 1400;
    private double windowHeight = 800;

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
}

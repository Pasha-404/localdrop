package com.localdrop.transfer;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.util.UUID;

public class TransferQueueItem {
    private final String id = UUID.randomUUID().toString();
    private final Path sourcePath;
    private final String relativePath;
    private final long size;
    private final long lastModified;
    private final ObjectProperty<TransferStatus> status = new SimpleObjectProperty<>(TransferStatus.QUEUED);
    private final StringProperty message = new SimpleStringProperty("");
    private final DoubleProperty progress = new SimpleDoubleProperty(0);

    public TransferQueueItem(Path sourcePath, String relativePath, long size, long lastModified) {
        this.sourcePath = sourcePath;
        this.relativePath = relativePath;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getId() {
        return id;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public TransferStatus getStatus() {
        return status.get();
    }

    public void setStatus(TransferStatus status) {
        this.status.set(status);
    }

    public ObjectProperty<TransferStatus> statusProperty() {
        return status;
    }

    public String getMessage() {
        return message.get();
    }

    public void setMessage(String message) {
        this.message.set(message == null ? "" : message);
    }

    public StringProperty messageProperty() {
        return message;
    }

    public double getProgress() {
        return progress.get();
    }

    public void setProgress(double progress) {
        this.progress.set(progress);
    }

    public DoubleProperty progressProperty() {
        return progress;
    }

    public String getDisplayName() {
        return relativePath;
    }

    public boolean canRemove() {
        return getStatus() != TransferStatus.SENDING;
    }
}

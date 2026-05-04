package com.localdrop.ui;

import com.localdrop.config.AppConfig;
import com.localdrop.config.ConfigService;
import com.localdrop.discovery.DiscoveryService;
import com.localdrop.i18n.AppLanguage;
import com.localdrop.i18n.I18n;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.transfer.RecentlyReceivedItem;
import com.localdrop.transfer.TransferClient;
import com.localdrop.transfer.TransferQueueItem;
import com.localdrop.transfer.TransferServer;
import com.localdrop.transfer.TransferStatus;
import com.localdrop.util.FileUtils;
import com.localdrop.util.LogService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class MainController {
    private enum ReceiveActivity {
        READY,
        UNAVAILABLE,
        RECEIVING_FROM,
        LAST_RECEIVED
    }

    private final Logger logger = LogService.getLogger(MainController.class);
    private final ConfigService configService;
    private final AppConfig config;
    private final String deviceName;
    private final ObservableList<DeviceInfo> devices = FXCollections.observableArrayList();
    private final ObservableList<TransferQueueItem> queueItems = FXCollections.observableArrayList();
    private final ObservableList<RecentlyReceivedItem> recentItems = FXCollections.observableArrayList();
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "localdrop-background");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final TransferClient transferClient = new TransferClient();
    private final I18n i18n;
    private final MainView view = new MainView(devices, queueItems, recentItems);

    private Stage stage;
    private TransferServer transferServer;
    private DiscoveryService discoveryService;
    private volatile boolean transferInProgress;
    private ReceiveActivity receiveActivity = ReceiveActivity.READY;
    private String receiveActivityArgument;

    public MainController(ConfigService configService, AppConfig config, String deviceName) {
        this.configService = configService;
        this.config = config;
        this.deviceName = deviceName;
        this.i18n = new I18n(AppLanguage.fromCode(config.getLanguage()));
        wireUi();
    }

    public Parent getRoot() {
        return view.getRoot();
    }

    public void attachStage(Stage stage) {
        this.stage = stage;
        view.setI18n(i18n);
        view.setLanguageSelection(i18n.getLanguage());
        view.updateCurrentDeviceName(deviceName);
        view.updateReceiveFolder(configService.getReceiveFolder());
        view.updateNetworkLabel(FileUtils.detectNetworkName());
        updateReceiveActivityLabel();
    }

    public void startServices() throws IOException {
        transferServer = new TransferServer(configService::getReceiveFolder, new TransferServer.Listener() {
            @Override
            public void onReceiveCompleted(RecentlyReceivedItem item) {
                Platform.runLater(() -> {
                    recentItems.add(0, item);
                    if (recentItems.size() > 5) {
                        recentItems.remove(5, recentItems.size());
                    }
                    setReceiveActivity(ReceiveActivity.LAST_RECEIVED, item.name());
                    view.refreshRecent();
                });
            }

            @Override
            public void onReadyToReceive() {
                Platform.runLater(() -> setReceiveActivity(ReceiveActivity.READY, null));
            }

            @Override
            public void onReceivingFrom(String senderDeviceName) {
                Platform.runLater(() -> setReceiveActivity(ReceiveActivity.RECEIVING_FROM, senderDeviceName));
            }
        });

        boolean receiveAvailable = false;
        try {
            transferServer.start();
            receiveAvailable = true;
            setReceiveActivity(ReceiveActivity.READY, null);
        } catch (IOException exception) {
            logger.severe("Unable to start receive service: " + exception.getMessage());
            setReceiveActivity(ReceiveActivity.UNAVAILABLE, null);
            view.updateInlineError(i18n.format("errors.receiveService", exception.getMessage()));
        }

        if (receiveAvailable) {
            discoveryService = new DiscoveryService(
                config.getDeviceId(),
                deviceName,
                "PC",
                transferServer.getBoundPort(),
                snapshot -> Platform.runLater(() -> applyDeviceSnapshot(snapshot))
            );

            try {
                discoveryService.start();
            } catch (IOException exception) {
                logger.severe("Unable to start device discovery: " + exception.getMessage());
                view.updateInlineError(i18n.format("errors.discoveryService", exception.getMessage()));
            }
        }
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        try {
            if (stage != null) {
                configService.updateWindowSize(stage.getWidth(), stage.getHeight());
            }
        } catch (IOException exception) {
            logger.warning("Failed to persist window size: " + exception.getMessage());
        }

        if (discoveryService != null) {
            discoveryService.stop();
        }
        if (transferServer != null) {
            transferServer.stop();
        }
        backgroundExecutor.shutdownNow();
    }

    private void wireUi() {
        view.setRemoveQueueItemAction(this::removeQueueItem);

        view.getRefreshButton().setOnAction(event -> {
            if (discoveryService != null) {
                discoveryService.refreshNow();
            }
        });
        view.getAddFilesButton().setOnAction(event -> chooseFiles());
        view.getAddFolderButton().setOnAction(event -> chooseFolder());
        view.getSendButton().setOnAction(event -> sendQueue());
        view.getClearQueueButton().setOnAction(event -> clearQueue());
        view.getChangeFolderButton().setOnAction(event -> changeReceiveFolder());
        view.getOpenFolderButton().setOnAction(event -> openReceiveFolder());
        view.getHelpButton().setOnAction(event -> Dialogs.showHelp(stage, i18n));
        view.getAboutButton().setOnAction(event -> Dialogs.showAbout(stage, i18n));
        view.getLanguageChoiceBox().getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && newValue != i18n.getLanguage()) {
                changeLanguage(newValue);
            }
        });
        view.getDeviceListView().getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            view.refreshDevices();
            updateSendButtonState();
        });

        queueItems.addListener((ListChangeListener<TransferQueueItem>) change -> {
            while (change.next()) {
                // The list view reads item properties directly; only counters and CTA state are synchronized here.
            }
            view.updateQueueCount(queueItems.size());
            view.refreshQueue();
            updateSendButtonState();
        });

        recentItems.addListener((ListChangeListener<RecentlyReceivedItem>) change -> view.refreshRecent());

        configureDragAndDrop();
        updateSendButtonState();
    }

    private void configureDragAndDrop() {
        view.getDropArea().setOnDragOver(event -> {
            if (event.getGestureSource() != view.getDropArea() && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        view.getDropArea().setOnDragDropped(this::handleDrop);
    }

    private void handleDrop(DragEvent event) {
        boolean success = false;
        if (event.getDragboard().hasFiles()) {
            List<Path> paths = event.getDragboard().getFiles().stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .toList();
            enqueuePaths(paths);
            success = true;
        }
        event.setDropCompleted(success);
        event.consume();
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n.text("chooser.addFiles"));
        List<java.io.File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            enqueuePaths(files.stream().map(file -> file.toPath().toAbsolutePath().normalize()).toList());
        }
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(i18n.text("chooser.addFolder"));
        java.io.File folder = chooser.showDialog(stage);
        if (folder != null) {
            enqueuePaths(List.of(folder.toPath().toAbsolutePath().normalize()));
        }
    }

    private void enqueuePaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }

        view.updateInlineError("");
        backgroundExecutor.submit(() -> {
            List<TransferQueueItem> collected = new ArrayList<>();
            boolean hadSkippedItems = false;

            for (Path path : paths) {
                try {
                    for (FileUtils.TransferSource source : FileUtils.collectTransferSources(path)) {
                        collected.add(new TransferQueueItem(
                            source.absolutePath(),
                            source.relativePath(),
                            source.size(),
                            source.lastModified()
                        ));
                    }
                } catch (IOException exception) {
                    hadSkippedItems = true;
                    logger.warning("Failed to scan " + path + ": " + exception.getMessage());
                }
            }

            boolean showWarning = hadSkippedItems;
            Platform.runLater(() -> {
                queueItems.addAll(collected);
                if (showWarning) {
                    view.updateInlineError(i18n.text("errors.skippedItems"));
                }
            });
        });
    }

    private void sendQueue() {
        DeviceInfo selectedDevice = view.getDeviceListView().getSelectionModel().getSelectedItem();
        List<TransferQueueItem> pendingItems = queueItems.stream()
            .filter(item -> item.getStatus() == TransferStatus.QUEUED
                || item.getStatus() == TransferStatus.FAILED
                || item.getStatus() == TransferStatus.WAITING_FOR_RETRY)
            .toList();

        if (selectedDevice == null || pendingItems.isEmpty() || transferInProgress) {
            return;
        }

        transferInProgress = true;
        view.updateInlineError("");
        updateSendButtonState();

        backgroundExecutor.submit(() -> transferClient.sendFiles(
            selectedDevice,
            config.getDeviceId(),
            deviceName,
            pendingItems,
            new TransferClient.Listener() {
                @Override
                public void onItemStatusChanged(TransferQueueItem item, TransferStatus status, String message) {
                    Platform.runLater(() -> {
                        item.setStatus(status);
                        item.setMessage(message == null ? "" : message);
                        if (status != TransferStatus.SENDING) {
                            item.setProgress(0);
                        }
                        view.refreshQueue();
                    });
                }

                @Override
                public void onItemProgress(TransferQueueItem item, double progress) {
                    Platform.runLater(() -> {
                        item.setStatus(TransferStatus.SENDING);
                        item.setMessage("");
                        item.setProgress(progress);
                        view.refreshQueue();
                    });
                }

                @Override
                public void onItemAcknowledged(TransferQueueItem item) {
                    Platform.runLater(() -> queueItems.remove(item));
                }

                @Override
                public void onTransferIssue(String targetDeviceName, String details) {
                    Platform.runLater(() -> view.updateInlineError(i18n.format("errors.sendTo", targetDeviceName, details)));
                }

                @Override
                public void onReceiverRejected(String reason) {
                    Platform.runLater(() -> view.updateInlineError(
                        reason == null || reason.isBlank() ? i18n.text("errors.receiverRejected") : reason
                    ));
                }

                @Override
                public void onTransferFinished() {
                    Platform.runLater(() -> {
                        transferInProgress = false;
                        updateSendButtonState();
                    });
                }
            }
        ));
    }

    private void clearQueue() {
        queueItems.removeIf(item -> item.getStatus() != TransferStatus.SENDING);
        updateSendButtonState();
    }

    private void removeQueueItem(TransferQueueItem item) {
        if (item != null && item.canRemove()) {
            queueItems.remove(item);
        }
    }

    private void changeReceiveFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(i18n.text("chooser.saveFolder"));
        Path currentFolder = configService.getReceiveFolder();
        if (currentFolder != null && currentFolder.toFile().exists()) {
            chooser.setInitialDirectory(currentFolder.toFile());
        }

        java.io.File folder = chooser.showDialog(stage);
        if (folder == null) {
            return;
        }

        try {
            configService.updateReceiveFolder(folder.toPath().toAbsolutePath().normalize());
            view.updateReceiveFolder(configService.getReceiveFolder());
            view.updateInlineError("");
        } catch (IOException exception) {
            logger.warning("Failed to save receive folder: " + exception.getMessage());
            view.updateInlineError(i18n.text("errors.saveReceiveFolder"));
        }
    }

    private void openReceiveFolder() {
        try {
            Path receiveFolder = configService.getReceiveFolder();
            Files.createDirectories(receiveFolder);
            FileUtils.openDirectory(receiveFolder);
        } catch (IOException exception) {
            logger.warning("Failed to open receive folder: " + exception.getMessage());
            view.updateInlineError(i18n.text("errors.openReceiveFolder"));
        }
    }

    private void applyDeviceSnapshot(List<DeviceInfo> snapshot) {
        DeviceInfo selectedDevice = view.getDeviceListView().getSelectionModel().getSelectedItem();
        String selectedDeviceId = selectedDevice == null ? null : selectedDevice.getDeviceId();

        devices.setAll(snapshot);
        if (selectedDeviceId != null) {
            for (DeviceInfo device : devices) {
                if (selectedDeviceId.equals(device.getDeviceId())) {
                    view.getDeviceListView().getSelectionModel().select(device);
                    break;
                }
            }
        }
        view.refreshDevices();
        updateSendButtonState();
    }

    private void changeLanguage(AppLanguage language) {
        i18n.setLanguage(language);
        config.setLanguage(language.getCode());
        view.setI18n(i18n);
        view.setLanguageSelection(language);
        updateReceiveActivityLabel();
        updateSendButtonState();

        try {
            configService.updateLanguage(language);
        } catch (IOException exception) {
            logger.warning("Failed to save language preference: " + exception.getMessage());
        }
    }

    private void setReceiveActivity(ReceiveActivity activity, String argument) {
        receiveActivity = activity;
        receiveActivityArgument = argument;
        updateReceiveActivityLabel();
    }

    private void updateReceiveActivityLabel() {
        String message = switch (receiveActivity) {
            case READY -> i18n.text("receiving.status.ready");
            case UNAVAILABLE -> i18n.text("receiving.status.unavailable");
            case RECEIVING_FROM -> i18n.format("receiving.status.receivingFrom", receiveActivityArgument);
            case LAST_RECEIVED -> i18n.format("receiving.status.lastReceived", receiveActivityArgument);
        };
        view.updateReceivingActivity(message);
    }

    private void updateSendButtonState() {
        DeviceInfo selectedDevice = view.getDeviceListView().getSelectionModel().getSelectedItem();
        long pendingCount = queueItems.stream()
            .filter(item -> item.getStatus() == TransferStatus.QUEUED
                || item.getStatus() == TransferStatus.FAILED
                || item.getStatus() == TransferStatus.WAITING_FOR_RETRY)
            .count();

        String buttonText;
        boolean disabled;
        if (transferInProgress) {
            buttonText = i18n.text("sending.button.sending");
            disabled = true;
        } else if (selectedDevice == null) {
            buttonText = i18n.text("sending.button.select");
            disabled = true;
        } else {
            buttonText = i18n.format("sending.button.sendTo", selectedDevice.getDeviceName());
            disabled = pendingCount == 0;
        }
        view.updateSendButton(buttonText, disabled);
    }
}

package com.localdrop.ui;

import com.localdrop.config.AppConfig;
import com.localdrop.config.ConfigService;
import com.localdrop.diagnostics.DiagnosticsService;
import com.localdrop.discovery.DiscoveryService;
import com.localdrop.i18n.AppLanguage;
import com.localdrop.i18n.I18n;
import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.transfer.RecentlyReceivedItem;
import com.localdrop.transfer.TransferClient;
import com.localdrop.transfer.ProgressUpdateThrottle;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class MainController {
    private static final int MAX_SERVICE_STARTUP_RECOVERY_ATTEMPTS = 3;

    private enum ReceiveActivity {
        READY,
        UNAVAILABLE,
        RECEIVING_FROM,
        LAST_RECEIVED
    }

    private enum ReceiveAvailability {
        STARTING,
        READY,
        BUSY,
        UNAVAILABLE
    }

    private enum DiscoveryAvailability {
        STARTING,
        RUNNING,
        UNAVAILABLE
    }

    private static final class ActiveTransferBatch {
        private final String id = UUID.randomUUID().toString();
        private final List<TransferQueueItem> items;

        private ActiveTransferBatch(List<TransferQueueItem> items) {
            this.items = List.copyOf(items);
        }
    }

    private final Logger logger = LogService.getLogger(MainController.class);
    private final ConfigService configService;
    private final AppConfig config;
    private final String deviceName;
    private final DiagnosticsService diagnosticsService;
    private final ObservableList<DeviceInfo> devices = FXCollections.observableArrayList();
    private final ObservableList<TransferQueueItem> queueItems = FXCollections.observableArrayList();
    private final ObservableList<RecentlyReceivedItem> recentItems = FXCollections.observableArrayList();
    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "localdrop-background");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean serviceStartupScheduled = new AtomicBoolean(false);
    private final AtomicInteger serviceStartupRecoveryAttempts = new AtomicInteger();
    private final AtomicLong receiveAvailabilityRevision = new AtomicLong();
    private final Object serviceLifecycleLock = new Object();
    private final I18n i18n;
    private final MainView view = new MainView(devices, queueItems, recentItems);
    private final TransferClient transferClient;
    private final Map<String, Long> busyVisibilitySince = new HashMap<>();

    private Stage stage;
    private volatile TransferServer transferServer;
    private volatile DiscoveryService discoveryService;
    private List<DeviceInfo> latestDiscoverySnapshot = List.of();
    private volatile boolean transferInProgress;
    private ActiveTransferBatch activeTransferBatch;
    private ReceiveActivity receiveActivity = ReceiveActivity.READY;
    private String receiveActivityArgument;
    private ReceiveAvailability receiveAvailability = ReceiveAvailability.STARTING;
    private volatile ReceiveAvailability lastReportedReceiveAvailability = ReceiveAvailability.STARTING;
    private DiscoveryAvailability discoveryAvailability = DiscoveryAvailability.STARTING;

    public MainController(ConfigService configService, AppConfig config, String deviceName) {
        this.configService = configService;
        this.config = config;
        this.deviceName = deviceName;
        this.i18n = new I18n(AppLanguage.fromCode(config.getLanguage()));
        this.diagnosticsService = new DiagnosticsService(config.getDeviceId(), deviceName, ProtocolConstants.DEVICE_TYPE_WINDOWS);
        this.transferClient = new TransferClient(diagnosticsService);
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
        view.updateNetworkLabel(i18n.text("status.localNetwork"));
        updateReceiveActivityLabel();
        renderReceiveAvailability();
        renderDiscoveryAvailability();
    }

    /** Starts local network listeners without delaying the JavaFX application thread. */
    public void startServicesAsync() {
        if (shutdown.get() || !serviceStartupScheduled.compareAndSet(false, true)) {
            return;
        }

        runOnUiThread(() -> {
            if (transferServer == null || !transferServer.isRunning()) {
                setReceiveAvailability(ReceiveAvailability.STARTING);
            }
            if (discoveryService == null || !discoveryService.isRunning()) {
                setDiscoveryAvailability(DiscoveryAvailability.STARTING);
            }
        });

        try {
            backgroundExecutor.submit(() -> {
                try {
                    startServices();
                } finally {
                    serviceStartupScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            serviceStartupScheduled.set(false);
            // Shutdown won the race before the background task was accepted.
        }
    }

    private void startServices() {
        if (shutdown.get()) {
            return;
        }

        TransferServer activeTransferServer = transferServer;
        boolean receiveAvailable = activeTransferServer != null && activeTransferServer.isRunning();
        if (!receiveAvailable) {
            TransferServer candidateTransferServer = new TransferServer(
                configService::getReceiveFolder,
                config.getDeviceId(),
                deviceName,
                ProtocolConstants.DEVICE_TYPE_WINDOWS,
                new TransferServer.Listener() {
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
                Platform.runLater(() -> {
                    setReceiveActivity(ReceiveActivity.READY, null);
                    refreshReceiveAvailabilityAsync();
                    if (discoveryService != null) {
                        discoveryService.refreshNow();
                    }
                });
            }

            @Override
            public void onReceivingFrom(String senderDeviceName) {
                Platform.runLater(() -> {
                    setReceiveActivity(ReceiveActivity.RECEIVING_FROM, senderDeviceName);
                    setReceiveAvailability(ReceiveAvailability.BUSY);
                    if (discoveryService != null) {
                        discoveryService.refreshNow();
                    }
                });
            }
                }, diagnosticsService);

            try {
                candidateTransferServer.start();
                if (!registerTransferServer(candidateTransferServer)) {
                    candidateTransferServer.stop();
                    return;
                }
                activeTransferServer = candidateTransferServer;
                receiveAvailable = true;
                runOnUiThread(() -> {
                    setReceiveActivity(ReceiveActivity.READY, null);
                    refreshReceiveAvailabilityAsync();
                });
            } catch (IOException exception) {
                candidateTransferServer.stop();
                logger.severe("Unable to start receive service: " + exception.getMessage());
                diagnosticsService.setTransferServerStatus("ERROR", ProtocolConstants.ERROR_TRANSFER_PORT_UNAVAILABLE);
                runOnUiThread(() -> {
                    setReceiveActivity(ReceiveActivity.UNAVAILABLE, null);
                    setReceiveAvailability(ReceiveAvailability.UNAVAILABLE);
                    view.updateInlineError(i18n.format("errors.receiveService", exception.getMessage()));
                });
            }
        }

        if (shutdown.get()) {
            return;
        }

        int advertisedTransferPort = receiveAvailable
            ? activeTransferServer.getBoundPort()
            : ProtocolConstants.DEFAULT_TRANSFER_PORT;
        DiscoveryService activeDiscoveryService = discoveryService;
        boolean discoveryAvailable = activeDiscoveryService != null && activeDiscoveryService.isRunning();
        if (discoveryAvailable) {
            activeDiscoveryService.updateTransferPort(advertisedTransferPort);
            activeDiscoveryService.retryRecovery();
        } else {
            DiscoveryService candidateDiscoveryService = new DiscoveryService(
                config.getDeviceId(),
                deviceName,
                ProtocolConstants.DEVICE_TYPE_WINDOWS,
                advertisedTransferPort,
                this::resolveLocalDiscoveryStatus,
                snapshot -> Platform.runLater(() -> applyDeviceSnapshot(snapshot)),
                diagnosticsService
            );

            try {
                candidateDiscoveryService.start();
                if (!registerDiscoveryService(candidateDiscoveryService)) {
                    candidateDiscoveryService.stop();
                    return;
                }
                activeDiscoveryService = candidateDiscoveryService;
                discoveryAvailable = true;
            } catch (IOException exception) {
                candidateDiscoveryService.stop();
                logger.severe("Unable to start device discovery: " + exception.getMessage());
                diagnosticsService.setDiscoveryStatus("ERROR", ProtocolConstants.DIAGNOSTIC_DISCOVERY_SOCKET_ERROR);
                runOnUiThread(() -> {
                    setDiscoveryAvailability(DiscoveryAvailability.UNAVAILABLE);
                    view.updateInlineError(i18n.format("errors.discoveryService", exception.getMessage()));
                });
            }
        }

        if (discoveryAvailable) {
            runOnUiThread(() -> setDiscoveryAvailability(DiscoveryAvailability.RUNNING));
        }
        if (receiveAvailable && discoveryAvailable) {
            serviceStartupRecoveryAttempts.set(0);
        } else {
            scheduleServiceStartupRecovery();
        }
    }

    private boolean registerTransferServer(TransferServer candidate) {
        synchronized (serviceLifecycleLock) {
            if (shutdown.get()) {
                return false;
            }
            transferServer = candidate;
            return true;
        }
    }

    private boolean registerDiscoveryService(DiscoveryService candidate) {
        synchronized (serviceLifecycleLock) {
            if (shutdown.get()) {
                return false;
            }
            discoveryService = candidate;
            return true;
        }
    }

    private void refreshNetworkServices() {
        DiscoveryService activeDiscoveryService = discoveryService;
        if (activeDiscoveryService != null) {
            activeDiscoveryService.refreshNow();
        }
        if (transferServer == null || !transferServer.isRunning()
            || activeDiscoveryService == null || !activeDiscoveryService.isRunning()) {
            serviceStartupRecoveryAttempts.set(0);
            startServicesAsync();
        }
    }

    private void scheduleServiceStartupRecovery() {
        int attempt = serviceStartupRecoveryAttempts.incrementAndGet();
        if (attempt > MAX_SERVICE_STARTUP_RECOVERY_ATTEMPTS) {
            logger.warning("Local service startup recovery was exhausted; Refresh can retry it manually.");
            return;
        }

        long delayMillis = 500L * attempt;
        try {
            backgroundExecutor.submit(() -> {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!shutdown.get()) {
                    startServicesAsync();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown won the race before a bounded retry could be scheduled.
        }
    }

    private void runOnUiThread(Runnable action) {
        Platform.runLater(() -> {
            if (!shutdown.get()) {
                action.run();
            }
        });
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        DiscoveryService discoveryServiceToStop;
        TransferServer transferServerToStop;
        synchronized (serviceLifecycleLock) {
            discoveryServiceToStop = discoveryService;
            discoveryService = null;
            transferServerToStop = transferServer;
            transferServer = null;
        }
        if (discoveryServiceToStop != null) {
            discoveryServiceToStop.stop();
        }
        if (transferServerToStop != null) {
            transferServerToStop.stop();
        }
        backgroundExecutor.shutdownNow();
    }

    private void wireUi() {
        view.setRemoveQueueItemAction(this::removeQueueItem);

        view.getRefreshButton().setOnAction(event -> {
            refreshNetworkServices();
        });
        view.getDiagnosticsButton().setOnAction(event -> Dialogs.showDiagnostics(
            stage,
            i18n,
            () -> diagnosticsService.formatSnapshot(i18n),
            this::refreshNetworkServices
        ));
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
            .filter(TransferQueueItem::isEligibleForNewTransfer)
            .toList();

        if (selectedDevice == null || pendingItems.isEmpty() || transferInProgress) {
            return;
        }

        DiscoveryService.SendTargetResolution resolution = discoveryService == null
            ? new DiscoveryService.SendTargetResolution(null, ProtocolConstants.DIAGNOSTIC_TRANSFER_CLIENT_NOT_STARTED, "Discovery is not running.")
            : discoveryService.resolveSendTarget(selectedDevice.getDeviceId());
        if (!resolution.canSend()) {
            view.updateInlineError(resolveSendTargetMessage(resolution));
            updateSendButtonState();
            return;
        }

        ActiveTransferBatch batch = reserveTransferBatch(pendingItems);
        if (batch == null) {
            return;
        }
        transferInProgress = true;
        view.updateInlineError("");
        updateSendButtonState();

        try {
            backgroundExecutor.submit(() -> transferClient.sendFiles(
                resolution.device(),
                config.getDeviceId(),
                deviceName,
                ProtocolConstants.DEVICE_TYPE_WINDOWS,
                pendingItems,
                new TransferClient.Listener() {
                    private final ProgressUpdateThrottle progressThrottle = new ProgressUpdateThrottle();

                    @Override
                    public void onItemStatusChanged(TransferQueueItem item, TransferStatus status, String message) {
                        if (status == TransferStatus.SENDING) {
                            progressThrottle.reset();
                        }
                        Platform.runLater(() -> {
                            if (!isCurrentBatchItem(batch, item)) {
                                return;
                            }
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
                        if (!progressThrottle.shouldPublish(progress)) {
                            return;
                        }
                        Platform.runLater(() -> {
                            if (isCurrentBatchItem(batch, item) && item.updateProgressIfSending(progress)) {
                                view.refreshQueue();
                            }
                        });
                    }

                    @Override
                    public void onItemAcknowledged(TransferQueueItem item) {
                        Platform.runLater(() -> {
                            if (!isCurrentBatchItem(batch, item)) {
                                return;
                            }
                            item.release(batch.id);
                            queueItems.remove(item);
                            view.refreshQueue();
                        });
                    }

                    @Override
                    public void onTransferIssue(String targetDeviceName, String details) {
                        Platform.runLater(() -> {
                            if (activeTransferBatch == batch) {
                                view.updateInlineError(i18n.format("errors.sendTo", targetDeviceName, details));
                            }
                        });
                    }

                    @Override
                    public void onReceiverRejected(String reason) {
                        Platform.runLater(() -> {
                            if (activeTransferBatch == batch) {
                                view.updateInlineError(
                                    reason == null || reason.isBlank() ? i18n.text("errors.receiverRejected") : reason
                                );
                            }
                        });
                    }

                    @Override
                    public void onSessionFinishUnconfirmed(String targetDeviceName, String details) {
                        Platform.runLater(() -> {
                            if (activeTransferBatch == batch) {
                                view.updateInlineError(i18n.format("errors.sessionFinishUnconfirmed", targetDeviceName));
                            }
                        });
                    }

                    @Override
                    public void onTransferFinished() {
                        Platform.runLater(() -> finishTransferBatch(batch));
                    }
                }
            ));
        } catch (RejectedExecutionException exception) {
            releaseUnstartedTransferBatch(batch);
            view.updateInlineError(i18n.text("errors.sendUnavailable"));
        }
    }

    private void clearQueue() {
        queueItems.removeIf(TransferQueueItem::canRemove);
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
            refreshReceiveAvailabilityAsync();
            if (discoveryService != null) {
                discoveryService.refreshNow();
            }
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
        latestDiscoverySnapshot = List.copyOf(snapshot);
        long now = System.currentTimeMillis();
        Map<String, DeviceInfo> currentlyVisibleById = new HashMap<>();
        for (DeviceInfo device : devices) {
            currentlyVisibleById.put(device.getDeviceId(), device);
        }

        busyVisibilitySince.keySet().removeIf(deviceId -> latestDiscoverySnapshot.stream().noneMatch(device -> device.getDeviceId().equals(deviceId)));
        List<DeviceInfo> visibleDevices = latestDiscoverySnapshot.stream()
            .filter(device -> shouldDisplayInMainList(device, currentlyVisibleById.containsKey(device.getDeviceId()), now))
            .sorted(Comparator.comparing(DeviceInfo::getDeviceName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        diagnosticsService.setMainListDevicesCount(visibleDevices.size());

        if (isSameDeviceSnapshot(visibleDevices)) {
            updateSendButtonState();
            return;
        }

        DeviceInfo selectedDevice = view.getDeviceListView().getSelectionModel().getSelectedItem();
        String selectedDeviceId = selectedDevice == null ? null : selectedDevice.getDeviceId();

        devices.setAll(visibleDevices);
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

    private boolean shouldDisplayInMainList(DeviceInfo device, boolean wasVisible, long now) {
        if (isLiveAndReady(device, now)) {
            busyVisibilitySince.remove(device.getDeviceId());
            return true;
        }

        if (ProtocolConstants.STATUS_BUSY.equalsIgnoreCase(device.getStatus()) && wasVisible && isWithinDisplayGrace(device, now)) {
            long startedAt = busyVisibilitySince.computeIfAbsent(device.getDeviceId(), ignored -> {
                scheduleDeviceListReevaluation(ProtocolConstants.MAIN_LIST_NOT_READY_GRACE_MS + 150);
                return now;
            });
            return now - startedAt <= ProtocolConstants.MAIN_LIST_NOT_READY_GRACE_MS;
        }

        busyVisibilitySince.remove(device.getDeviceId());
        return false;
    }

    private boolean isLiveAndReady(DeviceInfo device, long now) {
        long age = now - device.getLastSeenAt();
        if (age > ProtocolConstants.DISCOVERY_DEVICE_TIMEOUT_MILLIS) {
            return false;
        }
        return device.getStatus() == null
            || device.getStatus().isBlank()
            || ProtocolConstants.STATUS_READY.equalsIgnoreCase(device.getStatus())
            || ProtocolConstants.STATUS_READY_COMPAT.equalsIgnoreCase(device.getStatus());
    }

    private boolean isWithinDisplayGrace(DeviceInfo device, long now) {
        return now - device.getLastSeenAt()
            <= ProtocolConstants.DISCOVERY_DEVICE_TIMEOUT_MILLIS + ProtocolConstants.MAIN_LIST_EXPIRE_GRACE_MS;
    }

    private boolean isSameDeviceSnapshot(List<DeviceInfo> snapshot) {
        if (devices.size() != snapshot.size()) {
            return false;
        }

        List<DeviceInfo> currentSnapshot = devices.stream()
            .sorted(Comparator.comparing(DeviceInfo::getDeviceId))
            .toList();
        List<DeviceInfo> nextSnapshot = snapshot.stream()
            .sorted(Comparator.comparing(DeviceInfo::getDeviceId))
            .toList();

        for (int index = 0; index < currentSnapshot.size(); index++) {
            DeviceInfo current = currentSnapshot.get(index);
            DeviceInfo next = nextSnapshot.get(index);
            if (!Objects.equals(current.getDeviceId(), next.getDeviceId())
                || !Objects.equals(current.getDeviceName(), next.getDeviceName())
                || !Objects.equals(current.getDeviceType(), next.getDeviceType())
                || !Objects.equals(current.getStatus(), next.getStatus())
                || !Objects.equals(current.getHostAddress(), next.getHostAddress())
                || current.getTcpPort() != next.getTcpPort()) {
                return false;
            }
        }
        return true;
    }

    private void changeLanguage(AppLanguage language) {
        AppLanguage previousLanguage = i18n.getLanguage();
        try {
            configService.updateLanguage(language);
        } catch (IOException exception) {
            logger.warning("Failed to save language preference: " + exception.getMessage());
            view.setLanguageSelection(previousLanguage);
            return;
        }

        i18n.setLanguage(language);
        view.setI18n(i18n);
        view.setLanguageSelection(language);
        view.updateNetworkLabel(i18n.text("status.localNetwork"));
        updateReceiveActivityLabel();
        renderReceiveAvailability();
        renderDiscoveryAvailability();
        updateSendButtonState();
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

    private void refreshReceiveAvailabilityAsync() {
        try {
            backgroundExecutor.submit(this::resolveLocalDiscoveryStatus);
        } catch (RejectedExecutionException ignored) {
            // Shutdown won the race before the availability check was accepted.
        }
    }

    private ReceiveAvailability mapReceiveAvailability(String status) {
        if (ProtocolConstants.STATUS_BUSY.equalsIgnoreCase(status)) {
            return ReceiveAvailability.BUSY;
        }
        if (ProtocolConstants.STATUS_READY.equalsIgnoreCase(status)
            || ProtocolConstants.STATUS_READY_COMPAT.equalsIgnoreCase(status)) {
            return ReceiveAvailability.READY;
        }
        return ReceiveAvailability.UNAVAILABLE;
    }

    private void setReceiveAvailability(ReceiveAvailability availability) {
        receiveAvailabilityRevision.incrementAndGet();
        lastReportedReceiveAvailability = availability;
        receiveAvailability = availability;
        renderReceiveAvailability();
    }

    private void renderReceiveAvailability() {
        switch (receiveAvailability) {
            case STARTING -> view.updateReceiveAvailability(
                i18n.text("receiving.availability.starting"),
                i18n.text("receiving.chip.starting"),
                "starting"
            );
            case READY -> view.updateReceiveAvailability(
                i18n.text("receiving.availability.ready"),
                i18n.text("receiving.chip.ready"),
                "ready"
            );
            case BUSY -> view.updateReceiveAvailability(
                i18n.text("receiving.availability.busy"),
                i18n.text("receiving.chip.busy"),
                "busy"
            );
            case UNAVAILABLE -> view.updateReceiveAvailability(
                i18n.text("receiving.availability.unavailable"),
                i18n.text("receiving.chip.unavailable"),
                "unavailable"
            );
        }
    }

    private void setDiscoveryAvailability(DiscoveryAvailability availability) {
        discoveryAvailability = availability;
        renderDiscoveryAvailability();
    }

    private void renderDiscoveryAvailability() {
        String message = switch (discoveryAvailability) {
            case STARTING -> i18n.text("status.discoveryStarting");
            case RUNNING -> i18n.text("status.discoveryEnabled");
            case UNAVAILABLE -> i18n.text("status.discoveryUnavailable");
        };
        view.updateDiscoveryStatus(message);
    }

    private void updateSendButtonState() {
        DeviceInfo selectedDevice = view.getDeviceListView().getSelectionModel().getSelectedItem();
        long pendingCount = queueItems.stream()
            .filter(TransferQueueItem::isEligibleForNewTransfer)
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
            boolean canSend = discoveryService != null && discoveryService.resolveSendTarget(selectedDevice.getDeviceId()).canSend();
            disabled = pendingCount == 0 || !canSend;
        }
        view.updateSendButton(buttonText, disabled);
    }

    private ActiveTransferBatch reserveTransferBatch(List<TransferQueueItem> items) {
        if (activeTransferBatch != null) {
            return null;
        }
        ActiveTransferBatch batch = new ActiveTransferBatch(items);
        for (TransferQueueItem item : batch.items) {
            if (!item.reserve(batch.id)) {
                for (TransferQueueItem reservedItem : batch.items) {
                    reservedItem.release(batch.id);
                }
                return null;
            }
        }
        activeTransferBatch = batch;
        view.refreshQueue();
        return batch;
    }

    private boolean isCurrentBatchItem(ActiveTransferBatch batch, TransferQueueItem item) {
        return activeTransferBatch == batch
            && batch.items.contains(item)
            && queueItems.contains(item)
            && item.isReservedBy(batch.id);
    }

    private void releaseUnstartedTransferBatch(ActiveTransferBatch batch) {
        if (activeTransferBatch != batch) {
            return;
        }
        for (TransferQueueItem item : batch.items) {
            item.release(batch.id);
        }
        activeTransferBatch = null;
        transferInProgress = false;
        view.refreshQueue();
        updateSendButtonState();
    }

    private void finishTransferBatch(ActiveTransferBatch batch) {
        if (activeTransferBatch != batch) {
            return;
        }
        for (TransferQueueItem item : batch.items) {
            if (!item.isReservedBy(batch.id)) {
                continue;
            }
            if (item.getStatus() == TransferStatus.SENDING) {
                item.setStatus(TransferStatus.DELIVERY_UNKNOWN);
                item.setMessage("");
                item.setProgress(0);
            }
            item.release(batch.id);
        }
        activeTransferBatch = null;
        transferInProgress = false;
        view.refreshQueue();
        updateSendButtonState();
    }

    private String resolveLocalDiscoveryStatus() {
        String status;
        if (transferServer == null || !transferServer.isRunning()) {
            status = ProtocolConstants.STATUS_TRANSFER_PORT_UNAVAILABLE;
        } else if (transferServer.isBusy()) {
            status = ProtocolConstants.STATUS_BUSY;
        } else {
            Path receiveFolder = configService.getReceiveFolder();
            if (receiveFolder == null) {
                status = ProtocolConstants.STATUS_RECEIVE_FOLDER_NOT_SELECTED;
            } else {
                try {
                    Files.createDirectories(receiveFolder);
                    status = Files.isWritable(receiveFolder)
                        ? ProtocolConstants.STATUS_READY
                        : ProtocolConstants.STATUS_RECEIVE_FOLDER_NOT_WRITABLE;
                } catch (IOException exception) {
                    status = ProtocolConstants.STATUS_RECEIVE_FOLDER_NOT_WRITABLE;
                }
            }
        }
        publishReceiveAvailability(status);
        return status;
    }

    private void publishReceiveAvailability(String status) {
        ReceiveAvailability nextAvailability = mapReceiveAvailability(status);
        if (lastReportedReceiveAvailability == nextAvailability) {
            return;
        }
        lastReportedReceiveAvailability = nextAvailability;
        long revision = receiveAvailabilityRevision.incrementAndGet();
        runOnUiThread(() -> {
            if (receiveAvailabilityRevision.get() == revision) {
                receiveAvailability = nextAvailability;
                renderReceiveAvailability();
            }
        });
    }

    private void scheduleDeviceListReevaluation(long delayMillis) {
        backgroundExecutor.submit(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!shutdown.get()) {
                Platform.runLater(() -> applyDeviceSnapshot(latestDiscoverySnapshot));
            }
        });
    }

    private String resolveSendTargetMessage(DiscoveryService.SendTargetResolution resolution) {
        if (resolution == null || resolution.errorCode() == null) {
            return i18n.text("errors.receiverRejected");
        }
        return switch (resolution.errorCode()) {
            case ProtocolConstants.DIAGNOSTIC_DEVICE_NOT_FOUND,
                 ProtocolConstants.DIAGNOSTIC_DEVICE_NOT_LIVE,
                 ProtocolConstants.DIAGNOSTIC_STALE_DEVICE_ADDRESS -> i18n.text("errors.deviceNotLive");
            case ProtocolConstants.DIAGNOSTIC_DEVICE_NOT_READY -> i18n.text("errors.deviceNotReady");
            case ProtocolConstants.DIAGNOSTIC_TRANSFER_CLIENT_NOT_STARTED -> i18n.text("errors.discoveryNotRunning");
            default -> resolution.message() == null || resolution.message().isBlank()
                ? resolution.errorCode()
                : resolution.message();
        };
    }
}

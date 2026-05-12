package com.localdrop.ui;

import com.localdrop.i18n.AppLanguage;
import com.localdrop.i18n.I18n;
import com.localdrop.protocol.ProtocolConstants;
import com.localdrop.protocol.discovery.DeviceInfo;
import com.localdrop.transfer.RecentlyReceivedItem;
import com.localdrop.transfer.TransferQueueItem;
import com.localdrop.transfer.TransferStatus;
import com.localdrop.util.FileUtils;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

public class MainView {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final BorderPane root = new BorderPane();
    private final ListView<DeviceInfo> deviceListView = new ListView<>();
    private final ListView<TransferQueueItem> queueListView = new ListView<>();
    private final ListView<RecentlyReceivedItem> recentListView = new ListView<>();
    private final VBox dropArea = new VBox(8);
    private final Button refreshButton = new Button();
    private final Button addFilesButton = new Button();
    private final Button addFolderButton = new Button();
    private final Button sendButton = new Button();
    private final Button clearQueueButton = new Button();
    private final Button changeFolderButton = new Button();
    private final Button openFolderButton = new Button();
    private final Button helpButton = new Button();
    private final Button aboutButton = new Button();
    private final ChoiceBox<AppLanguage> languageChoiceBox = new ChoiceBox<>();
    private final Label languageLabel = new Label();
    private final Label headerTitleLabel = new Label();
    private final Label devicesTitleLabel = new Label();
    private final Label devicesHelperLabel = new Label();
    private final Label deviceEmptyLabel = new Label();
    private final Label sendingTitleLabel = new Label();
    private final Label dropTitleLabel = new Label();
    private final Label dropSubtitleLabel = new Label();
    private final Label queueTitleLabel = new Label();
    private final Label queueEmptyLabel = new Label();
    private final Label receivingTitleLabel = new Label();
    private final Label thisComputerLabel = new Label();
    private final Label readyMessageLabel = new Label();
    private final Label onlineChipLabel = new Label();
    private final Label saveFolderTitleLabel = new Label();
    private final Label receiveFolderLabel = new Label();
    private final Label currentDeviceNameLabel = new Label();
    private final Label receivingActivityLabel = new Label();
    private final Label recentTitleLabel = new Label();
    private final Label recentEmptyLabel = new Label();
    private final Label inlineErrorLabel = new Label();
    private final Label networkCaptionLabel = new Label();
    private final Label networkLabel = new Label("Local network");
    private final Label discoveryCaptionLabel = new Label();
    private final Label discoveryLabel = new Label();
    private Consumer<TransferQueueItem> removeQueueItemAction = item -> {
    };

    private I18n i18n;
    private int currentQueueCount;

    public MainView(
        ObservableList<DeviceInfo> devices,
        ObservableList<TransferQueueItem> queueItems,
        ObservableList<RecentlyReceivedItem> recentItems
    ) {
        deviceListView.setItems(devices);
        queueListView.setItems(queueItems);
        recentListView.setItems(recentItems);
        languageChoiceBox.getItems().setAll(AppLanguage.values());

        root.getStyleClass().add("app-root");
        root.setTop(buildHeader());
        root.setCenter(buildContent());
        root.setBottom(buildStatusBar());

        configureLists();
        configureDropArea();
        updateInlineError("");
    }

    public Parent getRoot() {
        return root;
    }

    public Button getRefreshButton() {
        return refreshButton;
    }

    public Button getAddFilesButton() {
        return addFilesButton;
    }

    public Button getAddFolderButton() {
        return addFolderButton;
    }

    public Button getSendButton() {
        return sendButton;
    }

    public Button getClearQueueButton() {
        return clearQueueButton;
    }

    public Button getChangeFolderButton() {
        return changeFolderButton;
    }

    public Button getOpenFolderButton() {
        return openFolderButton;
    }

    public Button getHelpButton() {
        return helpButton;
    }

    public Button getAboutButton() {
        return aboutButton;
    }

    public ChoiceBox<AppLanguage> getLanguageChoiceBox() {
        return languageChoiceBox;
    }

    public VBox getDropArea() {
        return dropArea;
    }

    public ListView<DeviceInfo> getDeviceListView() {
        return deviceListView;
    }

    public void setRemoveQueueItemAction(Consumer<TransferQueueItem> removeQueueItemAction) {
        this.removeQueueItemAction = removeQueueItemAction;
        queueListView.refresh();
    }

    public void setI18n(I18n i18n) {
        this.i18n = i18n;
        applyTexts();
    }

    public void setLanguageSelection(AppLanguage language) {
        languageChoiceBox.setValue(language);
    }

    public void updateQueueCount(int count) {
        currentQueueCount = count;
        if (i18n != null) {
            queueTitleLabel.setText(i18n.format("sending.queueTitle", count));
        }
    }

    public void updateReceiveFolder(Path receiveFolder) {
        receiveFolderLabel.setText(receiveFolder.toString());
    }

    public void updateCurrentDeviceName(String deviceName) {
        currentDeviceNameLabel.setText(deviceName);
    }

    public void updateReceivingActivity(String message) {
        receivingActivityLabel.setText(message);
    }

    public void updateSendButton(String text, boolean disabled) {
        sendButton.setText(text);
        sendButton.setDisable(disabled);
    }

    public void updateInlineError(String message) {
        inlineErrorLabel.setText(message == null ? "" : message);
        inlineErrorLabel.setVisible(message != null && !message.isBlank());
        inlineErrorLabel.setManaged(message != null && !message.isBlank());
    }

    public void updateNetworkLabel(String networkName) {
        networkLabel.setText(networkName);
    }

    public void refreshDevices() {
        deviceListView.refresh();
    }

    public void refreshQueue() {
        queueListView.refresh();
    }

    public void refreshRecent() {
        recentListView.refresh();
    }

    private void applyTexts() {
        headerTitleLabel.setText(i18n.text("app.title"));
        languageLabel.setText(i18n.text("language.label"));
        helpButton.setText(i18n.text("help"));
        aboutButton.setText(i18n.text("about"));

        devicesTitleLabel.setText(i18n.text("devices.title"));
        refreshButton.setText(i18n.text("devices.refresh"));
        devicesHelperLabel.setText(i18n.text("devices.helper"));
        deviceEmptyLabel.setText(i18n.text("devices.empty"));

        sendingTitleLabel.setText(i18n.text("sending.title"));
        dropTitleLabel.setText(i18n.text("sending.dropTitle"));
        dropSubtitleLabel.setText(i18n.text("sending.dropSubtitle"));
        addFilesButton.setText(i18n.text("sending.addFiles"));
        addFolderButton.setText(i18n.text("sending.addFolder"));
        clearQueueButton.setText(i18n.text("sending.clear"));
        queueEmptyLabel.setText(i18n.text("sending.empty"));

        receivingTitleLabel.setText(i18n.text("receiving.title"));
        thisComputerLabel.setText(i18n.text("receiving.thisComputer"));
        readyMessageLabel.setText(i18n.text("receiving.ready"));
        onlineChipLabel.setText(i18n.text("receiving.online"));
        saveFolderTitleLabel.setText(i18n.text("receiving.saveFolder"));
        changeFolderButton.setText(i18n.text("receiving.change"));
        openFolderButton.setText(i18n.text("receiving.open"));

        recentTitleLabel.setText(i18n.text("recent.title"));
        recentEmptyLabel.setText(i18n.text("recent.empty"));

        networkCaptionLabel.setText(i18n.text("status.network"));
        discoveryCaptionLabel.setText(i18n.text("status.discovery"));
        discoveryLabel.setText(i18n.text("status.discoveryEnabled"));

        updateQueueCount(currentQueueCount);
        refreshDevices();
        refreshQueue();
        refreshRecent();
    }

    private Parent buildHeader() {
        HBox header = new HBox(14);
        header.getStyleClass().add("header");
        header.setPadding(new Insets(18, 22, 18, 22));
        header.setAlignment(Pos.CENTER_LEFT);

        ImageView iconView = new ImageView(new Image(Objects.requireNonNull(
            MainView.class.getResourceAsStream("/com/localdrop/icons/app.png")
        )));
        iconView.setFitWidth(28);
        iconView.setFitHeight(28);

        headerTitleLabel.getStyleClass().add("header-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        languageLabel.getStyleClass().add("muted-label");
        languageChoiceBox.getStyleClass().add("language-choice");
        helpButton.getStyleClass().add("secondary-button");
        aboutButton.getStyleClass().add("secondary-button");

        HBox languageBox = new HBox(8, languageLabel, languageChoiceBox);
        languageBox.setAlignment(Pos.CENTER_LEFT);

        header.getChildren().addAll(iconView, headerTitleLabel, spacer, languageBox, helpButton, aboutButton);
        return header;
    }

    private Parent buildContent() {
        HBox content = new HBox(18);
        content.setPadding(new Insets(0, 22, 18, 22));

        VBox leftColumn = buildDevicesColumn();
        VBox centerColumn = buildSendingColumn();
        VBox rightColumn = buildReceivingColumn();

        leftColumn.setPrefWidth(300);
        rightColumn.setPrefWidth(320);
        HBox.setHgrow(centerColumn, Priority.ALWAYS);
        centerColumn.setMaxWidth(Double.MAX_VALUE);

        content.getChildren().addAll(leftColumn, centerColumn, rightColumn);
        return content;
    }

    private VBox buildDevicesColumn() {
        VBox card = card();
        card.getChildren().add(titleRow(devicesTitleLabel));

        refreshButton.getStyleClass().add("secondary-button");

        devicesHelperLabel.getStyleClass().add("helper-text");
        devicesHelperLabel.setWrapText(true);

        deviceEmptyLabel.setWrapText(true);
        deviceListView.setPlaceholder(deviceEmptyLabel);
        VBox.setVgrow(deviceListView, Priority.ALWAYS);

        card.getChildren().addAll(refreshButton, devicesHelperLabel, deviceListView);
        return card;
    }

    private VBox buildSendingColumn() {
        VBox card = card();
        card.getChildren().add(titleRow(sendingTitleLabel));

        HBox buttonRow = new HBox(12, addFilesButton, addFolderButton);
        addFilesButton.getStyleClass().add("secondary-button");
        addFolderButton.getStyleClass().add("secondary-button");

        sendButton.getStyleClass().add("primary-button");
        sendButton.setMaxWidth(Double.MAX_VALUE);
        inlineErrorLabel.getStyleClass().add("inline-error");

        HBox queueTitleRow = new HBox();
        queueTitleRow.setAlignment(Pos.CENTER_LEFT);
        queueTitleRow.getChildren().add(queueTitleLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        clearQueueButton.getStyleClass().add("link-button");
        queueTitleRow.getChildren().addAll(spacer, clearQueueButton);

        queueEmptyLabel.setWrapText(true);
        queueListView.setPlaceholder(queueEmptyLabel);
        VBox.setVgrow(queueListView, Priority.ALWAYS);

        card.getChildren().addAll(dropArea, buttonRow, sendButton, inlineErrorLabel, queueTitleRow, queueListView);
        return card;
    }

    private VBox buildReceivingColumn() {
        VBox column = new VBox(16);

        VBox receiveCard = card();
        receiveCard.getStyleClass().add("receive-card");
        receiveCard.getChildren().add(titleRow(receivingTitleLabel));

        HBox readyHeader = new HBox(12);
        readyHeader.setAlignment(Pos.TOP_LEFT);
        StackPane readyIcon = statusIcon("ME", Color.web("#17a34a"));

        VBox readyText = new VBox(4);
        thisComputerLabel.getStyleClass().add("muted-label");
        currentDeviceNameLabel.getStyleClass().add("card-title");
        readyMessageLabel.getStyleClass().add("ready-text");
        onlineChipLabel.getStyleClass().add("ready-chip");
        readyText.getChildren().addAll(thisComputerLabel, currentDeviceNameLabel, readyMessageLabel, onlineChipLabel);

        readyHeader.getChildren().addAll(readyIcon, readyText);

        saveFolderTitleLabel.getStyleClass().add("muted-label");
        receiveFolderLabel.setWrapText(true);
        receivingActivityLabel.getStyleClass().add("helper-text");

        HBox folderButtons = new HBox(10, changeFolderButton, openFolderButton);
        changeFolderButton.getStyleClass().add("secondary-button");
        openFolderButton.getStyleClass().add("secondary-button");

        receiveCard.getChildren().addAll(readyHeader, saveFolderTitleLabel, receiveFolderLabel, folderButtons, receivingActivityLabel);

        VBox recentCard = card();
        recentCard.getChildren().add(titleRow(recentTitleLabel));
        recentEmptyLabel.setWrapText(true);
        recentListView.setPlaceholder(recentEmptyLabel);
        VBox.setVgrow(recentListView, Priority.ALWAYS);
        recentCard.getChildren().add(recentListView);

        VBox.setVgrow(recentCard, Priority.ALWAYS);
        column.getChildren().addAll(receiveCard, recentCard);
        return column;
    }

    private Parent buildStatusBar() {
        HBox statusBar = new HBox(18);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setPadding(new Insets(14, 22, 16, 22));
        statusBar.setAlignment(Pos.CENTER_LEFT);

        networkCaptionLabel.getStyleClass().add("muted-label");
        discoveryCaptionLabel.getStyleClass().add("muted-label");

        VBox networkBlock = new VBox(2, networkCaptionLabel, networkLabel);
        VBox discoveryBlock = new VBox(2, discoveryCaptionLabel, discoveryLabel);
        statusBar.getChildren().addAll(networkBlock, discoveryBlock);
        return statusBar;
    }

    private void configureLists() {
        deviceListView.getStyleClass().add("clean-list");
        queueListView.getStyleClass().add("clean-list");
        recentListView.getStyleClass().add("clean-list");

        deviceListView.setCellFactory(list -> new DeviceCell());
        queueListView.setCellFactory(list -> new QueueCell());
        recentListView.setCellFactory(list -> new RecentCell());
    }

    private void configureDropArea() {
        dropArea.getStyleClass().add("drop-area");
        dropArea.setAlignment(Pos.CENTER);
        dropArea.setPadding(new Insets(28));
        dropArea.setMinHeight(220);

        StackPane dropBadge = new StackPane();
        dropBadge.getStyleClass().add("drop-badge");
        dropBadge.getChildren().add(new Label("+"));

        dropTitleLabel.getStyleClass().add("drop-title");
        dropSubtitleLabel.getStyleClass().add("helper-text");

        dropArea.getChildren().addAll(dropBadge, dropTitleLabel, dropSubtitleLabel);
    }

    private HBox titleRow(Label titleLabel) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        titleLabel.getStyleClass().add("section-title");
        row.getChildren().add(titleLabel);
        return row;
    }

    private VBox card() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));
        return card;
    }

    private StackPane statusIcon(String text, Color color) {
        Circle circle = new Circle(24, color.deriveColor(0, 1, 1, 0.15));
        circle.setStroke(color.deriveColor(0, 1, 1, 0.45));
        circle.setStrokeWidth(1.2);

        Label label = new Label(text);
        label.getStyleClass().add("icon-text");
        return new StackPane(circle, label);
    }

    private final class DeviceCell extends ListCell<DeviceInfo> {
        @Override
        protected void updateItem(DeviceInfo item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || i18n == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("device-card");
            row.setAlignment(Pos.TOP_LEFT);
            if (isSelected()) {
                row.getStyleClass().add("selected");
            }

            StackPane icon = statusIcon(ProtocolConstants.DEVICE_TYPE_ANDROID.equalsIgnoreCase(item.getDeviceType()) ? "AN" : "PC", Color.web("#2b7cff"));

            VBox textBox = new VBox(6);
            HBox.setHgrow(textBox, Priority.ALWAYS);
            textBox.setMaxWidth(Double.MAX_VALUE);

            Label nameLabel = new Label(item.getDeviceName());
            nameLabel.getStyleClass().add("device-name");
            nameLabel.setWrapText(true);
            nameLabel.setMaxWidth(Double.MAX_VALUE);

            Label onlineLabel = new Label(i18n.text("device.online"));
            onlineLabel.getStyleClass().add("subtle-chip");

            Label statusLabel = new Label("READY".equalsIgnoreCase(item.getStatus())
                ? i18n.text("device.ready")
                : i18n.text("device.online"));
            statusLabel.getStyleClass().add("ready-text");

            textBox.getChildren().addAll(nameLabel, onlineLabel, statusLabel);

            row.getChildren().addAll(icon, textBox);
            setGraphic(row);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }
    }

    private final class QueueCell extends ListCell<TransferQueueItem> {
        @Override
        protected void updateItem(TransferQueueItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || i18n == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("queue-row");
            row.setAlignment(Pos.CENTER_LEFT);

            StackPane icon = statusIcon("FI", Color.web("#7c8ea5"));

            VBox infoBox = new VBox(5);
            Label nameLabel = new Label(item.getDisplayName());
            nameLabel.getStyleClass().add("queue-name");
            nameLabel.setWrapText(true);
            Label sizeLabel = new Label(FileUtils.formatSize(item.getSize()));
            sizeLabel.getStyleClass().add("helper-text");
            infoBox.getChildren().addAll(nameLabel, sizeLabel);

            TransferStatus status = item.getStatus();
            if (item.getMessage() != null && !item.getMessage().isBlank() && status != TransferStatus.SENDING) {
                Label messageLabel = new Label(item.getMessage());
                messageLabel.getStyleClass().add("helper-text");
                messageLabel.setWrapText(true);
                infoBox.getChildren().add(messageLabel);
            }

            VBox statusBox = new VBox(6);
            statusBox.setAlignment(Pos.CENTER_RIGHT);
            Label statusLabel = new Label(i18n.text(status.getTranslationKey()));
            statusLabel.getStyleClass().addAll("status-pill", status.getStyleClass());
            statusBox.getChildren().add(statusLabel);

            if (status == TransferStatus.SENDING) {
                ProgressBar progressBar = new ProgressBar(item.getProgress());
                progressBar.setPrefWidth(180);
                Label progressLabel = new Label((int) Math.round(item.getProgress() * 100) + "%");
                progressLabel.getStyleClass().add("helper-text");
                statusBox.getChildren().addAll(progressBar, progressLabel);
            }

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button removeButton = new Button("x");
            removeButton.getStyleClass().add("icon-button");
            removeButton.setDisable(!item.canRemove());
            removeButton.setOnAction(event -> removeQueueItemAction.accept(item));

            row.getChildren().addAll(icon, infoBox, spacer, statusBox, removeButton);
            setGraphic(row);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }
    }

    private final class RecentCell extends ListCell<RecentlyReceivedItem> {
        @Override
        protected void updateItem(RecentlyReceivedItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || i18n == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(12);
            row.getStyleClass().add("recent-row");
            row.setAlignment(Pos.CENTER_LEFT);

            StackPane icon = new StackPane(new Circle(18, Color.web("#e8efff")), new Label("IN"));
            icon.getStyleClass().add("recent-icon");

            VBox textBox = new VBox(4);
            Label name = new Label(item.name());
            name.getStyleClass().add("queue-name");
            Label meta = new Label(i18n.format("recent.itemMeta", FileUtils.formatSize(item.size()), TIME_FORMAT.format(item.receivedAt())));
            meta.getStyleClass().add("helper-text");
            textBox.getChildren().addAll(name, meta);

            row.getChildren().addAll(icon, textBox);
            setGraphic(row);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }
    }
}

package com.localdrop.ui;

import com.localdrop.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.animation.PauseTransition;

import java.util.function.Supplier;

public final class Dialogs {
    private Dialogs() {
    }

    public static void showHelp(Window owner, I18n i18n) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, i18n.text("dialogs.help.content"), ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle(i18n.text("dialogs.help.title"));
        alert.setHeaderText(i18n.text("dialogs.help.header"));
        alert.showAndWait();
    }

    public static void showAbout(Window owner, I18n i18n) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, i18n.text("dialogs.about.content"), ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle(i18n.text("dialogs.about.title"));
        alert.setHeaderText(i18n.text("dialogs.about.header"));
        alert.showAndWait();
    }

    public static void showDiagnostics(
        Window owner,
        I18n i18n,
        Supplier<String> diagnosticsSupplier,
        Runnable refreshDevicesAction
    ) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(i18n.text("diagnostics.dialog.title"));

        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setPrefColumnCount(100);
        textArea.setPrefRowCount(32);

        Runnable refreshDiagnostics = () -> textArea.setText(diagnosticsSupplier.get());
        refreshDiagnostics.run();

        Button refreshButton = new Button(i18n.text("diagnostics.refresh"));
        Button refreshListButton = new Button(i18n.text("diagnostics.refreshList"));
        Button copyButton = new Button(i18n.text("diagnostics.copy"));
        Button closeButton = new Button(i18n.text("diagnostics.close"));

        refreshButton.getStyleClass().add("secondary-button");
        refreshListButton.getStyleClass().add("secondary-button");
        copyButton.getStyleClass().add("secondary-button");
        closeButton.getStyleClass().add("primary-button");

        refreshButton.setOnAction(event -> refreshDiagnostics.run());
        refreshListButton.setOnAction(event -> {
            refreshDevicesAction.run();
            refreshDiagnostics.run();
            PauseTransition pause = new PauseTransition(Duration.millis(900));
            pause.setOnFinished(ignored -> refreshDiagnostics.run());
            pause.play();
        });
        copyButton.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(textArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        closeButton.setOnAction(event -> stage.close());

        HBox buttons = new HBox(10, refreshButton, refreshListButton, copyButton, closeButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setCenter(textArea);
        root.setBottom(buttons);
        BorderPane.setMargin(buttons, new Insets(12, 0, 0, 0));

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 980, 720);
        stage.setScene(scene);
        stage.show();
    }
}

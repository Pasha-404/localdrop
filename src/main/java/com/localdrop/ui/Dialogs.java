package com.localdrop.ui;

import com.localdrop.i18n.I18n;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

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
}

package com.localdrop;

import com.localdrop.config.AppConfig;
import com.localdrop.config.ConfigService;
import com.localdrop.ui.MainController;
import com.localdrop.ui.TrayService;
import com.localdrop.util.BootstrapDiagnostics;
import com.localdrop.util.LogService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class LocalDropApp extends Application {
    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    private final Logger logger = LogService.getLogger(LocalDropApp.class);
    private MainController controller;
    private TrayService trayService;

    @Override
    public void start(Stage primaryStage) throws Exception {
        try {
            LogService.initialize();

            ConfigService configService = new ConfigService();
            AppConfig config = configService.load();

            controller = new MainController(configService, config, ConfigService.resolveDeviceName());
            controller.attachStage(primaryStage);

            Scene scene = new Scene(controller.getRoot(), config.getWindowWidth(), config.getWindowHeight());
            scene.getStylesheets().add(Objects.requireNonNull(
                LocalDropApp.class.getResource("/com/localdrop/styles.css")
            ).toExternalForm());

            primaryStage.setTitle("LocalDrop");
            primaryStage.getIcons().add(new Image(Objects.requireNonNull(
                LocalDropApp.class.getResourceAsStream("/com/localdrop/icons/app.png")
            )));
            primaryStage.setMinWidth(1200);
            primaryStage.setMinHeight(720);
            primaryStage.setScene(scene);

            Platform.setImplicitExit(false);
            configureTray(primaryStage);
            configureCloseBehavior(primaryStage);

            primaryStage.show();
            controller.startServices();
            logger.info("Application started");
        } catch (Throwable throwable) {
            BootstrapDiagnostics.reportFailure("LocalDrop startup error", throwable);
            requestExit();
        }
    }

    @Override
    public void stop() {
        requestExit();
    }

    static void launchApp(String[] args) {
        launch(args);
    }

    private void configureTray(Stage stage) {
        trayService = new TrayService();
        trayService.install(
            () -> Platform.runLater(() -> {
                if (!stage.isShowing()) {
                    stage.show();
                }
                stage.setIconified(false);
                stage.toFront();
                stage.requestFocus();
            }),
            this::requestExit
        );
    }

    private void configureCloseBehavior(Stage stage) {
        stage.setOnCloseRequest(event -> {
            if (trayService != null && trayService.isInstalled() && !exitRequested.get()) {
                event.consume();
                stage.hide();
            }
        });

        stage.iconifiedProperty().addListener((obs, oldValue, newValue) -> {
            if (Boolean.TRUE.equals(newValue) && trayService != null && trayService.isInstalled() && !exitRequested.get()) {
                Platform.runLater(() -> {
                    stage.hide();
                    stage.setIconified(false);
                });
            }
        });
    }

    private void requestExit() {
        if (!exitRequested.compareAndSet(false, true)) {
            return;
        }

        try {
            if (controller != null) {
                controller.shutdown();
            }
        } finally {
            if (trayService != null) {
                trayService.dispose();
            }
            LogService.shutdown();
            Platform.exit();
        }
    }

}

package com.localdrop.ui;

import com.localdrop.util.LogService;

import javax.imageio.ImageIO;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

public class TrayService {
    private final Logger logger = LogService.getLogger(TrayService.class);
    private TrayIcon trayIcon;
    private boolean installed;

    public boolean install(Runnable onOpen, Runnable onExit) {
        if (!SystemTray.isSupported() || installed) {
            return false;
        }

        try (InputStream stream = TrayService.class.getResourceAsStream("/com/localdrop/icons/app.png")) {
            if (stream == null) {
                logger.warning("Tray icon resource was not found");
                return false;
            }

            BufferedImage image = ImageIO.read(stream);
            PopupMenu menu = new PopupMenu();

            MenuItem openItem = new MenuItem("Open LocalDrop");
            openItem.addActionListener(event -> onOpen.run());
            menu.add(openItem);

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(event -> onExit.run());
            menu.add(exitItem);

            trayIcon = new TrayIcon(image, "LocalDrop", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> onOpen.run());
            SystemTray.getSystemTray().add(trayIcon);
            installed = true;
            logger.info("System tray initialized");
            return true;
        } catch (IOException | java.awt.AWTException exception) {
            logger.warning("Unable to initialize system tray: " + exception.getMessage());
            return false;
        }
    }

    public boolean isInstalled() {
        return installed;
    }

    public void dispose() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
        installed = false;
    }
}

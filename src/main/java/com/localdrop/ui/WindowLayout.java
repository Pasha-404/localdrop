package com.localdrop.ui;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Keeps startup windows inside the visible work area, including on scaled laptop displays. */
public final class WindowLayout {
    private static final double HORIZONTAL_MARGIN = 32;
    private static final double VERTICAL_MARGIN = 48;

    private WindowLayout() {
    }

    public static FittedBounds fitToPrimaryScreen(
        double preferredWidth,
        double preferredHeight,
        double preferredMinWidth,
        double preferredMinHeight
    ) {
        Rectangle2D available = Screen.getPrimary().getVisualBounds();
        double maxWidth = Math.max(1, available.getWidth() - HORIZONTAL_MARGIN);
        double maxHeight = Math.max(1, available.getHeight() - VERTICAL_MARGIN);
        double minWidth = Math.min(preferredMinWidth, maxWidth);
        double minHeight = Math.min(preferredMinHeight, maxHeight);
        double width = clamp(preferredWidth, minWidth, maxWidth);
        double height = clamp(preferredHeight, minHeight, maxHeight);

        return new FittedBounds(
            width,
            height,
            minWidth,
            minHeight,
            available.getMinX() + (available.getWidth() - width) / 2,
            available.getMinY() + (available.getHeight() - height) / 2
        );
    }

    public static void apply(Stage stage, FittedBounds bounds) {
        stage.setMinWidth(bounds.minWidth());
        stage.setMinHeight(bounds.minHeight());
        stage.setWidth(bounds.width());
        stage.setHeight(bounds.height());
        stage.setX(bounds.x());
        stage.setY(bounds.y());
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    public record FittedBounds(double width, double height, double minWidth, double minHeight, double x, double y) {
    }
}

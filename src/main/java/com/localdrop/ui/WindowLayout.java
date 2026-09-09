package com.localdrop.ui;

import com.localdrop.config.AppConfig;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;

/** Keeps startup windows inside a usable screen work area and retains normal bounds separately from maximized state. */
public final class WindowLayout {
    private static final double OUTER_MARGIN = 16;
    private static final double FIRST_RUN_WIDTH_FACTOR = 0.82;
    private static final double FIRST_RUN_HEIGHT_FACTOR = 0.86;
    private static final double FIRST_RUN_WIDTH_MIN = 1120;
    private static final double FIRST_RUN_WIDTH_MAX = 1280;
    private static final double FIRST_RUN_HEIGHT_MIN = 640;
    private static final double FIRST_RUN_HEIGHT_MAX = 800;

    private WindowLayout() {
    }

    public static FittedBounds fitForApplication(AppConfig config) {
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        return fitToAvailableBounds(
            config.getWindowWidth(),
            config.getWindowHeight(),
            720,
            420,
            !config.isWindowSizeConfigured(),
            config.isWindowPositionConfigured(),
            config.getWindowX(),
            config.getWindowY(),
            screens
        );
    }

    public static FittedBounds fitToPrimaryScreen(
        double preferredWidth,
        double preferredHeight,
        double preferredMinWidth,
        double preferredMinHeight
    ) {
        return fitToAvailableBounds(
            preferredWidth,
            preferredHeight,
            preferredMinWidth,
            preferredMinHeight,
            false,
            false,
            0,
            0,
            List.of(Screen.getPrimary().getVisualBounds())
        );
    }

    static FittedBounds fitToAvailableBounds(
        double preferredWidth,
        double preferredHeight,
        double preferredMinWidth,
        double preferredMinHeight,
        boolean firstRun,
        boolean hasSavedPosition,
        double savedX,
        double savedY,
        List<Rectangle2D> screens
    ) {
        if (screens == null || screens.isEmpty()) {
            throw new IllegalArgumentException("At least one screen is required.");
        }

        Rectangle2D screen = selectScreen(preferredWidth, preferredHeight, hasSavedPosition, savedX, savedY, screens);
        Rectangle2D usable = inset(screen, OUTER_MARGIN);
        double maxWidth = Math.max(1, usable.getWidth());
        double maxHeight = Math.max(1, usable.getHeight());
        double minWidth = Math.min(Math.max(1, preferredMinWidth), maxWidth);
        double minHeight = Math.min(Math.max(1, preferredMinHeight), maxHeight);
        double requestedWidth = firstRun
            ? clamp(screen.getWidth() * FIRST_RUN_WIDTH_FACTOR, FIRST_RUN_WIDTH_MIN, FIRST_RUN_WIDTH_MAX)
            : preferredWidth;
        double requestedHeight = firstRun
            ? clamp(screen.getHeight() * FIRST_RUN_HEIGHT_FACTOR, FIRST_RUN_HEIGHT_MIN, FIRST_RUN_HEIGHT_MAX)
            : preferredHeight;
        double width = clamp(finiteOr(requestedWidth, minWidth), minWidth, maxWidth);
        double height = clamp(finiteOr(requestedHeight, minHeight), minHeight, maxHeight);
        double x = hasSavedPosition && Double.isFinite(savedX)
            ? clamp(savedX, usable.getMinX(), usable.getMaxX() - width)
            : usable.getMinX() + (usable.getWidth() - width) / 2;
        double y = hasSavedPosition && Double.isFinite(savedY)
            ? clamp(savedY, usable.getMinY(), usable.getMaxY() - height)
            : usable.getMinY() + (usable.getHeight() - height) / 2;

        return new FittedBounds(width, height, minWidth, minHeight, x, y);
    }

    public static void apply(Stage stage, FittedBounds bounds) {
        stage.setMinWidth(bounds.minWidth());
        stage.setMinHeight(bounds.minHeight());
        stage.setWidth(bounds.width());
        stage.setHeight(bounds.height());
        stage.setX(bounds.x());
        stage.setY(bounds.y());
    }

    public static WindowStateTracker trackNormalBounds(Stage stage, FittedBounds initialBounds) {
        return new WindowStateTracker(stage, initialBounds);
    }

    private static Rectangle2D selectScreen(
        double width,
        double height,
        boolean hasSavedPosition,
        double x,
        double y,
        List<Rectangle2D> screens
    ) {
        if (!hasSavedPosition || !Double.isFinite(x) || !Double.isFinite(y)
            || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            return screens.getFirst();
        }
        Rectangle2D savedBounds = new Rectangle2D(x, y, width, height);
        Rectangle2D bestScreen = screens.getFirst();
        double greatestIntersection = 0;
        for (Rectangle2D screen : screens) {
            double intersection = intersectionArea(savedBounds, screen);
            if (intersection > greatestIntersection) {
                greatestIntersection = intersection;
                bestScreen = screen;
            }
        }
        return bestScreen;
    }

    private static double intersectionArea(Rectangle2D first, Rectangle2D second) {
        double width = Math.max(0, Math.min(first.getMaxX(), second.getMaxX()) - Math.max(first.getMinX(), second.getMinX()));
        double height = Math.max(0, Math.min(first.getMaxY(), second.getMaxY()) - Math.max(first.getMinY(), second.getMinY()));
        return width * height;
    }

    private static Rectangle2D inset(Rectangle2D bounds, double margin) {
        double horizontalMargin = Math.min(margin, Math.max(0, (bounds.getWidth() - 1) / 2));
        double verticalMargin = Math.min(margin, Math.max(0, (bounds.getHeight() - 1) / 2));
        return new Rectangle2D(
            bounds.getMinX() + horizontalMargin,
            bounds.getMinY() + verticalMargin,
            Math.max(1, bounds.getWidth() - horizontalMargin * 2),
            Math.max(1, bounds.getHeight() - verticalMargin * 2)
        );
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    public record FittedBounds(double width, double height, double minWidth, double minHeight, double x, double y) {
    }

    public record WindowState(double x, double y, double width, double height, boolean maximized) {
    }

    public static final class WindowStateTracker {
        private final Stage stage;
        private double normalX;
        private double normalY;
        private double normalWidth;
        private double normalHeight;

        private WindowStateTracker(Stage stage, FittedBounds initialBounds) {
            this.stage = stage;
            normalX = initialBounds.x();
            normalY = initialBounds.y();
            normalWidth = initialBounds.width();
            normalHeight = initialBounds.height();
            ChangeListener<Number> listener = (observable, oldValue, newValue) -> captureNormalBounds();
            stage.xProperty().addListener(listener);
            stage.yProperty().addListener(listener);
            stage.widthProperty().addListener(listener);
            stage.heightProperty().addListener(listener);
            stage.maximizedProperty().addListener((observable, oldValue, newValue) -> captureNormalBounds());
            stage.iconifiedProperty().addListener((observable, oldValue, newValue) -> captureNormalBounds());
        }

        public WindowState snapshot() {
            captureNormalBounds();
            return new WindowState(normalX, normalY, normalWidth, normalHeight, stage.isMaximized());
        }

        private void captureNormalBounds() {
            if (stage.isMaximized() || stage.isIconified()
                || !Double.isFinite(stage.getX()) || !Double.isFinite(stage.getY())
                || !Double.isFinite(stage.getWidth()) || !Double.isFinite(stage.getHeight())
                || stage.getWidth() <= 0 || stage.getHeight() <= 0) {
                return;
            }
            normalX = stage.getX();
            normalY = stage.getY();
            normalWidth = stage.getWidth();
            normalHeight = stage.getHeight();
        }
    }
}

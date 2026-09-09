package com.localdrop.ui;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowLayoutTest {
    @Test
    void firstRunUsesComfortableCapsInsteadOfAnAlmostFullScreenWindow() {
        WindowLayout.FittedBounds bounds = WindowLayout.fitToAvailableBounds(
            1400, 800, 720, 420, true, false, 0, 0,
            List.of(new Rectangle2D(0, 0, 1920, 1080))
        );

        assertEquals(1280, bounds.width());
        assertEquals(800, bounds.height());
        assertEquals(320, bounds.x());
        assertEquals(140, bounds.y());
    }

    @Test
    void constrainedWorkAreaNeverForcesTheStageOutsideTheScreen() {
        Rectangle2D screen = new Rectangle2D(0, 0, 1093, 614);
        WindowLayout.FittedBounds bounds = WindowLayout.fitToAvailableBounds(
            1400, 800, 720, 420, true, false, 0, 0, List.of(screen)
        );

        assertTrue(bounds.width() <= screen.getWidth() - 32);
        assertTrue(bounds.height() <= screen.getHeight() - 32);
        assertTrue(bounds.x() >= 16 && bounds.y() >= 16);
    }

    @Test
    void savedBoundsPreferTheScreenWithTheGreatestIntersection() {
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);
        Rectangle2D leftScreen = new Rectangle2D(-1280, 0, 1280, 1024);
        WindowLayout.FittedBounds bounds = WindowLayout.fitToAvailableBounds(
            900, 700, 720, 420, false, true, -1200, 100, List.of(primary, leftScreen)
        );

        assertTrue(bounds.x() < 0);
        assertTrue(bounds.x() >= leftScreen.getMinX() + 16);
        assertTrue(bounds.x() + bounds.width() <= leftScreen.getMaxX() - 16);
    }

    @Test
    void disconnectedMonitorBoundsAreClampedOntoTheCurrentScreen() {
        Rectangle2D primary = new Rectangle2D(0, 0, 1366, 768);
        WindowLayout.FittedBounds bounds = WindowLayout.fitToAvailableBounds(
            1200, 700, 720, 420, false, true, 5000, 5000, List.of(primary)
        );

        assertTrue(bounds.x() >= primary.getMinX() + 16);
        assertTrue(bounds.y() >= primary.getMinY() + 16);
        assertTrue(bounds.x() + bounds.width() <= primary.getMaxX() - 16);
        assertTrue(bounds.y() + bounds.height() <= primary.getMaxY() - 16);
    }
}

package org.firefly.screenrec.ui.overlay;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Shared helpers for the borderless overlay windows (control bar, region
 * selector, countdown).
 *
 * <p>These windows must not steal focus from the app being recorded, and on
 * Windows they benefit from being excluded from capture when possible.
 */
public final class OverlayWindow {

    private OverlayWindow() {
    }

    /** Applies the standard stylesheet chain to an overlay scene. */
    public static void applyStylesheets(Scene scene) {
        applyStylesheets(scene, "dark");
    }

    /** Applies the standard stylesheet chain with an explicit theme id. */
    public static void applyStylesheets(Scene scene, String themeId) {
        new org.firefly.screenrec.ui.theme.ThemeManager(themeId).applyCurrent(scene);
    }

    /**
     * Configures a stage so it floats above everything without activating.
     * Focus stealing is the main cause of the recorded app losing its cursor.
     */
    public static void makeFloating(Stage stage) {
        stage.setAlwaysOnTop(true);
        if (PlatformUtils.isMac()) {
            // JavaFX cannot express NSWindow levels directly; the utility window
            // style is the closest portable approximation.
            stage.initStyle(javafx.stage.StageStyle.UTILITY);
        }
        try {
            stage.setFullScreenExitKeyCombination(
                    javafx.scene.input.KeyCombination.NO_MATCH);
        } catch (RuntimeException ignored) {
            // not a full-screen stage
        }
    }

    /** Allows the window to receive mouse events (default state). */
    public static void makeClickThroughFalse(Stage stage) {
        stage.getScene().getRoot().setMouseTransparent(false);
    }

    /** Prevents this window from being shown in the taskbar (Windows/macOS). */
    public static void hideFromTaskbar(Stage stage) {
        try {
            // UTILITY style windows are excluded from the taskbar on Windows.
            if (PlatformUtils.isWindows()) {
                stage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
            }
        } catch (RuntimeException e) {
            Log.debug("Could not hide overlay from taskbar: " + e.getMessage());
        }
    }

    /** Returns true when the given window is one of our overlays. */
    public static boolean isOverlay(Window window) {
        return window instanceof Stage s && s.getStyle() != javafx.stage.StageStyle.DECORATED;
    }
}

package org.firefly.screenrec.core.screen;

import org.firefly.screenrec.util.Log;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.robot.Robot;
import javafx.scene.SnapshotParameters;
import javafx.scene.paint.Color;

/**
 * Captures still images of the desktop.
 *
 * <p>Uses the JavaFX {@link Robot}, which is the only cross-platform screen
 * grab available without a native dependency. Must be called on the FX thread.
 *
 * <p>The primary use is the region-selector backdrop; recording itself is always
 * done by FFmpeg.
 */
public final class ScreenCaptureService {

    private ScreenCaptureService() {
    }

    /**
     * Grabs the whole of a display.
     *
     * @param screen the display to capture, in JavaFX logical coordinates
     * @return the captured image, or null when capture is unavailable
     */
    public static WritableImage captureScreen(ScreenInfo screen) {
        return capture(screen.visualBounds());
    }

    /** Grabs an arbitrary rectangle in virtual-desktop coordinates. */
    public static WritableImage capture(Rectangle2D bounds) {
        try {
            Robot robot = new Robot();
            WritableImage image = robot.getScreenCapture(null, bounds);
            if (image == null || image.getWidth() < 2) {
                Log.warn("Screen capture returned an empty image");
                return null;
            }
            return image;
        } catch (Exception e) {
            Log.warn("Screen capture failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Converts an image to a data-less thumbnail by scaling it down.
     *
     * @param source    the source image
     * @param maxWidth  target maximum width
     * @param maxHeight target maximum height
     */
    public static WritableImage thumbnail(Image source, double maxWidth, double maxHeight) {
        if (source == null) {
            return null;
        }
        double scale = Math.min(maxWidth / source.getWidth(), maxHeight / source.getHeight());
        double w = Math.max(1, source.getWidth() * scale);
        double h = Math.max(1, source.getHeight() * scale);

        ImageView view = new ImageView(source);
        view.setFitWidth(w);
        view.setFitHeight(h);
        view.setPreserveRatio(false);
        view.setSmooth(true);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return view.snapshot(params, new WritableImage((int) w, (int) h));
    }
}

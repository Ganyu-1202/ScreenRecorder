package org.firefly.screenrec.core.screen;

import org.firefly.screenrec.util.Log;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Enumerates the available displays.
 *
 * <p>This is the one place in {@code core} that touches JavaFX, because the
 * JDK itself has no display enumeration API. The resulting {@link ScreenInfo}
 * records are plain data and keep the rest of the core JavaFX-free.
 */
public final class MonitorService {

    private MonitorService() {
    }

    /**
     * Returns all displays, sorted with the primary display first and then by
     * position (left to right, top to bottom) so indices are stable.
     */
    public static List<ScreenInfo> monitors() {
        List<ScreenInfo> result = new ArrayList<>();
        try {
            List<Screen> screens = new ArrayList<>(Screen.getScreens());
            screens.sort(Comparator
                    .comparing((Screen s) -> !isPrimary(s))
                    .thenComparingDouble(s -> s.getBounds().getMinX())
                    .thenComparingDouble(s -> s.getBounds().getMinY()));

            var nativeBounds = WindowsDisplayBounds.read();
            int index = 0;
            for (Screen screen : screens) {
                Rectangle2D visual = screen.getBounds();
                Rectangle2D dpi = nativeBounds.size() == screens.size() ? nativeBounds.get(index) : safeDpiBounds(screen, visual);

                double scaleX = visual.getWidth() == 0 ? 1 : dpi.getWidth() / visual.getWidth();
                double scaleY = visual.getHeight() == 0 ? 1 : dpi.getHeight() / visual.getHeight();

                boolean primary = isPrimary(screen);
                String id = "screen" + index;
                String name = (primary ? "Primary Display" : "Display " + (index + 1))
                        + " · " + (int) Math.round(dpi.getWidth()) + "×" + (int) Math.round(dpi.getHeight());

                result.add(new ScreenInfo(
                        index,
                        id,
                        name,
                        (int) Math.round(dpi.getMinX()),
                        (int) Math.round(dpi.getMinY()),
                        (int) Math.round(dpi.getWidth()),
                        (int) Math.round(dpi.getHeight()),
                        visual.getWidth(),
                        visual.getHeight(),
                        scaleX,
                        scaleY,
                        primary, visual.getMinX(), visual.getMinY()));
                index++;
            }
        } catch (Throwable t) {
            Log.warn("Display enumeration failed: " + t.getMessage());
        }

        if (result.isEmpty()) {
            // Fallback: assume a single 1080p display so the app stays usable.
            result.add(new ScreenInfo(0, "screen0", "Primary Display · 1920×1080",
                    0, 0, 1920, 1080, 1920, 1080, 1, 1, true));
        }
        return result;
    }

    /** The bounds of the entire virtual desktop (all displays combined). */
    public static Rectangle2D virtualBounds() {
        try {
            List<Screen> screens = Screen.getScreens();
            if (screens.isEmpty()) {
                return new Rectangle2D(0, 0, 1920, 1080);
            }
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (Screen s : screens) {
                Rectangle2D b = s.getBounds();
                minX = Math.min(minX, b.getMinX());
                minY = Math.min(minY, b.getMinY());
                maxX = Math.max(maxX, b.getMaxX());
                maxY = Math.max(maxY, b.getMaxY());
            }
            return new Rectangle2D(minX, minY, maxX - minX, maxY - minY);
        } catch (Throwable t) {
            return new Rectangle2D(0, 0, 1920, 1080);
        }
    }

    /** The primary display, or the first available. */
    public static ScreenInfo primary() {
        List<ScreenInfo> list = monitors();
        for (ScreenInfo s : list) {
            if (s.primary()) {
                return s;
            }
        }
        return list.get(0);
    }

    /**
     * Returns the display that contains the given virtual-desktop point,
     * or the primary display when the point lies outside every screen.
     */
    public static ScreenInfo monitorAt(double x, double y) {
        List<ScreenInfo> list = monitors();
        for (ScreenInfo s : list) {
            if (x >= s.x() && x < s.x() + s.width() && y >= s.y() && y < s.y() + s.height()) {
                return s;
            }
        }
        return primary();
    }

    private static boolean isPrimary(Screen screen) {
        try {
            return screen.equals(Screen.getPrimary());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Reads the physical DPI-aware bounds when the platform reports them,
     * falling back to the logical bounds.
     */
    private static Rectangle2D safeDpiBounds(Screen screen, Rectangle2D visual) {
        try {
            Rectangle2D dpi = screen.getBounds();
            if (screen.getOutputScaleX() > 0 && screen.getOutputScaleY() > 0) {
                double sx = screen.getOutputScaleX();
                double sy = screen.getOutputScaleY();
                if (sx != 1.0 || sy != 1.0) {
                    return new Rectangle2D(dpi.getMinX() * sx, dpi.getMinY() * sy,
                            dpi.getWidth() * sx, dpi.getHeight() * sy);
                }
            }
            return dpi;
        } catch (Throwable t) {
            return visual;
        }
    }
}

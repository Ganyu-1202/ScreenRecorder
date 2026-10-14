package org.firefly.screenrec.core.screen;

import java.util.List;

/**
 * Small helpers for working with the list of detected displays.
 */
public final class Screens {

    private Screens() {
    }

    /**
     * Returns the primary display, or the first entry when none is flagged as
     * primary (which can happen on unusual multi-monitor configurations).
     *
     * @throws IllegalArgumentException when the list is empty
     */
    public static ScreenInfo primaryOrFirst(List<ScreenInfo> screens) {
        if (screens == null || screens.isEmpty()) {
            throw new IllegalArgumentException("No displays available");
        }
        for (ScreenInfo s : screens) {
            if (s.primary()) {
                return s;
            }
        }
        return screens.get(0);
    }

    /** Returns the primary display, or null when no displays were detected. */
    public static ScreenInfo primary(List<ScreenInfo> screens) {
        if (screens == null || screens.isEmpty()) {
            return null;
        }
        for (ScreenInfo s : screens) {
            if (s.primary()) {
                return s;
            }
        }
        return screens.get(0);
    }

    /** Finds a display by its stable id ({@code screen0}, {@code screen1}, …). */
    public static ScreenInfo byId(List<ScreenInfo> screens, String id) {
        if (screens == null || id == null) {
            return null;
        }
        for (ScreenInfo s : screens) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** Sum of all display areas — used to size a full-desktop capture. */
    public static ScreenInfo union(List<ScreenInfo> screens) {
        if (screens == null || screens.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (ScreenInfo s : screens) {
            minX = Math.min(minX, s.x());
            minY = Math.min(minY, s.y());
            maxX = Math.max(maxX, s.x() + s.width());
            maxY = Math.max(maxY, s.y() + s.height());
        }
        return new ScreenInfo(
                -1, "all", "All displays",
                minX, minY, maxX - minX, maxY - minY,
                maxX - minX, maxY - minY,
                1.0, 1.0, false);
    }
}

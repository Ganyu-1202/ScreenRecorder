package org.firefly.screenrec.core.recorder;

import java.util.Objects;

/**
 * Describes <em>what</em> to capture: a whole monitor, a rectangle in virtual
 * desktop coordinates, or a single window.
 */
public final class CaptureSource {

    public enum Kind {
        /** Entire virtual desktop (all monitors stitched together). */
        FULL_SCREEN,
        /** A single monitor. */
        MONITOR,
        /** A user-drawn rectangle. */
        REGION,
        /** A single application window (Windows: by title, Linux: by window id). */
        WINDOW
    }

    private final Kind kind;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final String monitorId;
    private final String monitorName;
    private final String windowTitle;
    private final double scaleFactor;

    private CaptureSource(Kind kind, int x, int y, int width, int height,
                          String monitorId, String monitorName, String windowTitle,
                          double scaleFactor) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.monitorId = monitorId;
        this.monitorName = monitorName;
        this.windowTitle = windowTitle;
        this.scaleFactor = scaleFactor <= 0 ? 1.0 : scaleFactor;
    }

    public static CaptureSource fullScreen(int x, int y, int w, int h) {
        return new CaptureSource(Kind.FULL_SCREEN, x, y, w, h, null, "All displays", null, 1.0);
    }

    public static CaptureSource monitor(String id, String name, int x, int y, int w, int h,
                                        double scaleFactor) {
        return new CaptureSource(Kind.MONITOR, x, y, w, h, id, name, null, scaleFactor);
    }

    public static CaptureSource region(int x, int y, int w, int h) {
        return new CaptureSource(Kind.REGION, x, y, w, h, null, "Custom region", null, 1.0);
    }

    public static CaptureSource window(String title, int x, int y, int w, int h) {
        return new CaptureSource(Kind.WINDOW, x, y, w, h, null, title, title, 1.0);
    }

    public Kind kind() {
        return kind;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String monitorId() {
        return monitorId;
    }

    public String monitorName() {
        return monitorName;
    }

    public String windowTitle() {
        return windowTitle;
    }

    /** UI scale factor of the target display (2.0 on a HiDPI screen). */
    public double scaleFactor() {
        return scaleFactor;
    }

    /** Even-numbered width, required by H.264 chroma subsampling. */
    public int evenWidth() {
        return width % 2 == 0 ? width : width - 1;
    }

    /** Even-numbered height, required by H.264 chroma subsampling. */
    public int evenHeight() {
        return height % 2 == 0 ? height : height - 1;
    }

    public String displayLabel() {
        return switch (kind) {
            case FULL_SCREEN -> "Full Screen";
            case MONITOR -> monitorName == null ? "Display" : monitorName;
            case REGION -> "Region " + width + "×" + height;
            case WINDOW -> windowTitle == null ? "Window" : windowTitle;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CaptureSource other)) {
            return false;
        }
        return kind == other.kind && x == other.x && y == other.y
                && width == other.width && height == other.height
                && Objects.equals(monitorId, other.monitorId)
                && Objects.equals(windowTitle, other.windowTitle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, x, y, width, height, monitorId, windowTitle);
    }

    @Override
    public String toString() {
        return kind + "[" + width + "×" + height + "@" + x + "," + y + "]";
    }
}

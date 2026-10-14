package org.firefly.screenrec.core.screen;

/**
 * Describes one display: its index, bounds in virtual-desktop coordinates and
 * its UI scale factor.
 *
 * @param index        zero-based display ordinal, used as the FFmpeg monitor index
 * @param id           stable identifier ("screen0", "screen1", …)
 * @param name         human friendly label such as "Display 1 (1920×1080)"
 * @param x            left edge in virtual desktop coordinates
 * @param y            top edge in virtual desktop coordinates
 * @param width        width in physical pixels as reported to FFmpeg
 * @param height       height in physical pixels as reported to FFmpeg
 * @param visualWidth  width in JavaFX logical pixels
 * @param visualHeight height in JavaFX logical pixels
 * @param scaleX       horizontal UI scale factor (2.0 on a 200% HiDPI display)
 * @param scaleY       vertical UI scale factor
 * @param primary      true for the primary display
 */
public record ScreenInfo(
        int index,
        String id,
        String name,
        int x,
        int y,
        int width,
        int height,
        double visualWidth,
        double visualHeight,
        double scaleX,
        double scaleY,
        boolean primary, double visualX, double visualY) {
    public ScreenInfo(int index, String id, String name, int x, int y, int width, int height,
                      double visualWidth, double visualHeight, double scaleX, double scaleY, boolean primary) {
        this(index,id,name,x,y,width,height,visualWidth,visualHeight,scaleX,scaleY,primary,x,y);
    }

    /** Approximate pixel area, used for sorting displays by size. */
    public long area() {
        return (long) width * height;
    }

    /**
     * Display bounds in virtual-desktop coordinates, in physical pixels.
     *
     * <p>Physical pixels are the right unit for FFmpeg capture; use
     * {@link #visualBounds()} for positioning JavaFX windows.
     */
    public javafx.geometry.Rectangle2D bounds() {
        return new javafx.geometry.Rectangle2D(x, y, width, height);
    }

    /**
     * Display bounds in JavaFX logical coordinates, in the same space as
     * {@code javafx.stage.Screen}. Use for placing overlay windows.
     */
    public javafx.geometry.Rectangle2D visualBounds() {
        return new javafx.geometry.Rectangle2D(visualX, visualY, visualWidth, visualHeight);
    }

    public String resolutionLabel() {
        return width + "×" + height;
    }

    /** Compact label for UI cards, e.g. {@code Display 1 · 1920×1080}. */
    public String shortLabel() {
        return (primary ? "Primary · " : "") + resolutionLabel();
    }

    @Override
    public String toString() {
        return name;
    }
}

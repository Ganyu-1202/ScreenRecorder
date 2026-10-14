package org.firefly.screenrec.core.recorder;

import java.util.List;

/**
 * Named quality presets. Each preset derives a target bitrate from the
 * resolution and frame rate, keeping the UI simple while producing sane files.
 */
public enum VideoQuality {

    LOW("Low", 0.045, 26),
    MEDIUM("Medium", 0.075, 23),
    HIGH("High", 0.11, 20),
    ULTRA("Ultra", 0.16, 17),
    LOSSLESS("Near-lossless", 0.30, 14);

    private final String displayName;
    /** Bits per pixel per frame factor used to derive bitrate. */
    private final double bitsPerPixel;
    /** Constant-quality value (lower = better) used by hardware encoders. */
    private final int cq;

    VideoQuality(String displayName, double bitsPerPixel, int cq) {
        this.displayName = displayName;
        this.bitsPerPixel = bitsPerPixel;
        this.cq = cq;
    }

    public String displayName() {
        return displayName;
    }

    public double bitsPerPixel() {
        return bitsPerPixel;
    }

    /** Constant quality value for NVENC/QSV/AMF/VideoToolbox style rate control. */
    public int cq() {
        return cq;
    }

    public boolean isLossless() {
        return this == LOSSLESS;
    }

    /**
     * Derives a target bitrate in bits per second for the given frame geometry.
     *
     * @param width  output width in pixels
     * @param height output height in pixels
     * @param fps    frames per second
     */
    public int targetBitrateBps(int width, int height, int fps) {
        long pixels = (long) Math.max(1, width) * Math.max(1, height);
        double bps = pixels * fps * bitsPerPixel;
        // Clamp to a sane band for consumer capture.
        long clamped = Math.max(500_000L, Math.min((long) bps, 200_000_000L));
        return (int) clamped;
    }

    public static List<VideoQuality> all() {
        return List.of(values());
    }

    @Override
    public String toString() {
        return displayName;
    }
}

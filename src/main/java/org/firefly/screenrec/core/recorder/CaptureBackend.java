package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.PlatformUtils;

import java.util.List;
import java.util.Locale;

/**
 * Screen capture input backends. Each maps to an FFmpeg input device/filter.
 */
public enum CaptureBackend {

    /** Windows: DXGI Desktop Duplication via the {@code ddagrab} filter (GPU, fastest). */
    DDAGrab("ddagrab", "Desktop Duplication (GPU)", true, "windows"),
    /** Windows: GDI screen grabber; slower but very compatible. */
    GDIgrab("gdigrab", "GDI Capture (compatible)", false, "windows"),
    /** macOS: AVFoundation capture. */
    AVFoundation("avfoundation", "AVFoundation", true, "macos"),
    /** Linux: X11 screen grabber. */
    X11grab("x11grab", "X11 Capture", false, "linux"),
    Portal("portal", "Wayland Portal (PipeWire)", false, "linux"),
    /** Linux: KMS/DRM capture (requires privileges). */
    KMSgrab("kmsgrab", "KMS/DRM Capture", true, "linux"),
    /** Not determined yet. */
    AUTO("", "Automatic", true, "any");

    private final String ffmpegName;
    private final String displayName;
    private final boolean hardware;
    private final String platform;

    CaptureBackend(String ffmpegName, String displayName, boolean hardware, String platform) {
        this.ffmpegName = ffmpegName;
        this.displayName = displayName;
        this.hardware = hardware;
        this.platform = platform;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isHardware() {
        return hardware;
    }

    /** Platform key this backend belongs to ({@code windows}, {@code macos}, {@code linux}). */
    public String platform() {
        return platform;
    }

    /** Preferred capture backends for the current platform, best first. */
    public static List<CaptureBackend> preferredForPlatform() {
        if (PlatformUtils.isWindows()) {
            return List.of(DDAGrab, GDIgrab);
        }
        if (PlatformUtils.isMac()) {
            return List.of(AVFoundation);
        }
        if (PlatformUtils.isLinux()) {
            return PlatformUtils.isWayland() ? List.of(Portal) : List.of(X11grab);
        }
        return List.of();
    }

    public static CaptureBackend fromFfmpegName(String name) {
        if (name == null) {
            return AUTO;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (CaptureBackend b : values()) {
            if (b.ffmpegName.equalsIgnoreCase(n)) {
                return b;
            }
        }
        return AUTO;
    }

    /** Resolves a persisted identifier (enum name or FFmpeg device name). */
    public static CaptureBackend fromId(String id) {
        if (id == null || id.isBlank()) {
            return AUTO;
        }
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (CaptureBackend b : values()) {
            if (b.name().equalsIgnoreCase(s) || b.ffmpegName.equalsIgnoreCase(s)) {
                return b;
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

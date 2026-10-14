package org.firefly.screenrec.core.recorder;

import java.util.List;

/**
 * Output container / muxer formats supported by the recorder.
 */
public enum OutputFormat {

    MP4("mp4", "MP4 (H.264/H.265)", "mp4", true),
    MKV("matroska", "Matroska (crash-safe)", "mkv", true),
    MOV("mov", "QuickTime (MOV)", "mov", true),
    WEBM("webm", "WebM (VP8/VP9)", "webm", false),
    GIF("gif", "Animated GIF", "gif", false);

    private final String ffmpegMuxer;
    private final String displayName;
    private final String extension;
    private final boolean supportsAudio;

    OutputFormat(String ffmpegMuxer, String displayName, String extension, boolean supportsAudio) {
        this.ffmpegMuxer = ffmpegMuxer;
        this.displayName = displayName;
        this.extension = extension;
        this.supportsAudio = supportsAudio;
    }

    public String ffmpegMuxer() {
        return ffmpegMuxer;
    }

    public String displayName() {
        return displayName;
    }

    public String extension() {
        return extension;
    }

    public boolean supportsAudio() {
        return supportsAudio;
    }

    /** Formats safe to interrupt without corrupting the file. */
    public boolean isCrashSafe() {
        return this == MKV;
    }

    public static OutputFormat fromExtension(String ext) {
        if (ext == null) {
            return MP4;
        }
        String e = ext.toLowerCase(java.util.Locale.ROOT).replace(".", "");
        for (OutputFormat f : values()) {
            if (f.extension.equals(e)) {
                return f;
            }
        }
        return MP4;
    }

    /**
     * Resolves a persisted identifier (enum name, extension or muxer), defaulting
     * to MP4 so a corrupt settings file can never disable recording.
     */
    public static OutputFormat fromId(String id) {
        if (id == null || id.isBlank()) {
            return MP4;
        }
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        for (OutputFormat f : values()) {
            if (f.name().equalsIgnoreCase(s) || f.extension.equals(s)
                    || f.ffmpegMuxer.equals(s)) {
                return f;
            }
        }
        return MP4;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

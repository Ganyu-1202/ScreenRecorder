package org.firefly.screenrec.core.media;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Metadata about a recorded media file.
 *
 * @param path         file location
 * @param fileName     display name including extension
 * @param sizeBytes    file size on disk
 * @param durationMs   duration in milliseconds, or -1 when unknown
 * @param width        video width, or 0 when unknown
 * @param height       video height, or 0 when unknown
 * @param fps          frame rate, or 0 when unknown
 * @param videoCodec   codec name such as {@code h264}
 * @param audioCodec   codec name such as {@code aac}, or null when silent
 * @param created      file creation / last-modified time
 */
public record MediaInfo(
        Path path,
        String fileName,
        long sizeBytes,
        long durationMs,
        int width,
        int height,
        double fps,
        String videoCodec,
        String audioCodec,
        Instant created) {

    public boolean hasAudio() {
        return audioCodec != null && !audioCodec.isBlank() && !audioCodec.equals("none");
    }

    public boolean hasVideo() {
        return width > 0 && height > 0;
    }

    public String resolutionLabel() {
        return hasVideo() ? width + "×" + height : "—";
    }

    public String durationLabel() {
        if (durationMs < 0) {
            return "—";
        }
        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    public MediaInfo withDuration(long ms) {
        return new MediaInfo(path, fileName, sizeBytes, ms, width, height, fps,
                videoCodec, audioCodec, created);
    }

    /** Duration in milliseconds; -1 when unknown. Alias of {@link #durationMs()}. */
    public long durationMillis() {
        return durationMs;
    }

    /**
     * File modification time, used for sorting the library newest-first.
     * Never null for files that still exist.
     */
    public Instant lastModified() {
        if (created != null) {
            return created;
        }
        try {
            return java.nio.file.Files.getLastModifiedTime(path).toInstant();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Path of the cached thumbnail for this clip, whether or not it exists yet.
     * Thumbnails live next to the recording in a hidden {@code .thumbnails} folder.
     */
    public Path thumbnailPath() {
        Path parent = path.getParent();
        if (parent == null) {
            return null;
        }
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return parent.resolve(".thumbnails").resolve(base + ".jpg");
    }
}

package org.firefly.screenrec.core.recorder;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Live statistics parsed from FFmpeg's {@code -progress pipe:1} key/value stream.
 */
public final class RecordingStats {

    private volatile long frame;
    private volatile double fps;
    private volatile long totalSizeBytes;
    private volatile double bitrateKbps;
    private volatile long outTimeMicros;
    private volatile int droppedFrames;
    private volatile double speed;

    public long frame() {
        return frame;
    }

    public double fps() {
        return fps;
    }

    public long totalSizeBytes() {
        return totalSizeBytes;
    }

    public double bitrateKbps() {
        return bitrateKbps;
    }

    /** Output duration in milliseconds. */
    public long durationMillis() {
        return outTimeMicros / 1000L;
    }

    public long outTimeMicros() {
        return outTimeMicros;
    }

    public int droppedFrames() {
        return droppedFrames;
    }

    /** Encoding speed multiplier; 1.0 means real time. */
    public double speed() {
        return speed;
    }

    /** A shareable, immutable snapshot. */
    public Snapshot snapshot() {
        return new Snapshot(frame, fps, totalSizeBytes, bitrateKbps, outTimeMicros,
                droppedFrames, speed);
    }

    public record Snapshot(long frame, double fps, long totalSizeBytes, double bitrateKbps,
                           long outTimeMicros, int droppedFrames, double speed) {
        public long durationMillis() {
            return outTimeMicros / 1000L;
        }
    }

    /**
     * Accumulates progress key/value lines and exposes the merged state.
     * FFmpeg emits blocks terminated by {@code progress=continue|end}.
     */
    public static final class Parser {

        private final Map<String, String> pending = new HashMap<>();
        private final RecordingStats stats = new RecordingStats();

        public RecordingStats stats() {
            return stats;
        }

        /**
         * Feeds one line of FFmpeg progress output.
         *
         * @return true when a complete progress block was consumed
         */
        public boolean accept(String line) {
            if (line == null) {
                return false;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            pending.put(key, value);

            if (key.equals("progress")) {
                apply(pending);
                pending.clear();
                return true;
            }
            return false;
        }

        private void apply(Map<String, String> block) {
            stats.frame = parseLong(block.get("frame"), stats.frame);
            stats.fps = parseDouble(block.get("fps"), stats.fps);
            stats.totalSizeBytes = parseLong(block.get("total_size"), stats.totalSizeBytes);
            stats.bitrateKbps = parseDouble(stripUnit(block.get("bitrate")), stats.bitrateKbps);
            long micros = parseMicros(block.get("out_time_us"), parseTimeString(block.get("out_time")));
            if (micros >= 0) {
                stats.outTimeMicros = micros;
            }
            stats.droppedFrames = (int) parseLong(block.get("drop_frames"), stats.droppedFrames);
            stats.speed = parseSpeed(block.get("speed"), stats.speed);
        }

        private static String stripUnit(String v) {
            return v == null ? null : v.replaceAll("[^0-9.\\-]", "");
        }

        /**
         * Resolves the output timestamp, preferring the exact microsecond value
         * and falling back to the HH:MM:SS form. Returns -1 when unknown.
         */
        private static long parseMicros(String us, long fallbackMicros) {
            if (us != null) {
                try {
                    return Long.parseLong(us.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the time-string form
                }
            }
            return fallbackMicros;
        }

        /** Parses {@code HH:MM:SS.micro} into microseconds, or -1. */
        private static long parseTimeString(String t) {
            if (t == null || t.equalsIgnoreCase("N/A")) {
                return -1;
            }
            try {
                String[] parts = t.trim().split(":");
                if (parts.length != 3) {
                    return -1;
                }
                double hours = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                double total = hours * 3600 + minutes * 60 + seconds;
                return (long) (total * 1_000_000);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static long parseLong(String v, long fallback) {
            if (v == null) {
                return fallback;
            }
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static double parseDouble(String v, double fallback) {
            if (v == null) {
                return fallback;
            }
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static double parseSpeed(String v, double fallback) {
            if (v == null) {
                return fallback;
            }
            String cleaned = v.replace("x", "").trim();
            return parseDouble(cleaned, fallback);
        }
    }
}

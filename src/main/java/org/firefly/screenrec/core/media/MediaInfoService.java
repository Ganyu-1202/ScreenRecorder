package org.firefly.screenrec.core.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads media metadata with {@code ffprobe} and extracts poster thumbnails with
 * {@code ffmpeg}. Every method degrades gracefully when the tools are missing,
 * falling back to filesystem-only information.
 */
public final class MediaInfoService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Extensions the library considers to be recordings. */
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".mkv", ".mov", ".webm", ".avi", ".gif", ".flv", ".m4v", ".ts");

    /** Extensions ffprobe can inspect. */
    private static final List<String> PROBE_EXTENSIONS =
            List.of(".mp4", ".mkv", ".mov", ".webm", ".avi", ".flv", ".m4v", ".ts", ".mp3", ".wav");

    private final Path ffmpeg;
    private final Path ffprobe;

    public MediaInfoService(Path ffmpeg, Path ffprobe) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
    }

    /** True when ffprobe is available for detailed inspection. */
    public boolean canProbe() {
        return ffprobe != null && Files.isRegularFile(ffprobe);
    }

    public boolean canExtractThumbnails() {
        return ffmpeg != null && Files.isRegularFile(ffmpeg);
    }

    // ------------------------------------------------------------------
    // Probing
    // ------------------------------------------------------------------

    /**
     * Inspects a file. Falls back to size/date-only metadata when ffprobe is
     * unavailable or the file is not probeable.
     */
    public MediaInfo inspect(Path file) {
        String name = file.getFileName().toString();
        long size = sizeOf(file);
        Instant created = createdTime(file);

        if (!canProbe() || !isProbeable(name)) {
            return new MediaInfo(file, name, size, -1, 0, 0, 0, null, null, created);
        }

        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(
                    ffprobe.toString(),
                    "-v", "error",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    file.toString()), 30_000);

            if (!r.ok() || r.stdout().isBlank()) {
                Log.debug("ffprobe returned nothing for " + name);
                return new MediaInfo(file, name, size, -1, 0, 0, 0, null, null, created);
            }

            JsonNode root = MAPPER.readTree(r.stdout());
            return parse(root, file, name, size, created);
        } catch (Exception e) {
            Log.warn("ffprobe failed for " + name + ": " + e.getMessage());
            return new MediaInfo(file, name, size, -1, 0, 0, 0, null, null, created);
        }
    }

    private MediaInfo parse(JsonNode root, Path file, String name, long size, Instant created) {
        long durationMs = -1;
        JsonNode format = root.path("format");
        if (format.hasNonNull("duration")) {
            durationMs = (long) (format.get("duration").asDouble() * 1000);
        }

        int width = 0;
        int height = 0;
        double fps = 0;
        String videoCodec = null;
        String audioCodec = null;

        JsonNode streams = root.path("streams");
        if (streams.isArray()) {
            for (JsonNode s : streams) {
                String type = s.path("codec_type").asText("");
                if (type.equals("video") && width == 0) {
                    width = s.path("width").asInt(0);
                    height = s.path("height").asInt(0);
                    videoCodec = s.path("codec_name").asText(null);
                    fps = parseRate(s.path("avg_frame_rate").asText(null));
                    if (fps <= 0) {
                        fps = parseRate(s.path("r_frame_rate").asText(null));
                    }
                    if (durationMs < 0 && s.hasNonNull("duration")) {
                        durationMs = (long) (s.get("duration").asDouble() * 1000);
                    }
                } else if (type.equals("audio") && audioCodec == null) {
                    audioCodec = s.path("codec_name").asText(null);
                }
            }
        }

        return new MediaInfo(file, name, size, durationMs, width, height, fps,
                videoCodec, audioCodec, created);
    }

    /** Parses FFmpeg rational frame rates such as {@code 30000/1001}. */
    private static double parseRate(String rate) {
        if (rate == null || rate.isBlank() || rate.equals("N/A") || rate.equals("0/0")) {
            return 0;
        }
        try {
            int slash = rate.indexOf('/');
            if (slash < 0) {
                return Double.parseDouble(rate);
            }
            double num = Double.parseDouble(rate.substring(0, slash));
            double den = Double.parseDouble(rate.substring(slash + 1));
            return den == 0 ? 0 : num / den;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Library scanning
    // ------------------------------------------------------------------

    /**
     * Lists recording files in a directory, newest first.
     *
     * @param inspectEach when true, each file is probed for full metadata
     */
    public List<MediaInfo> listRecordings(Path directory, boolean inspectEach) {
        List<MediaInfo> result = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isVideo(p.getFileName().toString()))
                    .sorted(Comparator.comparingLong(MediaInfoService::lastModifiedSafe).reversed())
                    .toList();
            for (Path p : files) {
                result.add(inspectEach ? inspect(p) : lightInspect(p));
            }
        } catch (IOException e) {
            Log.warn("Could not list recordings in " + directory + ": " + e.getMessage());
        }
        return result;
    }

    /** Cheap metadata using filesystem information only. */
    public MediaInfo lightInspect(Path file) {
        return new MediaInfo(file, file.getFileName().toString(), sizeOf(file), -1,
                0, 0, 0, null, null, createdTime(file));
    }

    // ------------------------------------------------------------------
    // Thumbnails
    // ------------------------------------------------------------------

    /**
     * Extracts a single poster frame to {@code destination}.
     *
     * @param positionSeconds where in the video to sample the frame
     * @param width           output width; height keeps the aspect ratio
     * @return the written path, or empty on failure
     */
    public Optional<Path> extractThumbnail(Path video, Path destination,
                                           double positionSeconds, int width) {
        if (!canExtractThumbnails()) {
            return Optional.empty();
        }
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(destination) && sizeOf(destination) > 0) {
                return Optional.of(destination);
            }

            List<String> cmd = new ArrayList<>(List.of(
                    ffmpeg.toString(),
                    "-hide_banner", "-loglevel", "error",
                    "-ss", String.format(Locale.ROOT, "%.2f", Math.max(0, positionSeconds)),
                    "-i", video.toString(),
                    "-frames:v", "1",
                    "-vf", "scale=" + width + ":-2",
                    "-y",
                    destination.toString()));

            ProcessRunner.Result r = ProcessRunner.run(cmd, 30_000);
            if (r.ok() && Files.exists(destination) && sizeOf(destination) > 0) {
                return Optional.of(destination);
            }
            Log.debug("Thumbnail extraction failed for " + video.getFileName());
            return Optional.empty();
        } catch (Exception e) {
            Log.debug("Thumbnail extraction error: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Extracts a thumbnail at 10% into the clip, or at 1s for short clips. */
    public Optional<Path> extractDefaultThumbnail(Path video, MediaInfo info, Path destination) {
        double position = 1.0;
        if (info != null && info.durationMs() > 0) {
            position = Math.min(info.durationMs() * 0.1 / 1000.0, 10.0);
            position = Math.max(position, 0.5);
        }
        return extractThumbnail(video, destination, position, 480);
    }

    /**
     * Generates (and caches) a thumbnail for a recording at its conventional
     * location. Returns the path when a thumbnail is available, else null.
     *
     * @param info  the clip to preview
     * @param width output width in pixels
     * @param height retained for callers that want a specific aspect box
     */
    public Path generateThumbnail(MediaInfo info, int width, int height) {
        if (info == null || !canExtractThumbnails()) {
            return null;
        }
        Path destination = info.thumbnailPath();
        if (destination == null) {
            return null;
        }
        // extractThumbnail returns the existing file when already cached.
        return extractDefaultThumbnail(info.path(), info, destination).orElse(null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public static boolean isVideo(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean isProbeable(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return PROBE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long lastModifiedSafe(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Instant createdTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }
}

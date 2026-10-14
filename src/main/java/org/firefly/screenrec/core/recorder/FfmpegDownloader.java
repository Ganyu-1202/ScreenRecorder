package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a static FFmpeg build into the app-managed cache when no usable
 * binary was found locally.
 *
 * <p>The archive is stored in {@code ~/.screenrecorder/ffmpeg/<platform>/}.
 * Extraction is hardened against zip-slip, and a partial download is discarded
 * rather than left behind as a broken binary.
 */
public final class FfmpegDownloader {

    /** Progress callback: (bytesDownloaded, totalBytesOrMinusOne). */
    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /** A downloadable archive for one platform. */
    public record Source(String url, String description, long approximateBytes) {
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(15);
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;

    private final HttpClient client;

    public FfmpegDownloader() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Download sources for the current platform, fastest and most reliable
     * first.
     *
     * <p>Ordering matters a lot in practice: GitHub's release CDN is roughly
     * three orders of magnitude faster than gyan.dev from many networks
     * (measured ~23 MB/s vs ~7 KB/s), so the GitHub build is tried first and
     * gyan.dev is kept only as a fallback. The GitHub asset tracks the newest
     * stable FFmpeg release branch and is a fully static GPL build containing
     * every encoder we use (x264, x265, NVENC, AMF, QSV).
     *
     * <p>These are deliberately <em>self-contained</em>: the app never requires
     * FFmpeg to be installed on the machine.
     */
    public static java.util.List<Source> sourcesForPlatform() {
        return switch (PlatformUtils.current()) {
            case WINDOWS -> java.util.List.of(
                    new Source("https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-win64-gpl-9.0.zip",
                            "FFmpeg 9.0 stable GPL (Windows, GitHub CDN)", 185L * 1024 * 1024),
                    new Source("https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n8.1-latest-win64-gpl-8.1.zip",
                            "FFmpeg 8.1 stable GPL (Windows, GitHub CDN)", 184L * 1024 * 1024),
                    new Source("https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
                            "gyan.dev release essentials (Windows)", 110L * 1024 * 1024));
            case MACOS -> java.util.List.of(
                    new Source("https://evermeet.cx/ffmpeg/ffmpeg-7.1.zip",
                            "evermeet.cx (macOS)", 40L * 1024 * 1024));
            case LINUX -> java.util.List.of(
                    new Source("https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz",
                            "johnvansickle static build (Linux)", 40L * 1024 * 1024));
            default -> java.util.List.of();
        };
    }

    /**
     * Downloads and extracts FFmpeg, returning the path to the binary.
     *
     * @param progress receives download progress; may be null
     * @return the extracted ffmpeg path, or empty on failure
     */
    public java.util.Optional<Path> download(ProgressListener progress) {
        var sources = sourcesForPlatform();
        if (sources.isEmpty()) {
            Log.warn("No FFmpeg download source for platform " + PlatformUtils.current());
            return java.util.Optional.empty();
        }
        Path cacheDir = PlatformUtils.ffmpegCacheDir().resolve(PlatformUtils.platformKey());
        if (PlatformUtils.ensureDir(cacheDir).isEmpty()) {
            Log.error("Cannot create FFmpeg cache directory: " + cacheDir);
            return java.util.Optional.empty();
        }

        for (Source source : sources) {
            // A transient network hiccup should not disqualify an entire source,
            // especially when the first mirror is fast and the fallbacks are not.
            int attempts = 2;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    Log.info("Downloading FFmpeg: " + source.description()
                            + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                    Path archive = downloadArchive(source, cacheDir, progress);
                    if (archive == null) {
                        break; // non-retryable server response; try the next source
                    }
                    Path binary = extract(archive, cacheDir);
                    Files.deleteIfExists(archive);
                    if (binary != null) {
                        makeExecutable(binary);
                        Log.info("FFmpeg installed at " + binary);
                        return java.util.Optional.of(binary);
                    }
                    Log.warn("Archive from " + source.description() + " contained no ffmpeg binary");
                    break; // the archive itself is wrong; retrying will not help
                } catch (Exception e) {
                    Log.warn("FFmpeg download failed from " + source.description()
                            + " (attempt " + attempt + "): " + e.getMessage());
                }
            }
        }
        return java.util.Optional.empty();
    }

    private Path downloadArchive(Source source, Path cacheDir, ProgressListener progress)
            throws IOException, InterruptedException {
        Path target = cacheDir.resolve("ffmpeg-download" + extensionFor(source.url()));
        Path partial = cacheDir.resolve(target.getFileName() + ".part");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(source.url()))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "ScreenRecorder/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() / 100 != 2) {
            Log.warn("Download returned HTTP " + response.statusCode());
            return null;
        }

        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        long written = 0;

        try (InputStream in = response.body();
             FileChannel out = FileChannel.open(partial,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            long lastReport = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(java.nio.ByteBuffer.wrap(buffer, 0, read));
                written += read;
                if (written > MAX_ARCHIVE_BYTES) {
                    throw new IOException("Archive exceeds maximum expected size");
                }
                if (progress != null && written - lastReport > 512 * 1024) {
                    progress.onProgress(written, total);
                    lastReport = written;
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }

        if (progress != null) {
            progress.onProgress(written, total > 0 ? total : written);
        }

        // Reject truncated transfers. A short download still produces a file that
        // looks plausible, and unzipping it yields binaries that fail at runtime
        // with confusing corruption errors (a bad ffprobe is easy to miss).
        // Comparing against Content-Length catches it here instead.
        if (total > 0 && written != total) {
            Files.deleteIfExists(partial);
            throw new IOException("Incomplete download: got " + written + " of " + total + " bytes");
        }

        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static String extensionFor(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.xz")) {
            return ".tar.xz";
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return ".tar.gz";
        }
        return ".zip";
    }

    /**
     * Extracts the archive and locates the ffmpeg binary inside it.
     * Handles both .zip (Windows/macOS) and .tar.xz (Linux).
     */
    private Path extract(Path archive, Path cacheDir) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        Path extractionDir = Files.createTempDirectory(cacheDir, "extract");
        try {
            if (name.endsWith(".zip")) {
                unzip(archive, extractionDir);
            } else {
                untarWithSystemTool(archive, extractionDir);
            }
            Path binary = findBinary(extractionDir, "ffmpeg");
            Path ffprobe = findBinary(extractionDir, "ffprobe");

            if (binary == null) {
                return null;
            }

            // Flatten into a stable location.
            Path destDir = cacheDir.resolve("bin");
            Files.createDirectories(destDir);
            String suffix = PlatformUtils.exeSuffix();

            Path destFfmpeg = destDir.resolve("ffmpeg" + suffix);
            Files.copy(binary, destFfmpeg, StandardCopyOption.REPLACE_EXISTING);
            boolean probeOk = false;
            if (ffprobe != null) {
                Path destFfprobe = destDir.resolve("ffprobe" + suffix);
                Files.copy(ffprobe, destFfprobe, StandardCopyOption.REPLACE_EXISTING);
                probeOk = canRun(destFfprobe);
                if (!probeOk) {
                    // A corrupt ffprobe (truncated archive) would silently break
                    // media inspection later, so drop it and keep ffmpeg only.
                    Log.warn("Extracted ffprobe is not runnable; discarding " + destFfprobe);
                    Files.deleteIfExists(destFfprobe);
                }
            }
            // The archive can unpack cleanly and still yield a broken executable;
            // verify before reporting success.
            if (!canRun(destFfmpeg)) {
                Log.warn("Extracted ffmpeg is not runnable: " + destFfmpeg);
                Files.deleteIfExists(destFfmpeg);
                return null;
            }
            Log.debug("Installed ffmpeg (ffprobe present: " + probeOk + ")");
            return destFfmpeg;
        } finally {
            deleteRecursively(extractionDir);
        }
    }

    private static void unzip(Path archive, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = safeResolve(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /** Uses the system tar for .tar.xz archives (available on macOS and Linux). */
    private static void untarWithSystemTool(Path archive, Path targetDir) throws IOException {
        try {
            Process p = new ProcessBuilder("tar", "-xf", archive.toString(),
                    "-C", targetDir.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
                p.destroyForcibly();
                throw new IOException("tar extraction failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar extraction interrupted");
        }
    }

    /** Guards against zip-slip path traversal. */
    private static Path safeResolve(Path base, String entryName) throws IOException {
        Path resolved = base.resolve(entryName).normalize();
        if (!resolved.startsWith(base.normalize())) {
            throw new IOException("Archive entry escapes target directory: " + entryName);
        }
        return resolved;
    }

    private static Path findBinary(Path root, String tool) throws IOException {
        String expected = tool + PlatformUtils.exeSuffix();
        try (var stream = Files.walk(root, 6)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(expected)
                            || p.getFileName().toString().equalsIgnoreCase(tool))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Cheaply checks that an extracted executable actually runs.
     *
     * <p>A truncated archive can still unpack into a file of the expected size
     * that fails only when executed, so running {@code -version} is the only
     * reliable signal that the binary is intact.
     */
    private static boolean canRun(Path binary) {
        try {
            makeExecutable(binary);
            Process p = new ProcessBuilder(binary.toString(), "-hide_banner", "-version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && output.contains("version");
        } catch (Exception e) {
            Log.debug("Binary check failed for " + binary + ": " + e.getMessage());
            return false;
        }
    }

    private static void makeExecutable(Path binary) {
        try {
            if (!PlatformUtils.isWindows()) {
                binary.toFile().setExecutable(true, false);
                Path parent = binary.getParent();
                if (parent != null) {
                    parent.toFile().setExecutable(true, false);
                }
            }
        } catch (Exception e) {
            Log.debug("Could not set executable bit: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    /** Convenience helper that exposes progress as a simple consumer. */
    public java.util.Optional<Path> download(Path ignoredForApi, BiConsumer<Long, Long> progress) {
        return download((downloaded, total) -> progress.accept(downloaded, total));
    }
}

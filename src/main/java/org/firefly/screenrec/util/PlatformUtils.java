package org.firefly.screenrec.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Operating-system detection and filesystem helpers.
 *
 * <p>Intentionally free of JavaFX so the whole {@code core} package stays unit testable
 * and usable from headless contexts.
 */
public final class PlatformUtils {

    private PlatformUtils() {
    }

    /** Supported operating system families. */
    public enum OS {
        WINDOWS, MACOS, LINUX, UNKNOWN
    }

    private static final OS CURRENT = detect();

    private static OS detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return OS.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return OS.MACOS;
        }
        if (name.contains("nux") || name.contains("nix") || name.contains("aix")) {
            return OS.LINUX;
        }
        return OS.UNKNOWN;
    }

    public static OS current() {
        return CURRENT;
    }

    public static boolean isWindows() {
        return CURRENT == OS.WINDOWS;
    }

    public static boolean isMac() {
        return CURRENT == OS.MACOS;
    }

    public static boolean isLinux() {
        return CURRENT == OS.LINUX;
    }

    public static boolean isWayland() {
        return isLinux() && isWaylandSession(System.getenv());
    }

    public static boolean isWaylandSession(java.util.Map<String, String> env) {
        return "wayland".equalsIgnoreCase(env.getOrDefault("XDG_SESSION_TYPE", ""))
                || !env.getOrDefault("WAYLAND_DISPLAY", "").isBlank();
    }

    /** Short platform key used for FFmpeg download URLs and folder names. */
    public static String platformKey() {
        return switch (CURRENT) {
            case WINDOWS -> "win";
            case MACOS -> "mac";
            case LINUX -> "linux";
            default -> "unknown";
        };
    }

    /** Executable file suffix for the current platform. */
    public static String exeSuffix() {
        return isWindows() ? ".exe" : "";
    }

    // ------------------------------------------------------------------
    // Application folders
    // ------------------------------------------------------------------

    /** Root folder for the user's home directory. */
    public static Path userHome() {
        return Paths.get(System.getProperty("user.home", "."));
    }

    /** Application data root, e.g. {@code ~/.screenrecorder}. */
    public static Path appDataDir() {
        return userHome().resolve(".screenrecorder");
    }

    /** Where recording output defaults to. */
    public static Path defaultOutputDir() {
        Path videos = userHome().resolve("Videos");
        Path base = Files.isDirectory(videos) ? videos : userHome();
        return base.resolve("ScreenRecorder");
    }

    /** App-managed cache for downloaded FFmpeg builds. */
    public static Path ffmpegCacheDir() {
        return appDataDir().resolve("ffmpeg");
    }

    public static Path settingsFile() {
        return appDataDir().resolve("settings.json");
    }

    // ------------------------------------------------------------------
    // Filesystem helpers
    // ------------------------------------------------------------------

    /** Creates a directory (and parents) if missing. Returns the path, or empty on failure. */
    public static Optional<Path> ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return Optional.of(dir);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Expands a leading {@code ~} to the user home directory. */
    public static Path expandHome(String raw) {
        if (raw == null || raw.isBlank()) {
            return userHome();
        }
        String trimmed = raw.trim();
        if (trimmed.equals("~")) {
            return userHome();
        }
        if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            return userHome().resolve(trimmed.substring(2));
        }
        return Paths.get(trimmed);
    }

    /**
     * Returns a unique file path in {@code dir} derived from {@code fileName}.
     * Appends {@code (1)}, {@code (2)} … before the extension when the name is taken.
     */
    public static Path uniqueFile(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            Path next = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(next)) {
                return next;
            }
        }
        return dir.resolve(base + "_" + System.currentTimeMillis() + ext);
    }

    /** Human readable file size, e.g. {@code 12.4 MB}. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** Human readable duration, e.g. {@code 01:23:45} or {@code 05:12}. */
    public static String formatDuration(long totalSeconds) {
        long s = Math.max(0, totalSeconds);
        long hours = s / 3600;
        long minutes = (s % 3600) / 60;
        long seconds = s % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /**
     * Splits a shell-ish PATH-style string into entries using the platform separator.
     */
    public static List<String> splitPathEntries(String pathValue) {
        List<String> entries = new ArrayList<>();
        if (pathValue == null || pathValue.isBlank()) {
            return entries;
        }
        for (String part : pathValue.split(java.io.File.pathSeparator)) {
            if (!part.isBlank()) {
                entries.add(part.trim());
            }
        }
        return entries;
    }

    /**
     * Opens the system file manager with the given file selected, or with the
     * folder open when a directory is given.
     *
     * <p>Best-effort: failures are logged, never thrown, because this is a
     * convenience action that must not break the UI.
     */
    public static void revealInFileManager(java.nio.file.Path path) {
        if (path == null || !java.nio.file.Files.exists(path)) {
            Log.debug("Cannot reveal missing path: " + path);
            return;
        }
        path = path.toAbsolutePath().normalize();
        try {
            if (isWindows()) {
                if (java.nio.file.Files.isDirectory(path)) {
                    new ProcessBuilder("explorer.exe", path.toString()).start();
                } else {
                    WindowsFileReveal.reveal(path);
                }
            } else if (isMac()) {
                new ProcessBuilder("open", "-R", path.toString()).start();
            } else {
                // Most Linux desktops implement xdg-open; fall back to the parent folder.
                java.nio.file.Path target = java.nio.file.Files.isDirectory(path)
                        ? path : path.getParent();
                if (target != null) {
                    new ProcessBuilder("xdg-open", target.toString()).start();
                }
            }
        } catch (Exception e) {
            Log.debug("revealInFileManager failed: " + e.getMessage());
        }
    }

    /** Opens a folder in the system file manager. */
    public static void openFolder(java.nio.file.Path dir) {
        if (dir == null) {
            return;
        }
        ensureDir(dir);
        revealInFileManager(dir);
    }

    /**
     * Whether a system tray is likely to be available. On Linux this depends on
     * the desktop environment, so it is only a hint — the caller must still
     * handle {@code SystemTray.isSupported()} and failures gracefully.
     */
    public static boolean supportsTray() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return false;
        }
        return isWindows() || isMac() || isLinux();
    }

    /** Formats a timestamp for display, e.g. {@code Oct 11, 2026 18:25}. */    public static String formatDateTime(java.time.Instant instant) {
        if (instant == null) {
            return "—";
        }
        java.time.LocalDateTime local = java.time.LocalDateTime.ofInstant(
                instant, java.time.ZoneId.systemDefault());
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("MMM d, yyyy HH:mm", Locale.getDefault());
        return local.format(fmt);
    }
}

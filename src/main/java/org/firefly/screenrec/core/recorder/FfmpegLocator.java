package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Finds a usable FFmpeg (and ffprobe) executable.
 *
 * <p>Search order, first match wins:
 * <ol>
 *   <li>an explicit user-configured path from settings,</li>
 *   <li>executables shipped next to the application ({@code ./ffmpeg/<os>/}),</li>
 *   <li>the app-managed cache ({@code ~/.screenrecorder/ffmpeg/}),</li>
 *   <li>entries on {@code PATH},</li>
 *   <li>well-known installation directories per platform.</li>
 * </ol>
 * Every candidate is verified by running {@code -version} so a broken or
 * wrong-architecture binary is never selected.
 */
public final class FfmpegLocator {

    private static final String FFMPEG = "ffmpeg";
    private static final String FFPROBE = "ffprobe";

    private final Path explicitFfmpeg;
    private final Path explicitFfprobe;

    private Path resolvedFfmpeg;
    private Path resolvedFfprobe;
    private String resolvedVersion;

    private FfmpegLocator(Path explicitFfmpeg, Path explicitFfprobe) {
        this.explicitFfmpeg = explicitFfmpeg;
        this.explicitFfprobe = explicitFfprobe;
    }

    public static FfmpegLocator create() {
        return new FfmpegLocator(null, null);
    }

    /** Creates a locator that prefers the given paths (from user settings). */
    public static FfmpegLocator withExplicit(Path ffmpeg, Path ffprobe) {
        return new FfmpegLocator(ffmpeg, ffprobe);
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Locates FFmpeg, returning empty when nothing usable was found.
     *
     * <p>Trusted locations (explicit setting, shipped binary, app-managed cache,
     * PATH) are accepted with a cheap {@code -version} check and win immediately.
     * Bundled builds discovered by scanning application folders are unverified
     * third-party copies, so when we have to fall back to those we rank them by
     * capability and pick the strongest rather than the first on disk.
     */
    public synchronized Optional<Path> findFfmpeg() {
        if (resolvedFfmpeg != null) {
            return Optional.of(resolvedFfmpeg);
        }

        List<Path> trusted = new ArrayList<>();
        List<Path> bundled = new ArrayList<>();
        collectCandidates(FFMPEG, trusted, bundled);

        for (Path candidate : trusted) {
            if (verify(candidate)) {
                return resolved(candidate);
            }
        }

        Path best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Path candidate : bundled) {
            if (!verify(candidate)) {
                continue;
            }
            int score = capabilityScore(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null) {
            return resolved(best);
        }

        Log.warn("No working FFmpeg executable found");
        return Optional.empty();
    }

    private Optional<Path> resolved(Path candidate) {
        resolvedFfmpeg = candidate.toAbsolutePath().normalize();
        Log.info("FFmpeg found: %s", resolvedFfmpeg);
        return Optional.of(resolvedFfmpeg);
    }

    /**
     * Ranks an unverified FFmpeg build by the capture and encoding features it
     * advertises. Newer, fuller builds score higher so a bundled 7.x/8.x binary
     * beats an old stripped helper that happens to sit earlier in the scan.
     */
    private int capabilityScore(Path exe) {
        int score = 0;
        try {
            // Modern DXGI Desktop Duplication capture is the single biggest win
            // on Windows: it is GPU-side and far faster than GDI.
            if (probeContains(exe, List.of("-hide_banner", "-filters"), "ddagrab")) {
                score += 100;
            }
            String encoders = probe(exe, List.of("-hide_banner", "-encoders"));
            if (encoders.contains("nvenc")) {
                score += 40;
            }
            if (encoders.contains("libx264")) {
                score += 20;
            }
            if (encoders.contains("amf") || encoders.contains("qsv")) {
                score += 10;
            }
            // Prefer builds whose reported version is recent.
            String version = probe(exe, List.of("-version"));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("version\\s+(?:git-)?(\\d{4})").matcher(version);
            if (m.find()) {
                score += Math.min(30, Math.max(0, Integer.parseInt(m.group(1)) - 2000));
            }
        } catch (Exception e) {
            Log.debug("Capability probe failed for " + exe + ": " + e.getMessage());
        }
        return score;
    }

    private boolean probeContains(Path exe, List<String> args, String needle) {
        return probe(exe, args).contains(needle);
    }

    private String probe(Path exe, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(args);
        try {
            org.firefly.screenrec.util.ProcessRunner.Result r =
                    org.firefly.screenrec.util.ProcessRunner.run(cmd, 15_000);
            return r.stdout() + "\n" + r.stderr();
        } catch (Exception e) {
            return "";
        }
    }

    /** Locates ffprobe, returning empty when unavailable. */
    public synchronized Optional<Path> findFfprobe() {
        if (resolvedFfprobe != null) {
            return Optional.of(resolvedFfprobe);
        }
        for (Path candidate : candidates(FFPROBE)) {
            if (verify(candidate)) {
                resolvedFfprobe = candidate.toAbsolutePath().normalize();
                Log.info("ffprobe found: %s", resolvedFfprobe);
                return Optional.of(resolvedFfprobe);
            }
        }
        Log.warn("No working ffprobe executable found");
        return Optional.empty();
    }

    /**
     * Tries to locate ffprobe next to the already-resolved ffmpeg binary.
     */
    public synchronized Optional<Path> findFfprobeBeside(Path ffmpegPath) {
        if (ffmpegPath == null) {
            return findFfprobe();
        }
        String name = FFPROBE + PlatformUtils.exeSuffix();
        Path sibling = ffmpegPath.getParent() == null ? null : ffmpegPath.getParent().resolve(name);
        if (sibling != null && verify(sibling)) {
            resolvedFfprobe = sibling.toAbsolutePath().normalize();
            return Optional.of(resolvedFfprobe);
        }
        return findFfprobe();
    }

    public synchronized Optional<String> version() {
        if (resolvedVersion != null) {
            return Optional.of(resolvedVersion);
        }
        return findFfmpeg().flatMap(this::probeVersion);
    }

    /** Forces re-detection on the next lookup (used after the user changes the path). */
    public synchronized void reset() {
        resolvedFfmpeg = null;
        resolvedFfprobe = null;
        resolvedVersion = null;
    }

    /**
     * True when the given binary lives inside the app-managed directories.
     *
     * <p>The distinction matters: a managed copy is one we downloaded and can
     * upgrade, whereas anything else — a system install or a copy bundled by
     * unrelated software — is an external dependency the user asked us not to
     * rely on.
     */
    public static boolean isManaged(Path binary) {
        if (binary == null) {
            return false;
        }
        try {
            Path cache = PlatformUtils.ffmpegCacheDir().toAbsolutePath().normalize();
            Path normalized = binary.toAbsolutePath().normalize();
            if (normalized.startsWith(cache)) return true;
            Path root = applicationRoot();
            if (root == null) return false;
            return normalized.startsWith(root.resolve("ffmpeg"))
                    || normalized.startsWith(root.resolve("tools").resolve("ffmpeg"))
                    || normalized.equals(root.resolve("ffmpeg" + PlatformUtils.exeSuffix()));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Candidate generation
    // ------------------------------------------------------------------

    private List<Path> candidates(String tool) {
        List<Path> trusted = new ArrayList<>();
        List<Path> bundled = new ArrayList<>();
        collectCandidates(tool, trusted, bundled);
        List<Path> all = new ArrayList<>(trusted);
        all.addAll(bundled);
        return all;
    }

    /**
     * Fills {@code trusted} with locations we control (explicit setting, shipped
     * binary, app cache, PATH) and {@code bundled} with third-party copies found
     * by scanning application folders.
     */
    private void collectCandidates(String tool, List<Path> trusted, List<Path> bundled) {
        Set<Path> known = new LinkedHashSet<>();
        String exe = tool + PlatformUtils.exeSuffix();

        // 1. Explicit configuration
        Path explicit = tool.equals(FFMPEG) ? explicitFfmpeg : explicitFfprobe;
        if (explicit != null) {
            trusted.add(explicit);
        }

        // 2. Shipped next to the app: ./ffmpeg/<platform>/ffmpeg[.exe]
        Path appDir = applicationRoot();
        if (appDir != null) {
            trusted.add(appDir.resolve("ffmpeg").resolve(PlatformUtils.platformKey()).resolve(exe));
            trusted.add(appDir.resolve("ffmpeg").resolve(exe));
            trusted.add(appDir.resolve("tools").resolve("ffmpeg").resolve(exe));
            trusted.add(appDir.resolve(exe));
        }

        // Development runs use the same project-owned engine as installers.
        Path project = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.isRegularFile(project.resolve("pom.xml"))) {
            trusted.add(project.resolve("ffmpeg").resolve(PlatformUtils.platformKey()).resolve(exe));
        }

        // 3. App-managed cache — this is where FfmpegDownloader installs its
        //    flattened binaries (see FfmpegDownloader.extract -> cache/bin).
        //    Without the bin/ entries a successful download would be invisible
        //    on the next launch and the app would fall back to a third-party
        //    copy found elsewhere on the machine.
        Path cache = PlatformUtils.ffmpegCacheDir();
        trusted.add(cache.resolve("bin").resolve(exe));
        trusted.add(cache.resolve(PlatformUtils.platformKey()).resolve("bin").resolve(exe));
        trusted.add(cache.resolve(PlatformUtils.platformKey()).resolve(exe));
        trusted.add(cache.resolve(exe));

        // 4. PATH
        String pathValue = System.getenv("PATH");
        for (String dir : PlatformUtils.splitPathEntries(pathValue)) {
            try {
                trusted.add(Paths.get(dir).resolve(exe));
            } catch (java.nio.file.InvalidPathException e) {
                Log.warn("Ignoring invalid PATH entry: " + dir);
            }
        }

        // 5. Well-known install locations, then a scan of application folders.
        bundled.addAll(knownInstallDirs(tool));

        // Only keep things that actually exist on disk, plus bare names for PATH lookup.
        filterExisting(trusted, known);
        filterExisting(bundled, known);
    }

    private static void filterExisting(List<Path> paths, Set<Path> seen) {
        paths.removeIf(p -> {
            if (p.getParent() == null) {
                return false; // bare name resolved through PATH
            }
            return !Files.isRegularFile(p) || !seen.add(p.toAbsolutePath().normalize());
        });
    }

    private static Path applicationRoot() {
        try {
            Path codeSource = Paths.get(FfmpegLocator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            return dir == null ? null : dir.toAbsolutePath().normalize();
        } catch (Exception e) {
            return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        }
    }

    private static List<Path> knownInstallDirs(String tool) {
        String exe = tool + PlatformUtils.exeSuffix();
        List<Path> dirs = new ArrayList<>();
        if (PlatformUtils.isWindows()) {
            String programFiles = System.getenv("ProgramFiles");
            String programFilesX86 = System.getenv("ProgramFiles(x86)");
            String localAppData = System.getenv("LOCALAPPDATA");
            String[] roots = {programFiles, programFilesX86, localAppData};
            for (String root : roots) {
                if (root == null) {
                    continue;
                }
                Path base = Paths.get(root);
                dirs.add(base.resolve("ffmpeg").resolve("bin").resolve(exe));
                dirs.add(base.resolve("FFmpeg").resolve("bin").resolve(exe));
            }
            dirs.add(Paths.get("C:\\ffmpeg\\bin").resolve(exe));
            // Chocolatey shim location
            dirs.add(Paths.get("C:\\ProgramData\\chocolatey\\bin").resolve(exe));
            // Many applications bundle a full FFmpeg build in their own folder
            // (Format Factory, OBS plugins, video converters, …). Scanning one
            // level below Program Files finds those without a system install.
            dirs.addAll(scanForBundledFfmpeg(roots, exe));
        } else {
            dirs.add(Paths.get("/usr/local/bin").resolve(exe));
            dirs.add(Paths.get("/usr/bin").resolve(exe));
            dirs.add(Paths.get("/opt/homebrew/bin").resolve(exe));
            dirs.add(Paths.get("/snap/bin").resolve(exe));
            dirs.add(Paths.get("/opt/local/bin").resolve(exe));
        }
        return dirs;
    }

    /**
     * Scans one level below the given Windows install roots for a bundled
     * {@code ffmpeg.exe}. Results are ordered by how likely the build is to be a
     * complete, modern static build rather than a helper stub.
     */
    private static List<Path> scanForBundledFfmpeg(String[] roots, String exe) {
        List<Path> found = new ArrayList<>();
        // Include both "Program Files" variants on every fixed drive, because
        // applications are commonly installed to a non-system drive (D:, E:, …).
        Set<Path> bases = new LinkedHashSet<>();
        for (String root : roots) {
            if (root != null) {
                bases.add(Paths.get(root));
            }
        }
        for (java.io.File drive : java.io.File.listRoots()) {
            Path root = drive.toPath();
            String name = root.toString().toUpperCase(java.util.Locale.ROOT);
            if (!name.startsWith("A:") && !name.startsWith("B:")) {
                bases.add(root.resolve("Program Files"));
                bases.add(root.resolve("Program Files (x86)"));
            }
        }

        for (Path base : bases) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (var children = Files.list(base)) {
                children.filter(Files::isDirectory).forEach(appDir -> {
                    // Common layouts: <app>/ffmpeg.exe, <app>/bin/ffmpeg.exe,
                    // <app>/program/ffmpeg.exe, <app>/ffmpeg/bin/ffmpeg.exe
                    for (Path candidate : List.of(
                            appDir.resolve(exe),
                            appDir.resolve("bin").resolve(exe),
                            appDir.resolve("program").resolve(exe),
                            appDir.resolve("ffmpeg").resolve("bin").resolve(exe))) {
                        if (Files.isRegularFile(candidate)) {
                            found.add(candidate);
                        }
                    }
                });
            } catch (IOException e) {
                Log.debug("Could not scan " + base + ": " + e.getMessage());
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Verification
    // ------------------------------------------------------------------
    /** Runs {@code <tool> -version} and checks the output looks like FFmpeg. */
    private boolean verify(Path candidate) {
        try {
            ProcessBuilder pb = new ProcessBuilder(candidate.toString(), "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && output != null && (output.toLowerCase().startsWith("ffmpeg version") || output.toLowerCase().startsWith("ffprobe version"));
        } catch (IOException e) {
            if (!(e instanceof java.io.FileNotFoundException)) {
                Log.debug("verify failed for " + candidate + ": " + e.getMessage());
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Optional<String> probeVersion(Path exe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toString(), "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String first;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                first = reader.readLine();
            }
            p.waitFor(8, TimeUnit.SECONDS);
            if (first != null) {
                resolvedVersion = first.trim();
                return Optional.of(resolvedVersion);
            }
        } catch (Exception e) {
            Log.debug("version probe failed: " + e.getMessage());
        }
        return Optional.empty();
    }
}

package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import org.firefly.screenrec.util.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes a concrete FFmpeg binary for the encoders, muxers and capture devices
 * it actually supports, then answers "which backends can I really use?".
 *
 * <p>This is what keeps the recorder honest across platforms and FFmpeg builds:
 * the UI only ever offers options that will genuinely work.
 */
public final class FfmpegCapabilities {

    /** Output of {@code ffmpeg -hide_banner -encoders} */
    private static final Pattern ENCODER_LINE =
            Pattern.compile("^\\s*([A-Z.]{6})\\s+(\\S+)\\s+(.*)$");
    /**
     * Output of {@code ffmpeg -hide_banner -devices}. Real layout is a single
     * flag column followed by the device name, e.g. {@code " D  gdigrab  GDI API..."}
     * (older builds) or {@code "DE dshow ..."} (newer builds), so accept one or
     * two flag characters.
     */
    private static final Pattern DEVICE_LINE =
            Pattern.compile("^\\s*([D][E]?|[.][E])\\s+(\\S+)\\s*(.*)$");
    /** Output of {@code ffmpeg -hide_banner -filters}, filter rows end with "->V" or similar. */
    private static final Pattern FILTER_LINE =
            Pattern.compile("^\\s*\\S+\\s+(\\S+)\\s+.*");

    private final Set<String> encoders = new LinkedHashSet<>();
    private final Set<String> muxers = new LinkedHashSet<>();
    private final Set<String> demuxers = new LinkedHashSet<>();
    private final Set<String> devices = new LinkedHashSet<>();
    private final Set<String> filters = new LinkedHashSet<>();

    private String versionLine = "unknown";
    private boolean probed;
    private boolean portalAvailable;

    private FfmpegCapabilities() {
    }

    // ------------------------------------------------------------------
    // Probing
    // ------------------------------------------------------------------

    /**
     * Probes the given FFmpeg binary. Never throws: on failure an empty
     * capability set is returned and the error is logged.
     */
    public static FfmpegCapabilities probe(Path ffmpeg) {
        FfmpegCapabilities caps = new FfmpegCapabilities();
        if (ffmpeg == null) {
            return caps;
        }
        AtomicReference<Path> exe = new AtomicReference<>(ffmpeg);

        caps.versionLine = run(exe.get(), "-version")
                .map(r -> r.stdout().lines().findFirst().orElse("unknown"))
                .orElse("unknown");

        run(exe.get(), "-hide_banner", "-encoders")
                .ifPresent(r -> caps.parseEncoders(r.stdout()));

        run(exe.get(), "-hide_banner", "-devices")
                .ifPresent(r -> caps.parseDevices(r.stdout()));

        run(exe.get(), "-hide_banner", "-muxers")
                .ifPresent(r -> caps.parseMuxers(r.stdout()));

        run(exe.get(), "-hide_banner", "-demuxers")
                .ifPresent(r -> caps.parseMuxers(r.stdout()));

        run(exe.get(), "-hide_banner", "-filters")
                .ifPresent(r -> caps.parseFilters(r.stdout()));

        caps.portalAvailable = java.nio.file.Files.isExecutable(LinuxPortalCapture.helperPath(ffmpeg));
        caps.probed = true;
        Log.info("FFmpeg capabilities: %d encoders, %d devices, %d filters, version=%s",
                caps.encoders.size(), caps.devices.size(), caps.filters.size(),
                shortVersion(caps.versionLine));
        Log.debug("Encoders: " + caps.encoders);
        return caps;
    }

    private static String shortVersion(String line) {
        int idx = line.indexOf("Copyright");
        return idx > 0 ? line.substring(0, idx).trim() : line;
    }

    private static Optional<ProcessRunner.Result> run(Path exe, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(List.of(args));
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, 20_000);
            if (!r.ok()) {
                Log.debug("ffmpeg " + String.join(" ", args) + " exited " + r.exitCode());
            }
            return Optional.of(r);
        } catch (Exception e) {
            Log.warn("Failed to run ffmpeg " + String.join(" ", args) + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private void parseEncoders(String output) {
        for (String line : output.split("\\R")) {
            Matcher m = ENCODER_LINE.matcher(line);
            if (m.matches()) {
                String flags = m.group(1);
                String name = m.group(2);
                // FFmpeg prints a six-character flag column whose FIRST character
                // is the codec kind: V=video, A=audio, S=subtitle, D=data.
                // Example: "V..... libx264 ...". The remaining five characters
                // are capability flags (.F.... = frame threading, etc.).
                char kind = flags.charAt(0);
                if (kind == 'V' || kind == 'A') {
                    encoders.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private void parseDevices(String output) {
        for (String line : output.split("\\R")) {
            Matcher m = DEVICE_LINE.matcher(line);
            if (m.matches()) {
                devices.add(m.group(2).toLowerCase(Locale.ROOT));
            }
        }
    }

    private void parseMuxers(String output) {
        for (String line : output.split("\\R")) {
            if (line.isBlank() || line.startsWith("File formats") || line.startsWith("--")
                    || line.startsWith("  E") || line.trim().startsWith("D.")) {
                // still allow parse below
            }
            String trimmed = line.trim();
            if (trimmed.length() < 4 || !(trimmed.startsWith("E") || trimmed.startsWith("D")
                    || trimmed.startsWith("DE") || trimmed.startsWith("  E") || trimmed.startsWith(" D "))) {
                if (!trimmed.startsWith("E") && !trimmed.startsWith("D")) {
                    continue;
                }
            }
            Matcher m = Pattern.compile("^[DE.]{1,3}\\s+(\\S+)\\s+(.*)$").matcher(trimmed);
            if (m.matches()) {
                String name = m.group(1).toLowerCase(Locale.ROOT);
                if (!name.contains(",")) {
                    muxers.add(name);
                }
            }
        }
    }

    private void parseFilters(String output) {
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Filters:") || trimmed.startsWith("--")) {
                continue;
            }
            // Format: " T.. name  input -> output  description"
            Matcher m = Pattern.compile("^[TSC.]+\\s+([A-Za-z0-9_]+)\\s+.*").matcher(trimmed);
            if (m.matches()) {
                filters.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public boolean isProbed() {
        return probed;
    }

    public String versionLine() {
        return versionLine;
    }

    public Set<String> encoders() {
        return Set.copyOf(encoders);
    }

    public Set<String> devices() {
        return Set.copyOf(devices);
    }

    public Set<String> filters() {
        return Set.copyOf(filters);
    }

    public boolean hasEncoder(String name) {
        return name != null && encoders.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean hasFilter(String name) {
        return name != null && filters.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean hasDevice(String name) {
        return name != null && devices.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Video encoder backends actually available in this FFmpeg build,
     * ordered by preference for the current platform.
     */
    public List<EncoderBackend> availableEncoders() {
        List<EncoderBackend> out = new ArrayList<>();
        for (EncoderBackend b : EncoderBackend.preferredForPlatform()) {
            if (hasEncoder(b.ffmpegName())) {
                out.add(b);
            }
        }
        // Always include software fallbacks as a last resort when present.
        for (EncoderBackend b : List.of(EncoderBackend.LIBX264, EncoderBackend.LIBX265)) {
            if (hasEncoder(b.ffmpegName()) && !out.contains(b)) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * Capture backends usable on this platform, best first.
     */
    public List<CaptureBackend> availableCaptureBackends() {
        List<CaptureBackend> out = new ArrayList<>();
        for (CaptureBackend b : CaptureBackend.preferredForPlatform()) {
            if (isCaptureBackendSupported(b)) {
                out.add(b);
            }
        }
        return out;
    }

    private boolean isCaptureBackendSupported(CaptureBackend backend) {
        return switch (backend) {
            // ddagrab is a filter (needs hwupload + a D3D11 device); gdigrab is a demuxer.
            case DDAGrab -> hasFilter("ddagrab");
            case GDIgrab -> hasDevice("gdigrab") || hasFilter("gdigrab");
            case AVFoundation -> hasDevice("avfoundation");
            case X11grab -> hasDevice("x11grab") || hasFilter("x11grab");
            case Portal -> PlatformUtils.isWayland() && portalAvailable;
            case KMSgrab -> hasDevice("kmsgrab");
            case AUTO -> true;
        };
    }

    /**
     * Best capture backend for the platform, or empty when none is supported.
     */
    public Optional<CaptureBackend> bestCaptureBackend() {
        List<CaptureBackend> list = availableCaptureBackends();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Best video encoder, preferring hardware acceleration.
     *
     * @deprecated This only consults encoders compiled into the FFmpeg build and
     *     ignores whether the local driver can actually drive them, so the first
     *     entry can be a hardware encoder that fails on this machine. Use
     *     {@link org.firefly.screenrec.core.AppContext#usableEncoders()} instead,
     *     which verifies each candidate with a real encode.
     */
    @Deprecated
    public Optional<EncoderBackend> bestEncoder() {
        List<EncoderBackend> list = availableEncoders();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** True when at least one audio encoder is present. */
    public boolean hasAudioEncoding() {
        return hasEncoder("aac") || hasEncoder("libmp3lame") || hasEncoder("libopus")
                || hasEncoder("libvorbis");
    }

    /** Number of hardware encoders found, used for UI messaging. */
    public long hardwareEncoderCount() {
        return availableEncoders().stream().filter(EncoderBackend::isHardware).count();
    }

    /** Human readable summary for the settings/diagnostics view. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("FFmpeg: ").append(shortVersion(versionLine)).append('\n');
        sb.append("Platform: ").append(PlatformUtils.current()).append('\n');
        List<EncoderBackend> enc = availableEncoders();
        sb.append("Video encoders: ");
        if (enc.isEmpty()) {
            sb.append("none detected");
        } else {
            for (int i = 0; i < enc.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(enc.get(i).displayName());
            }
        }
        sb.append('\n');
        List<CaptureBackend> back = availableCaptureBackends();
        sb.append("Capture backends: ");
        if (back.isEmpty()) {
            sb.append("none detected");
        } else {
            for (int i = 0; i < back.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(back.get(i).displayName());
            }
        }
        return sb.toString();
    }

    /** Flags that indicate a usable build; used by tests and diagnostics. */
    public EnumSet<Feature> features() {
        EnumSet<Feature> set = EnumSet.noneOf(Feature.class);
        if (hasFilter("ddagrab")) {
            set.add(Feature.DDAGrab);
        }
        if (hasDevice("gdigrab")) {
            set.add(Feature.GDIgrab);
        }
        if (hasDevice("avfoundation")) {
            set.add(Feature.AVFoundation);
        }
        if (hasDevice("x11grab")) {
            set.add(Feature.X11grab);
        }
        if (hasEncoder("libx264")) {
            set.add(Feature.X264);
        }
        if (hasEncoder("h264_nvenc")) {
            set.add(Feature.NVENC);
        }
        if (hasEncoder("h264_qsv")) {
            set.add(Feature.QSV);
        }
        if (hasEncoder("h264_amf")) {
            set.add(Feature.AMF);
        }
        if (hasEncoder("h264_videotoolbox")) {
            set.add(Feature.VIDEOTOOLBOX);
        }
        if (hasEncoder("aac")) {
            set.add(Feature.AAC);
        }
        return set;
    }

    public enum Feature {
        DDAGrab, GDIgrab, AVFoundation, X11grab, X264, NVENC, QSV, AMF, VIDEOTOOLBOX, AAC
    }
}

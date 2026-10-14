package org.firefly.screenrec.core.audio;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import org.firefly.screenrec.util.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enumerates audio inputs: microphones plus, where the platform allows it,
 * a system-output loopback device.
 *
 * <p>Strategy per platform:
 * <ul>
 *   <li><b>Windows</b> — uses DirectShow ({@code dshow}). System audio needs a
 *       loopback device such as {@code virtual-audio-capturer} or
 *       {@code "Stereo Mix"}; both are detected when present.</li>
 *   <li><b>macOS</b> — uses AVFoundation ({@code avfoundation}) video/audio
 *       device listings; system audio requires a virtual device (BlackHole,
 *       Soundflower, Loopback).</li>
 *   <li><b>Linux</b> — uses PulseAudio ({@code pulse}); microphones are sources
 *       and system audio is the monitor of the default sink.</li>
 * </ul>
 * When no FFmpeg binary is available the service still reports a sensible
 * default so the UI is not empty.
 */
public final class AudioDeviceService {

    /** dshow enumeration line: {@code [dshow @ ...] "Name" (audio)} */
    private static final Pattern DSHOW_AUDIO =
            Pattern.compile("\"([^\"]+)\"\\s*\\((audio)\\)", Pattern.CASE_INSENSITIVE);
    /** avfoundation audio entries look like {@code [2] Microphone Name} */
    private static final Pattern AVF_AUDIO =
            Pattern.compile("^\\[?(\\d+)\\]?\\s+(.+)$");

    /** Names that usually indicate a system-audio loopback device. */
    private static final List<String> SYSTEM_AUDIO_HINTS = List.of(
            "stereo mix", "立体声混音", "virtual-audio-capturer", "what u hear",
            "loopback", "soundflower", "blackhole", "monitor of", "cable output",
            "vb-audio", "voicemeeter", "loopback audio", "wave out mix", "mixage stéréo",
            "stereomix", "立体声混音", "扬声器", "speakers (loopback)");

    private AudioDeviceService() {
    }

    /**
     * Enumerates all available audio devices.
     *
     * @param ffmpeg the FFmpeg binary used for enumeration, may be null
     */
    public static List<AudioDevice> listDevices(Path ffmpeg) {
        List<AudioDevice> devices = new ArrayList<>();
        try {
            if (PlatformUtils.isWindows()) {
                devices.addAll(WasapiLoopback.devices(ffmpeg));
                devices.addAll(listWindowsDevices(ffmpeg));
            } else if (PlatformUtils.isMac()) {
                devices.addAll(listMacDevices(ffmpeg));
            } else if (PlatformUtils.isLinux()) {
                devices.addAll(listLinuxDevices(ffmpeg));
            }
        } catch (Exception e) {
            Log.warn("Audio enumeration failed: " + e.getMessage());
        }

        if (devices.isEmpty() && !PlatformUtils.isWindows()) {
            devices.addAll(defaultDevices());
        }

        // De-duplicate by id, preserving order.
        Map<String, AudioDevice> unique = new LinkedHashMap<>();
        for (AudioDevice d : devices) {
            unique.putIfAbsent(d.id(), d);
        }
        return new ArrayList<>(unique.values());
    }

    /** Only the microphones from {@link #listDevices(Path)}. */
    public static List<AudioDevice> listMicrophones(Path ffmpeg) {
        return listDevices(ffmpeg).stream().filter(AudioDevice::isMicrophone).toList();
    }

    /** Only the system-audio loopback devices. */
    public static List<AudioDevice> listSystemAudio(Path ffmpeg) {
        return listDevices(ffmpeg).stream().filter(AudioDevice::isSystemAudio).toList();
    }

    /** True when the platform can capture system audio with this FFmpeg build. */
    public static boolean supportsSystemAudio(Path ffmpeg) {
        return !listSystemAudio(ffmpeg).isEmpty();
    }

    // ==================================================================
    // Windows
    // ==================================================================

    private static List<AudioDevice> listWindowsDevices(Path ffmpeg) {
        List<AudioDevice> result = new ArrayList<>();
        if (ffmpeg == null) {
            return result;
        }
        ProcessRunner.Result r = runDshow(ffmpeg);
        if (r == null) {
            return result;
        }
        // FFmpeg prints the DirectShow device list to STDERR (not stdout), and
        // deliberately exits non-zero after listing. Both streams are scanned so
        // we keep working if a future build changes which one is used.
        String dump = r.stderr() + "\n" + r.stdout();
        return parseWindowsDevices(dump);
    }

    public static List<AudioDevice> parseWindowsDevices(String dump) {
        List<AudioDevice> result = new ArrayList<>();
        for (String line : dump.split("\\R")) {
            Matcher m = DSHOW_AUDIO.matcher(line);
            if (!m.find()) {
                continue;
            }
            String name = m.group(1).trim();
            if (name.isEmpty()) {
                continue;
            }
            boolean system = looksLikeSystemAudio(name);
            Log.info("DirectShow audio input: " + name + "; category=" + (system ? "system" : "microphone"));
            String id = (system ? "sys:" : "mic:") + name;
            result.add(new AudioDevice(
                    id,
                    name,
                    system ? AudioDevice.Kind.SYSTEM_OUTPUT : AudioDevice.Kind.MICROPHONE,
                    "dshow",
                    "audio=" + name,
                    2,
                    48_000));
        }
        if (result.isEmpty()) Log.warn("No DirectShow audio inputs. Enumeration output: " + dump.substring(0, Math.min(6000, dump.length())));
        Log.info("Found %d DirectShow audio device(s)", result.size());
        return result;
    }

    private static ProcessRunner.Result runDshow(Path ffmpeg) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(
                    ffmpeg.toString(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"),
                    15_000);
            // FFmpeg writes the device list to stderr and exits non-zero; that is expected.
            return r;
        } catch (Exception e) {
            Log.warn("dshow enumeration failed: " + e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // macOS
    // ==================================================================

    private static List<AudioDevice> listMacDevices(Path ffmpeg) {
        List<AudioDevice> result = new ArrayList<>();
        if (ffmpeg == null) {
            return result;
        }
        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(
                    ffmpeg.toString(), "-hide_banner", "-f", "avfoundation",
                    "-list_devices", "true", "-i", ""),
                    15_000);
            boolean audioSection = false;
            for (String line : (r.stdout() + "\n" + r.stderr()).split("\\R")) {
                if (line.contains("AVFoundation audio devices")) {
                    audioSection = true;
                    continue;
                }
                if (line.contains("AVFoundation video devices")) {
                    audioSection = false;
                    continue;
                }
                if (!audioSection) {
                    continue;
                }
                Matcher m = AVF_AUDIO.matcher(line.replaceAll("^.*\\]\\s*", ""));
                if (m.matches()) {
                    String index = m.group(1);
                    String name = m.group(2).trim();
                    boolean system = looksLikeSystemAudio(name);
                    result.add(new AudioDevice(
                            (system ? "sys:" : "mic:") + name,
                            name,
                            system ? AudioDevice.Kind.SYSTEM_OUTPUT : AudioDevice.Kind.MICROPHONE,
                            "avfoundation",
                            index + ":none",
                            2,
                            48_000));
                }
            }
        } catch (Exception e) {
            Log.warn("avfoundation enumeration failed: " + e.getMessage());
        }
        return result;
    }

    // ==================================================================
    // Linux
    // ==================================================================

    private static List<AudioDevice> listLinuxDevices(Path ffmpeg) {
        List<AudioDevice> result = new ArrayList<>();

        // Prefer `pactl` when available for accurate source names.
        ProcessRunner.Result pactl = null;
        try {
            pactl = ProcessRunner.run(List.of("pactl", "list", "short", "sources"), 8_000);
        } catch (Exception e) {
            Log.debug("pactl not available: " + e.getMessage());
        }

        if (pactl != null && pactl.ok()) {
            for (String line : pactl.stdout().split("\\R")) {
                String[] parts = line.split("\t");
                if (parts.length < 2) {
                    continue;
                }
                String sourceName = parts[1].trim();
                boolean system = sourceName.contains(".monitor") || sourceName.contains("monitor");
                result.add(new AudioDevice(
                        (system ? "sys:" : "mic:") + sourceName,
                        sourceName,
                        system ? AudioDevice.Kind.SYSTEM_OUTPUT : AudioDevice.Kind.MICROPHONE,
                        "pulse",
                        sourceName,
                        2,
                        48_000));
            }
        }

        // Fallback: the PulseAudio default source, plus the sink monitor for system audio.
        if (result.stream().noneMatch(AudioDevice::isMicrophone)) {
            result.add(new AudioDevice("mic:default", "Default input",
                    AudioDevice.Kind.MICROPHONE, "pulse", "default", 2, 48_000));
        }
        if (result.stream().noneMatch(AudioDevice::isSystemAudio)) {
            result.add(new AudioDevice("sys:default", "System output (monitor)",
                    AudioDevice.Kind.SYSTEM_OUTPUT, "pulse", "@DEFAULT_MONITOR@", 2, 48_000));
        }
        return result;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private static boolean looksLikeSystemAudio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String hint : SYSTEM_AUDIO_HINTS) {
            if (n.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Fallback devices so the UI always has something selectable. */
    private static List<AudioDevice> defaultDevices() {
        List<AudioDevice> defaults = new ArrayList<>();
        if (PlatformUtils.isWindows()) {
            defaults.add(new AudioDevice("mic:default", "Default microphone",
                    AudioDevice.Kind.MICROPHONE, "dshow", "audio=Default", 2, 48_000));
        } else if (PlatformUtils.isMac()) {
            defaults.add(new AudioDevice("mic:default", "Default microphone",
                    AudioDevice.Kind.MICROPHONE, "avfoundation", "0:none", 2, 48_000));
        } else if (PlatformUtils.isLinux()) {
            defaults.add(new AudioDevice("mic:default", "Default input",
                    AudioDevice.Kind.MICROPHONE, "pulse", "default", 2, 48_000));
            defaults.add(new AudioDevice("sys:default", "System output (monitor)",
                    AudioDevice.Kind.SYSTEM_OUTPUT, "pulse", "@DEFAULT_MONITOR@", 2, 48_000));
        }
        return defaults;
    }

    /** Convenience lookup used when restoring settings. */
    public static Optional<AudioDevice> findById(List<AudioDevice> devices, String id) {
        if (id == null) {
            return Optional.empty();
        }
        return devices.stream().filter(d -> d.id().equals(id)).findFirst();
    }
}

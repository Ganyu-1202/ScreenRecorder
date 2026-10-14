package org.firefly.screenrec.core.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.firefly.screenrec.core.recorder.CaptureBackend;
import org.firefly.screenrec.core.recorder.CaptureSource;
import org.firefly.screenrec.core.recorder.EncoderBackend;
import org.firefly.screenrec.core.recorder.OutputFormat;
import org.firefly.screenrec.core.recorder.VideoQuality;
import org.firefly.screenrec.core.screen.MonitorService;
import org.firefly.screenrec.core.screen.ScreenInfo;
import org.firefly.screenrec.core.screen.Screens;

import java.util.List;
import java.util.Locale;

/**
 * Persisted user preferences. Plain mutable bean so Jackson can round-trip it;
 * the UI layer wraps the interesting fields in observable properties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    // ---- Output ----
    private String outputDirectory = "";
    private String fileNameTemplate = "Recording_%Y%m%d_%H%M%S";
    private String format = "mp4";

    // ---- Capture defaults ----
    private int fps = 60;
    private String quality = "HIGH";
    private int videoBitrateMbps = 0;
    private String encoder = "AUTO";
    private String captureBackend = "AUTO";
    private String resolutionCap = "source";
    private boolean drawMouseCursor = true;
    private boolean highlightClicks = false;
    private boolean captureMicrophone = true;
    private boolean captureSystemAudio = false;
    private String microphoneId = "";
    private String systemAudioId = "";
    private double micGain = 1.0;
    private double systemGain = 1.0;
    private boolean mixToMono = false;
    private int countdownSeconds = 3;

    // ---- FFmpeg ----
    private String ffmpegPath = "";
    private String ffprobePath = "";
    private boolean autoDownloadFfmpeg = true;

    // ---- Appearance & behaviour ----
    private String theme = "dark";
    private String accentColor = "#ff4d5e";
    private java.util.Map<String, String> shortcuts = new java.util.LinkedHashMap<>();

    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String value) {
        accentColor = value != null && value.matches("#[0-9a-fA-F]{6}")
                ? value.toLowerCase(Locale.ROOT) : "#ff4d5e";
    }
    public java.util.Map<String, String> getShortcuts() { return shortcuts; }
    public void setShortcuts(java.util.Map<String, String> value) {
        shortcuts = value == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(value);
    }
    private boolean minimizeToTray = true;
    private boolean showControlBar = true;
    private boolean excludeControlBarFromCapture = true;

    public boolean isExcludeControlBarFromCapture() { return excludeControlBarFromCapture; }
    public void setExcludeControlBarFromCapture(boolean value) { excludeControlBarFromCapture = value; }
    private double controlBarOpacity = 0.5;
    private boolean minimizeOnRecording = false;
    private boolean keepControlBarOnTop = true;
    private double controlBarX = Double.NaN;
    private double controlBarY = Double.NaN;
    private boolean notifyOnFinish = true;
    private boolean openFolderOnFinish = false;
    private boolean playSoundOnFinish = true;
    private String language = "en";

    // ---- Window ----
    private double windowWidth = 1120;
    private double windowHeight = 760;
    private double windowX = Double.NaN;
    private double windowY = Double.NaN;
    private boolean windowMaximized = false;

    // ==================================================================
    // Getters / setters
    // ==================================================================

    public String getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(String v) {
        this.outputDirectory = v;
    }

    public String getFileNameTemplate() {
        return fileNameTemplate;
    }

    public void setFileNameTemplate(String v) {
        this.fileNameTemplate = v;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String v) {
        this.format = v;
    }

    public int getFps() {
        return fps;
    }

    public void setFps(int v) {
        this.fps = v;
    }

    public int getVideoBitrateMbps() { return videoBitrateMbps; }
    public void setVideoBitrateMbps(int value) { videoBitrateMbps = Math.max(0, Math.min(200, value)); }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String v) {
        this.quality = v;
    }

    public String getEncoder() {
        return encoder;
    }

    public void setEncoder(String v) {
        this.encoder = v;
    }

    public String getCaptureBackend() {
        return captureBackend;
    }

    public void setCaptureBackend(String v) {
        this.captureBackend = v;
    }

    /** Output resolution cap: {@code source}, {@code 2160p}, {@code 1440p}, {@code 1080p}, {@code 720p}, {@code 480p}. */
    public String getResolutionCap() {
        return resolutionCap;
    }

    public void setResolutionCap(String v) {
        this.resolutionCap = v;
    }

    public boolean isDrawMouseCursor() {
        return drawMouseCursor;
    }

    public void setDrawMouseCursor(boolean v) {
        this.drawMouseCursor = v;
    }

    public boolean isHighlightClicks() {
        return highlightClicks;
    }

    public void setHighlightClicks(boolean v) {
        this.highlightClicks = v;
    }

    public boolean isCaptureMicrophone() {
        return captureMicrophone;
    }

    public void setCaptureMicrophone(boolean v) {
        this.captureMicrophone = v;
    }

    public boolean isCaptureSystemAudio() {
        return captureSystemAudio;
    }

    public void setCaptureSystemAudio(boolean v) {
        this.captureSystemAudio = v;
    }

    public String getMicrophoneId() {
        return microphoneId;
    }

    public void setMicrophoneId(String v) {
        this.microphoneId = v;
    }

    public String getSystemAudioId() {
        return systemAudioId;
    }

    public void setSystemAudioId(String v) {
        this.systemAudioId = v;
    }

    public double getMicGain() {
        return micGain;
    }

    public void setMicGain(double v) {
        this.micGain = v;
    }

    public double getSystemGain() {
        return systemGain;
    }

    public void setSystemGain(double v) {
        this.systemGain = v;
    }

    public boolean isMixToMono() {
        return mixToMono;
    }

    public void setMixToMono(boolean v) {
        this.mixToMono = v;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public void setCountdownSeconds(int v) {
        this.countdownSeconds = v;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String v) {
        this.ffmpegPath = v;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String v) {
        this.ffprobePath = v;
    }

    public boolean isAutoDownloadFfmpeg() {
        return autoDownloadFfmpeg;
    }

    public void setAutoDownloadFfmpeg(boolean v) {
        this.autoDownloadFfmpeg = v;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String v) {
        this.theme = v;
    }

    public boolean isMinimizeToTray() {
        return minimizeToTray;
    }

    public void setMinimizeToTray(boolean v) {
        this.minimizeToTray = v;
    }

    public boolean isShowControlBar() {
        return showControlBar;
    }

    public void setShowControlBar(boolean v) {
        this.showControlBar = v;
    }

    /** Resting opacity, clamped so controls never become invisible. */
    public double getControlBarOpacity() {
        return Double.isFinite(controlBarOpacity)
                ? Math.max(0.1, Math.min(1.0, controlBarOpacity)) : 0.5;
    }

    public void setControlBarOpacity(double v) {
        controlBarOpacity = Double.isFinite(v) ? Math.max(0.1, Math.min(1.0, v)) : 0.5;
    }

    public boolean isMinimizeOnRecording() {
        return minimizeOnRecording;
    }

    public void setMinimizeOnRecording(boolean v) {
        minimizeOnRecording = v;
    }

    public boolean isKeepControlBarOnTop() {
        return keepControlBarOnTop;
    }

    public void setKeepControlBarOnTop(boolean v) {
        this.keepControlBarOnTop = v;
    }

    public double getControlBarX() {
        return controlBarX;
    }

    public void setControlBarX(double v) {
        this.controlBarX = v;
    }

    public double getControlBarY() {
        return controlBarY;
    }

    public void setControlBarY(double v) {
        this.controlBarY = v;
    }

    public boolean isNotifyOnFinish() {
        return notifyOnFinish;
    }

    public void setNotifyOnFinish(boolean v) {
        this.notifyOnFinish = v;
    }

    public boolean isOpenFolderOnFinish() {
        return openFolderOnFinish;
    }

    public void setOpenFolderOnFinish(boolean v) {
        this.openFolderOnFinish = v;
    }

    public boolean isPlaySoundOnFinish() {
        return playSoundOnFinish;
    }

    public void setPlaySoundOnFinish(boolean v) {
        this.playSoundOnFinish = v;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String v) {
        this.language = v;
    }

    public double getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(double v) {
        this.windowWidth = v;
    }

    public double getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(double v) {
        this.windowHeight = v;
    }

    public double getWindowX() {
        return windowX;
    }

    public void setWindowX(double v) {
        this.windowX = v;
    }

    public double getWindowY() {
        return windowY;
    }

    public void setWindowY(double v) {
        this.windowY = v;
    }

    public boolean isWindowMaximized() {
        return windowMaximized;
    }

    public void setWindowMaximized(boolean v) {
        this.windowMaximized = v;
    }

    // ==================================================================
    // Typed resolvers
    // ==================================================================

    /**
     * The selected display, or the primary one when nothing specific is chosen.
     * Never null — display selection always resolves to something recordable.
     */
    public ScreenInfo resolveScreen(CaptureSource source) {
        List<ScreenInfo> screens = MonitorService.monitors();
        if (screens.isEmpty()) {
            // Degenerate case (headless test environment): synthesise a screen.
            return new ScreenInfo(0, "screen0", "Display 1", 0, 0, 1920, 1080,
                    1920, 1080, 1.0, 1.0, true);
        }
        if (source != null && source.kind() == CaptureSource.Kind.MONITOR
                && source.monitorId() != null) {
            for (ScreenInfo s : screens) {
                if (s.id().equals(source.monitorId())) {
                    return s;
                }
            }
        }
        return Screens.primaryOrFirst(screens);
    }

    /** Resolves the requested region's display, defaulting to primary. */
    public ScreenInfo resolveScreen() {
        return resolveScreen(null);
    }

    /** Resolves the quality enum, tolerating unknown persisted values. */
    public VideoQuality resolveQuality() {
        try {
            return VideoQuality.valueOf(quality == null ? "HIGH" : quality.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VideoQuality.HIGH;
        }
    }

    /** Resolves the output format, defaulting to MP4. */
    public OutputFormat resolveFormat() {
        return OutputFormat.fromId(format);
    }

    /** Resolves the encoder, falling back to auto-selection. */
    public EncoderBackend resolveEncoder() {
        return EncoderBackend.fromId(encoder);
    }

    /** Resolves the capture backend, falling back to platform auto-detection. */
    public CaptureBackend resolveCaptureBackend() {
        return CaptureBackend.fromId(captureBackend);
    }

    /**
     * Frame rate clamped to a sane range. Values outside 1–240 are rejected
     * because they always indicate a corrupt settings file.
     */
    public int resolveFrameRate() {
        if (fps < 1 || fps > 240) {
            return 60;
        }
        return fps;
    }

    /** Countdown seconds clamped to 0–10. */
    public int resolveCountdownSeconds() {
        return Math.max(0, Math.min(10, countdownSeconds));
    }

    /** The filename prefix used before the timestamp. */
    public String getFilenamePrefix() {
        String template = fileNameTemplate == null ? "" : fileNameTemplate.trim();
        if (template.isBlank() || template.contains("%")) {
            // Templates with strftime tokens are handled by the controller; the
            // prefix is just the literal leading text.
            if (template.isBlank()) {
                return "Recording";
            }
            int idx = template.indexOf('%');
            String prefix = idx > 0 ? template.substring(0, idx) : "Recording";
            prefix = prefix.replace('_', ' ').trim();
            return prefix.isBlank() ? "Recording" : prefix;
        }
        return template.replace('_', ' ').trim();
    }

    /** Mic gain clamped to 0–4. */
    public double getMicrophoneVolume() {
        return Math.max(0, Math.min(4, micGain));
    }

    /** System gain clamped to 0–4. */
    public double getSystemVolume() {
        return Math.max(0, Math.min(4, systemGain));
    }

    public boolean isMicrophoneEnabled() {
        return captureMicrophone;
    }

    public boolean isSystemAudioEnabled() {
        return captureSystemAudio;
    }

    public boolean isCaptureCursor() {
        return drawMouseCursor;
    }

    public boolean isRememberControlBarPosition() {
        return keepControlBarOnTop;
    }
}

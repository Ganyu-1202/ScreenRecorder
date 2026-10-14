package org.firefly.screenrec.core;

import org.firefly.screenrec.core.audio.AudioDevice;
import org.firefly.screenrec.core.audio.AudioDeviceService;
import org.firefly.screenrec.core.media.MediaInfo;
import org.firefly.screenrec.core.media.MediaInfoService;
import org.firefly.screenrec.core.recorder.CaptureBackend;
import org.firefly.screenrec.core.recorder.EncoderBackend;
import org.firefly.screenrec.core.recorder.EncoderVerifier;
import org.firefly.screenrec.core.recorder.FfmpegCapabilities;
import org.firefly.screenrec.core.recorder.FfmpegDownloader;
import org.firefly.screenrec.core.recorder.FfmpegLocator;
import org.firefly.screenrec.core.recorder.RecordingSession;
import org.firefly.screenrec.core.settings.AppSettings;
import org.firefly.screenrec.core.settings.SettingsStore;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-wide service container.
 *
 * <p>Holds the resolved FFmpeg paths, probed capabilities, settings, detected
 * displays and audio devices, and the currently active recording session.
 * The UI observes this object rather than reaching into services directly.
 */
public final class AppContext {

    private static AppContext instance;

    private final SettingsStore settingsStore;
    private AppSettings settings;

    private FfmpegLocator locator;
    private FfmpegCapabilities capabilities;
    private MediaInfoService mediaInfoService;
    private FfmpegDownloader downloader;

    private Path ffmpegPath;

    /** Cached result of runtime encoder verification; null until first use. */
    private List<EncoderBackend> usableEncoders;
    private Path ffprobePath;

    private final List<AudioDevice> audioDevices = new CopyOnWriteArrayList<>();

    /** Currently active recording session, if any. */
    private final ObjectProperty<RecordingSession> activeSession =
            new SimpleObjectProperty<>(null);

    /** Guards against concurrent background FFmpeg downloads. */
    private final java.util.concurrent.atomic.AtomicBoolean downloading =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private AppContext() {
        this.settingsStore = new SettingsStore();
        this.settings = settingsStore.load();
        this.downloader = new FfmpegDownloader();
        initFfmpeg();
    }

    public static synchronized AppContext get() {
        if (instance == null) {
            instance = new AppContext();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // FFmpeg initialization
    // ------------------------------------------------------------------

    private void initFfmpeg() {
        Path explicitFfmpeg = settings.getFfmpegPath() == null || settings.getFfmpegPath().isBlank()
                ? null : PlatformUtils.expandHome(settings.getFfmpegPath());
        Path explicitFfprobe = settings.getFfprobePath() == null || settings.getFfprobePath().isBlank()
                ? null : PlatformUtils.expandHome(settings.getFfprobePath());

        this.locator = FfmpegLocator.withExplicit(explicitFfmpeg, explicitFfprobe);
        resolveFfmpeg();
    }

    /**
     * Re-runs FFmpeg discovery and capability probing. Call after the user
     * changes the FFmpeg path in settings.
     */
    public void resolveFfmpeg() {
        locator.reset();
        usableEncoders = null;
        Optional<Path> ffmpeg = locator.findFfmpeg();
        if (ffmpeg.isPresent()) {
            ffmpegPath = ffmpeg.get();
            ffprobePath = locator.findFfprobeBeside(ffmpegPath).orElse(null);
            capabilities = FfmpegCapabilities.probe(ffmpegPath);
            mediaInfoService = new MediaInfoService(ffmpegPath, ffprobePath);
            Log.info("FFmpeg ready: %s", ffmpegPath);
            Log.info(capabilities.summary());
        } else {
            ffmpegPath = null;
            ffprobePath = null;
            capabilities = null;
            mediaInfoService = new MediaInfoService(null, null);
            Log.warn("FFmpeg not available; recording is disabled until resolved");
        }
        refreshAudioDevices();
    }

    /** Attempts to download FFmpeg and, on success, re-resolves everything. */
    public boolean downloadFfmpeg(FfmpegDownloader.ProgressListener progress) {
        Optional<Path> downloaded = downloader.download(progress);
        if (downloaded.isPresent()) {
            settings.setFfmpegPath("");
            settings.setFfprobePath("");
            saveSettings();
            resolveFfmpeg();
            return ffmpegPath != null;
        }
        return false;
    }

    /** Re-enumerates audio devices (after FFmpeg changes or on demand). */
    public void refreshAudioDevices() {
        audioDevices.clear();
        audioDevices.addAll(AudioDeviceService.listDevices(ffmpegPath));
        Log.info("Audio devices: " + audioDevices.size());
    }

    /**
     * Resolves FFmpeg, then — when auto-download is enabled and we are relying
     * on an unmanaged copy found elsewhere on the machine — fetches our own
     * build in the background.
     *
     * <p>The app is designed to be self-contained: it should not depend on
     * FFmpeg being installed by some other program (Format Factory, OBS, a
     * package manager, …). Those copies are treated as a stopgap so recording
     * works immediately, while the managed download replaces them.
     *
     * <p>The download runs off the UI thread and re-resolves on success, so the
     * app stays responsive and simply upgrades itself a moment later.
     */
    public void ensureManagedFfmpeg() {
        if (!settings.isAutoDownloadFfmpeg()) {
            return;
        }
        if (ffmpegPath != null && locator.isManaged(ffmpegPath)) {
            return; // already using the app's own build
        }
        // Nothing usable at all, or only a third-party copy: fetch our own.
        Log.info("No app-managed FFmpeg yet; starting background download");
        autoDownloadInBackground();
    }

    /** Runs the FFmpeg download on a daemon thread and re-resolves on success. */
    private void autoDownloadInBackground() {
        if (downloading.compareAndSet(false, true)) {
            Thread worker = new Thread(() -> {
                try {
                    Optional<Path> downloaded = downloader.download(null);
                    if (downloaded.isPresent()) {
                        Log.info("Background FFmpeg download complete: " + downloaded.get());
                        // resolveFfmpeg() only touches plain paths and processes — it
                        // is safe off the FX thread and avoids a JavaFX dependency in
                        // the core layer.
                        resolveFfmpeg();
                    } else {
                        Log.warn("Background FFmpeg download failed; keeping current engine");
                    }
                } catch (Exception e) {
                    Log.warn("Background FFmpeg download error: " + e.getMessage());
                } finally {
                    downloading.set(false);
                }
            }, "ffmpeg-download");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** True while a background FFmpeg download is in flight. */
    public boolean isDownloadingFfmpeg() {
        return downloading.get();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public AppSettings settings() {
        return settings;
    }

    public SettingsStore settingsStore() {
        return settingsStore;
    }

    public void saveSettings() {
        settingsStore.save(settings);
    }

    public Optional<Path> ffmpegPath() {
        return Optional.ofNullable(ffmpegPath);
    }

    public Optional<Path> ffprobePath() {
        return Optional.ofNullable(ffprobePath);
    }

    public boolean hasFfmpeg() {
        return ffmpegPath != null && Files.isRegularFile(ffmpegPath);
    }

    public Optional<FfmpegCapabilities> capabilities() {
        return Optional.ofNullable(capabilities);
    }

    public MediaInfoService mediaInfo() {
        return mediaInfoService == null ? new MediaInfoService(null, null) : mediaInfoService;
    }

    public FfmpegDownloader downloader() {
        return downloader;
    }

    /** Detected audio devices; empty when none could be enumerated. */
    public List<AudioDevice> audioDevices() {
        return Collections.unmodifiableList(audioDevices);
    }

    public List<AudioDevice> microphones() {
        return audioDevices.stream().filter(AudioDevice::isMicrophone).toList();
    }

    public List<AudioDevice> systemAudioDevices() {
        var endpoints = audioDevices.stream().filter(d -> "wasapi-loopback".equals(d.ffmpegInputFormat())).toList();
        return endpoints.isEmpty() ? audioDevices.stream().filter(AudioDevice::isSystemAudio).toList() : endpoints;
    }

    public boolean supportsSystemAudio() {
        return !systemAudioDevices().isEmpty();
    }

    /** Encoders actually available in the detected FFmpeg build. */
    public List<EncoderBackend> availableEncoders() {
        return capabilities == null ? List.of(EncoderBackend.LIBX264) : capabilities.availableEncoders();
    }

    /**
     * Encoders this machine can really use, most preferable first.
     *
     * <p>Being listed by FFmpeg is not enough: hardware encoders are compiled in
     * on every build but still fail at runtime when the graphics driver is too
     * old. Those are filtered out here so a recording never starts against an
     * encoder that is guaranteed to fail.
     *
     * <p>The result is cached: each candidate costs one short FFmpeg run.
     */
    public List<EncoderBackend> usableEncoders() {
        if (usableEncoders == null) {
            usableEncoders = verifyEncoders();
        }
        return usableEncoders;
    }

    private List<EncoderBackend> verifyEncoders() {
        List<EncoderBackend> advertised = availableEncoders();
        if (ffmpegPath == null || advertised.isEmpty()) {
            return advertised;
        }
        Path cache = Path.of(System.getProperty("user.home"), ".screenrecorder", "encoder-cache.properties");
        String cacheKey = null;
        try {
            cacheKey = org.firefly.screenrec.core.recorder.EncoderCache.fingerprint(ffmpegPath);
            List<EncoderBackend> cached = org.firefly.screenrec.core.recorder.EncoderCache.read(cache, cacheKey, advertised);
            if (!cached.isEmpty()) {
                Log.info("Using cached encoder verification: %s", cached);
                return cached;
            }
        } catch (Exception ignored) { /* Probe normally if fingerprinting fails. */ }
        EncoderVerifier verifier = new EncoderVerifier(ffmpegPath);
        List<EncoderBackend> usable = new ArrayList<>();
        for (EncoderBackend candidate : advertised) {
            if (verifier.isUsable(candidate)) {
                usable.add(candidate);
            }
        }
        if (usable.isEmpty()) {
            // Nothing passed the probe. Rather than refuse to record, keep the
            // advertised list so the user still gets a working configuration on
            // systems where the probe itself is unreliable.
            Log.warn("No encoder passed verification; falling back to the advertised list");
            return advertised;
        }
        if (cacheKey != null) org.firefly.screenrec.core.recorder.EncoderCache.write(cache, cacheKey, usable);
        Log.info("Usable encoders: %s", usable);
        return usable;
    }

    /** Capture backends usable on this platform with this FFmpeg build. */
    public List<CaptureBackend> availableCaptureBackends() {
        return capabilities == null ? List.of() : capabilities.availableCaptureBackends();
    }

    public ObjectProperty<RecordingSession> activeSessionProperty() {
        return activeSession;
    }

    public RecordingSession activeSession() {
        return activeSession.get();
    }

    public void setActiveSession(RecordingSession session) {
        activeSession.set(session);
    }

    public boolean isRecording() {
        RecordingSession s = activeSession.get();
        return s != null && s.state().isActive();
    }

    /** Resolved output directory, created if necessary. */
    public Path outputDirectory() {
        String raw = settings.getOutputDirectory();
        Path dir = (raw == null || raw.isBlank())
                ? PlatformUtils.defaultOutputDir()
                : PlatformUtils.expandHome(raw);
        PlatformUtils.ensureDir(dir);
        return dir;
    }

    /** Lists recordings in the output directory with lightweight metadata. */
    public List<MediaInfo> listRecordings() {
        return mediaInfo().listRecordings(outputDirectory(), false);
    }

    /**
     * Releases resources held by the application: stops any recording still in
     * flight (giving FFmpeg a chance to finalise the file) and flushes settings.
     */
    public void shutdown() {
        try {
            RecordingSession session = activeSession.get();
            if (session != null && session.isActive()) {
                Log.info("Stopping active recording during shutdown");
                session.stop();
            }
        } catch (Exception e) {
            Log.warn("Could not stop the recording during shutdown: " + e.getMessage());
        }
        try {
            settingsStore.save(settings);
        } catch (Exception e) {
            Log.warn("Could not save settings on shutdown: " + e.getMessage());
        }
        Log.info("Shutdown complete");
    }
}

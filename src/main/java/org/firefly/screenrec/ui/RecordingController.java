package org.firefly.screenrec.ui;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.core.audio.AudioConfig;
import org.firefly.screenrec.core.audio.AudioDevice;
import org.firefly.screenrec.core.recorder.CaptureSource;
import org.firefly.screenrec.core.recorder.EncoderBackend;
import org.firefly.screenrec.core.recorder.RecordingProfile;
import org.firefly.screenrec.core.recorder.RecordingSession;
import org.firefly.screenrec.core.recorder.RecordingState;
import org.firefly.screenrec.core.screen.MonitorService;
import org.firefly.screenrec.core.screen.ScreenInfo;
import org.firefly.screenrec.core.screen.Screens;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.overlay.CountdownOverlay;
import org.firefly.screenrec.ui.overlay.FloatingControlBar;
import org.firefly.screenrec.ui.overlay.RegionSelector;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.WritableImage;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Orchestrates the recording lifecycle for the UI: countdown, overlay windows,
 * session creation, and file naming.
 *
 * <p>Views request a recording through this controller rather than touching
 * {@link RecordingSession} directly, so countdown, region selection and overlays
 * behave consistently no matter which button started the recording.
 */
public final class RecordingController {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final AppContext context;

    private FloatingControlBar controlBar;
    private RegionSelector regionSelector;
    private final java.util.List<RegionSelector> allRegionSelectors = new java.util.ArrayList<>();
    private org.firefly.screenrec.ui.overlay.RecordingBorderOverlay recordingBorder;
    private Consumer<Path> onSaved;
    private Consumer<String> onError;
    private Runnable onStarted;
    private Runnable onDiscarded;
    private final java.util.Set<Path> discardedOutputs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean discardConfirmationOpen;

    public void setOnDiscarded(Runnable callback) {
        onDiscarded = callback;
    }

    public void setOnStarted(Runnable onStarted) {
        this.onStarted = onStarted;
    }

    public RecordingController(AppContext context) {
        this.context = context;
    }

    /** Callback invoked after a recording is saved successfully. */
    public void setOnSaved(Consumer<Path> onSaved) {
        this.onSaved = onSaved;
    }

    /** Callback invoked with a human-readable message when recording fails. */
    public void setOnError(Consumer<String> onError) {
        this.onError = onError;
    }

    public boolean isRecording() {
        return context.isRecording();
    }

    public RecordingSession activeSession() {
        return context.activeSession();
    }

    // ------------------------------------------------------------------
    // Recording entry points
    // ------------------------------------------------------------------

    /**
     * Starts a recording of the given source, showing a countdown first when
     * one is configured.
     *
     * @param source     what to capture
     * @param region     region rectangle when {@code source} is REGION
     * @param microphone selected microphone, or null for none
     * @param systemAudio selected system-audio device, or null for none
     */
    public void startRecording(CaptureSource.Kind sourceKind, Rectangle2D region,
                               AudioDevice microphone, AudioDevice systemAudio) {
        startRecording(sourceKind, region, microphone, systemAudio, null);
    }

    public void startMonitorRecording(ScreenInfo screen, AudioDevice microphone, AudioDevice systemAudio) {
        CaptureSource target = CaptureSource.monitor(screen.id(), screen.name(), screen.x(), screen.y(),
                screen.width(), screen.height(), screen.scaleX());
        startRecording(CaptureSource.Kind.MONITOR, null, microphone, systemAudio, target);
    }

    public void startWindowRecording(CaptureSource window, AudioDevice microphone, AudioDevice systemAudio) {
        startRecording(CaptureSource.Kind.WINDOW, null, microphone, systemAudio, window);
    }

    private void startRecording(CaptureSource.Kind sourceKind, Rectangle2D region,
                                AudioDevice microphone, AudioDevice systemAudio, CaptureSource window) {
        if (isRecording() || preparing) {
            Log.warn("Recording already in progress");
            return;
        }
        if (!context.hasFfmpeg()) {
            fail(I18n.t("rec.startFailed.ffmpeg"));
            return;
        }

        int countdown = context.settings().resolveCountdownSeconds();
        Runnable beginCapture = () -> beginCapture(sourceKind, region, microphone, systemAudio, window);

        if (countdown > 0) {
            Rectangle2D overlayBounds = window != null
                    ? regionCountdownBounds(new Rectangle2D(window.x(), window.y(), window.width(), window.height()),
                            MonitorService.monitorAt(window.x() + 1, window.y() + 1))
                    : countdownBounds(sourceKind, region);
            CountdownOverlay.show(countdown, overlayBounds, beginCapture, () ->
                    Log.info("Countdown cancelled by user"));
        } else {
            beginCapture.run();
        }
    }

    /**
     * Opens the region selector, then starts recording the chosen area.
     *
     * @param screen        display to select within
     * @param screenshot    screenshot of that display for the selection backdrop
     * @param microphone    selected microphone, or null
     * @param systemAudio   selected system-audio device, or null
     */
    public void startAllScreensRegionRecording(AudioDevice microphone, AudioDevice systemAudio) {
        if (isRecording()) return;
        closeRegionSelectors();
        var screens = MonitorService.monitors();
        var shots = screens.stream().map(org.firefly.screenrec.core.screen.ScreenCaptureService::captureScreen).toList();
        for (int i = 0; i < screens.size(); i++) {
            ScreenInfo screen = screens.get(i);
            var shot = shots.get(i);
            allRegionSelectors.add(RegionSelector.open(screen, shot, region -> {
                closeRegionSelectors();
                startRecording(CaptureSource.Kind.REGION, region, microphone, systemAudio);
            }, this::closeRegionSelectors));
        }
    }

    private void closeRegionSelectors() {
        for (var selector : allRegionSelectors) selector.forceClose();
        allRegionSelectors.clear();
    }

    public void startRegionRecording(ScreenInfo screen, WritableImage screenshot,
                                     AudioDevice microphone, AudioDevice systemAudio) {
        if (isRecording()) {
            return;
        }
        regionSelector = RegionSelector.open(screen, screenshot,
                region -> {
                    regionSelector = null;
                    startRecording(CaptureSource.Kind.REGION, region, microphone, systemAudio);
                },
                () -> {
                    regionSelector = null;
                    Log.info("Region selection cancelled");
                });
    }

    public static Rectangle2D regionCountdownBounds(Rectangle2D physical, ScreenInfo screen) {
        return new Rectangle2D(screen.visualBounds().getMinX() + (physical.getMinX() - screen.x()) / screen.scaleX(),
                screen.visualBounds().getMinY() + (physical.getMinY() - screen.y()) / screen.scaleY(),
                physical.getWidth() / screen.scaleX(), physical.getHeight() / screen.scaleY());
    }

    private Rectangle2D countdownBounds(CaptureSource.Kind sourceKind, Rectangle2D region) {
        // Show the countdown over the area being recorded, so the user sees it
        // in the final video and knows exactly what is captured.
        if (sourceKind == CaptureSource.Kind.REGION && region != null) {
            ScreenInfo monitor = MonitorService.monitorAt(region.getMinX() + 1, region.getMinY() + 1);
            if (monitor != null) return regionCountdownBounds(region, monitor);
            return region;
        }
        ScreenInfo screen = context.settings().resolveScreen(null);
        return screen.visualBounds();
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    private boolean preparing;

    private void beginCapture(CaptureSource.Kind sourceKind, Rectangle2D region,
                              AudioDevice microphone, AudioDevice systemAudio, CaptureSource window) {
        Path output = buildOutputPath();

        ScreenInfo screen = region != null
                ? MonitorService.monitorAt(region.getMinX() + 1, region.getMinY() + 1)
                : context.settings().resolveScreen(null);
        if (screen == null) {
            fail(I18n.t("rec.startFailed.noDisplay"));
            return;
        }

        CaptureSource target = window != null ? window : buildCaptureSource(sourceKind, screen, region);

        RecordingProfile.Builder builder = RecordingProfile.builder()
                .source(target)
                .outputFile(output)
                .quality(context.settings().resolveQuality())
                .explicitBitrateBps(context.settings().getVideoBitrateMbps() * 1_000_000)
                .format(context.settings().resolveFormat())
                .encoder(resolveEncoder())
                .captureBackend(context.settings().resolveCaptureBackend())
                .fps(context.settings().resolveFrameRate())
                .drawMouseCursor(context.settings().isCaptureCursor())
                .highlightClicks(context.settings().isHighlightClicks())
                .countdownSeconds(0)
                .audio(buildAudioConfig(microphone, systemAudio));

        // Only set an explicit output size when the capture source does not
        // already carry the correct dimensions (i.e. when scaling is requested).
        int[] targetSize = resolveOutputSize(target);
        if (targetSize != null) {
            builder.outputSize(targetSize[0], targetSize[1]);
        }

        RecordingProfile profile = builder.build();

        RecordingSession session = new RecordingSession(
                profile,
                context.ffmpegPath().orElseThrow(),
                buildListener());

        preparing = true;
        Runnable launch = () -> {
            boolean started = session.start();
            FxScheduler.run(() -> finishStart(session, target, started));
        };
        if (org.firefly.screenrec.util.PlatformUtils.isWayland()) {
            FxScheduler.background(launch);
        } else {
            finishStart(session, target, session.start());
        }
    }

    private void finishStart(RecordingSession session, CaptureSource target, boolean started) {
        preparing = false;
        if (!started) {
            fail(I18n.t("rec.startFailed.spawn"));
            return;
        }

        context.setActiveSession(session);
        if (!org.firefly.screenrec.util.PlatformUtils.isWayland() &&
                (target.kind() == CaptureSource.Kind.REGION || target.kind() == CaptureSource.Kind.WINDOW)) {
            closeRecordingBorder();
            Rectangle2D physical = new Rectangle2D(target.x(), target.y(), target.width(), target.height());
            ScreenInfo monitor = MonitorService.monitorAt(target.x() + 1, target.y() + 1);
            try {
                recordingBorder = org.firefly.screenrec.ui.overlay.RecordingBorderOverlay.show(
                        regionCountdownBounds(physical, monitor), context.settings().getAccentColor());
            } catch (RuntimeException e) { Log.warn("Could not show recording border: " + e.getMessage()); }
        }
        if (onStarted != null) {
            onStarted.run();
        }
        if (context.settings().isShowControlBar()) {
            showControlBar(session);
        }
    }

    /** Builds the concrete capture target, mapping region/screen to physical pixels. */
    private CaptureSource buildCaptureSource(CaptureSource.Kind sourceKind, ScreenInfo screen,
                                             Rectangle2D region) {
        if (sourceKind == CaptureSource.Kind.REGION && region != null) {
            return CaptureSource.region(
                    (int) Math.round(region.getMinX()),
                    (int) Math.round(region.getMinY()),
                    (int) Math.round(region.getWidth()),
                    (int) Math.round(region.getHeight()));
        }
        if (sourceKind == CaptureSource.Kind.FULL_SCREEN) {
            ScreenInfo union = Screens.union(MonitorService.monitors());
            ScreenInfo bounds = union == null ? screen : union;
            return CaptureSource.fullScreen(bounds.x(), bounds.y(),
                    bounds.width(), bounds.height());
        }
        // MONITOR (or a fallback for WINDOW without a resolved window).
        return CaptureSource.monitor(screen.id(), screen.name(),
                screen.x(), screen.y(), screen.width(), screen.height(), screen.scaleX());
    }

    /**
     * Returns the requested output dimensions, or null to record at native size.
     * An explicit size is only needed when the user picked a resolution cap.
     */
    private int[] resolveOutputSize(CaptureSource target) {
        String cap = context.settings().getResolutionCap();
        if (cap == null || cap.isBlank() || cap.equalsIgnoreCase("source")) {
            return null;
        }
        int targetHeight = switch (cap.toLowerCase(java.util.Locale.ROOT)) {
            case "1080p" -> 1080;
            case "720p" -> 720;
            case "480p" -> 480;
            case "1440p" -> 1440;
            case "2160p", "4k" -> 2160;
            default -> 0;
        };
        if (targetHeight <= 0 || targetHeight >= target.height()) {
            return null;
        }
        double aspect = (double) target.width() / target.height();
        int width = (int) Math.round(targetHeight * aspect);
        // Encoders require even dimensions.
        return new int[]{width - (width % 2), targetHeight - (targetHeight % 2)};
    }

    /**
     * Resolves the encoder, honouring the user's choice when it is actually
     * available and falling back to the best supported option otherwise.
     */
    private EncoderBackend resolveEncoder() {
        EncoderBackend requested = context.settings().resolveEncoder();
        // Only consider encoders that actually work on this machine. Listing an
        // encoder proves it was compiled in, not that the driver supports it —
        // picking an unusable one would fail the recording outright.
        java.util.List<EncoderBackend> available = context.usableEncoders();

        if (requested != EncoderBackend.AUTO && available.contains(requested)) {
            return requested;
        }
        if (requested != EncoderBackend.AUTO) {
            Log.warn(String.format("Requested encoder %s is not usable here; falling back", requested));
        }
        // AUTO (or an unusable explicit choice): use the platform preference order.
        for (EncoderBackend candidate : EncoderBackend.preferredForPlatform()) {
            if (available.contains(candidate)) {
                return candidate;
            }
        }
        return available.isEmpty() ? EncoderBackend.LIBX264 : available.get(0);
    }

    private RecordingSession.Listener buildListener() {
        return new RecordingSession.Listener() {
            @Override
            public void onFinished(Path outputFile, java.time.Duration duration, boolean success) {
                FxScheduler.run(() -> {
                    closeRecordingBorder();
                    // Discard owns cleanup and notification, never report it as saved.
                    if (discardedOutputs.remove(outputFile)) return;
                    context.setActiveSession(null);
                    if (success && onSaved != null) {
                        onSaved.accept(outputFile);
                    } else if (!success) {
                        fail(I18n.t("rec.failed"));
                    }
                });
            }
        };
    }

    private void showControlBar(RecordingSession session) {
        if (controlBar != null && controlBar.isShowing()) {
            controlBar.close();
        }
        controlBar = FloatingControlBar.show(session,
                s -> {
                    // Stop already requested by the bar; persist its position.
                    if (context.settings().isRememberControlBarPosition()) {
                        // Position captured before close; the bar is still up here.
                    }
                },
                () -> discardRecording(session),
                null, null, context.settings().getControlBarOpacity(),
                context.settings().isExcludeControlBarFromCapture());
    }

    // ------------------------------------------------------------------
    // Stop / discard
    // ------------------------------------------------------------------

    /** Stops the active recording, waiting for a clean finalize. */
    public void stopRecording() {
        RecordingSession session = context.activeSession();
        if (session == null) {
            return;
        }
        // Run the (blocking) graceful stop off the FX thread.
        FxScheduler.background(() -> session.stop(6000));
    }

    /** Confirms destructive discard before stopping and deleting the output. */
    public void discardRecording(RecordingSession session) {
        if (session == null || discardConfirmationOpen
                || discardedOutputs.contains(session.profile().outputFile())) return;
        discardConfirmationOpen = true;
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                I18n.t("rec.discard.confirm"),
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        confirm.setTitle(I18n.t("rec.discard.title"));
        confirm.setHeaderText(I18n.t("rec.discard.title"));
        confirm.setGraphic(null);
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK))
                .setText(I18n.t("rec.discard.delete"));
        ((javafx.scene.control.Button) confirm.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL))
                .setText(I18n.t("rec.discard.cancel"));
        new org.firefly.screenrec.ui.theme.ThemeManager(context.settings().getTheme())
                .applyCurrent(confirm.getDialogPane().getScene());
        ((javafx.stage.Stage) confirm.getDialogPane().getScene().getWindow()).setAlwaysOnTop(true);
        boolean approved;
        try {
            approved = confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                    == javafx.scene.control.ButtonType.OK;
        } finally {
            discardConfirmationOpen = false;
        }
        if (!approved) return;
        Path out = session.profile().outputFile();
        discardedOutputs.add(out);
        FxScheduler.background(() -> {
            String error = null;
            try {
                if (!session.stopAndAwaitExit(4000)) {
                    throw new java.io.IOException("Recording process has not exited");
                }
                deleteDiscardedOutput(out);
                Log.info("Discarded recording: " + out);
            } catch (Exception e) {
                error = I18n.t("rec.discard.failed") + " " + out + "\n" + e.getMessage();
                Log.warn(error);
            }
            String message = error;
            FxScheduler.run(() -> {
                if (context.activeSession() == session && !session.state().isActive())
                    context.setActiveSession(null);
                closeRecordingBorder();
                if (controlBar != null) controlBar.close();
                if (onDiscarded != null) onDiscarded.run();
                if (message != null) fail(message);
            });
        });
    }

    static void deleteDiscardedOutput(Path output) throws java.io.IOException, InterruptedException {
        // Short bounded retries handle Windows releasing the final file handle.
        for (int attempt = 0; ; attempt++) {
            try {
                java.nio.file.Files.deleteIfExists(output);
                if (java.nio.file.Files.exists(output))
                    throw new java.io.IOException("Output still exists: " + output);
                return;
            } catch (java.io.IOException e) {
                if (attempt == 9) throw e;
                Thread.sleep(150);
            }
        }
    }

    /** Toggles pause on the active session. */
    public void togglePause() {
        RecordingSession session = context.activeSession();
        if (session == null) {
            return;
        }
        RecordingState state = session.state();
        if (state == RecordingState.RECORDING) {
            session.pause();
        } else if (state == RecordingState.PAUSED) {
            session.resume();
        }
    }

    /** Closes any open overlays, e.g. on application shutdown. */
    private void closeRecordingBorder() {
        if (recordingBorder != null) { recordingBorder.close(); recordingBorder = null; }
    }

    public void closeOverlays() {
        closeRegionSelectors();
        closeRecordingBorder();
        if (controlBar != null) {
            controlBar.close();
            controlBar = null;
        }
        if (regionSelector != null) {
            regionSelector.forceClose();
            regionSelector = null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AudioConfig buildAudioConfig(AudioDevice microphone, AudioDevice systemAudio) {
        AudioConfig.Builder b = AudioConfig.builder();
        if (microphone != null && context.settings().isMicrophoneEnabled()) {
            b.microphone(microphone);
        }
        if (systemAudio != null && context.settings().isSystemAudioEnabled()) {
            b.systemAudio(systemAudio);
        }
        b.micGain(context.settings().getMicrophoneVolume());
        b.systemGain(context.settings().getSystemVolume());
        b.mixToMono(context.settings().isMixToMono());
        return b.build();
    }

    /** Builds a unique, human-readable output path such as {@code Recording 2026-10-11_18-25-03.mp4}. */
    private Path buildOutputPath() {
        Path dir = context.outputDirectory();
        String stamp = LocalDateTime.now().format(FILE_STAMP);
        String prefix = context.settings().getFilenamePrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "Recording";
        }
        String extension = context.settings().resolveFormat().extension();

        Path candidate = dir.resolve(prefix + " " + stamp + "." + extension);
        int counter = 1;
        while (java.nio.file.Files.exists(candidate)) {
            candidate = dir.resolve(prefix + " " + stamp + " (" + counter++ + ")." + extension);
        }
        return candidate;
    }

    private void fail(String message) {
        Log.error(message);
        if (onError != null) {
            onError.accept(message);
        }
    }

    /** Exposes the remembered control-bar position, if any. */
    public Optional<double[]> rememberedControlBarPosition() {
        if (!context.settings().isRememberControlBarPosition()) {
            return Optional.empty();
        }
        double x = context.settings().getControlBarX();
        double y = context.settings().getControlBarY();
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Optional.empty();
        }
        return Optional.of(new double[]{x, y});
    }
}

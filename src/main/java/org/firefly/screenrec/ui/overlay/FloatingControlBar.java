package org.firefly.screenrec.ui.overlay;

import org.firefly.screenrec.core.recorder.RecordingSession;
import org.firefly.screenrec.core.recorder.RecordingState;
import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.Icons;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * A compact, draggable, always-on-top control bar shown while recording.
 *
 * <p>Displays a live timer and pulsing record indicator alongside pause/resume,
 * stop, and discard controls. It floats above other windows and remembers where
 * the user dragged it.
 */
public final class FloatingControlBar {

    /**
     * Resting opacity of the bar. Half-transparent so it barely interferes with
     * the content being recorded; hovering raises it back to fully opaque so
     * the timer and controls stay readable when the user reaches for them.
     */
    private double idleOpacity = 0.5;

    private final RecordingSession session;
    private final Consumer<RecordingSession> onStop;
    private final Runnable onDiscard;

    private FadeTransition opacityFade;

    private Stage stage;
    private Label timerLabel;
    private Button pauseButton;
    private Circle recordingDot;
    private Timeline dotPulse;
    private Timeline timer;
    private javafx.beans.value.ChangeListener<RecordingState> stateListener;

    private double dragOffsetX;
    private double dragOffsetY;

    private FloatingControlBar(RecordingSession session,
                               Consumer<RecordingSession> onStop,
                               Runnable onDiscard) {
        this.session = session;
        this.onStop = onStop;
        this.onDiscard = onDiscard;
    }

    /**
     * Shows the control bar for a session.
     *
     * @param session    the active recording session
     * @param onStop     invoked with the session when the user stops
     * @param onDiscard  invoked when the user discards the recording
     * @param initialX   preferred left position (null = default)
     * @param initialY   preferred top position (null = default)
     */
    public static FloatingControlBar show(RecordingSession session,
                                          Consumer<RecordingSession> onStop,
                                          Runnable onDiscard,
                                          Double initialX, Double initialY) {
        return show(session, onStop, onDiscard, initialX, initialY, 0.5);
    }

    public static FloatingControlBar show(RecordingSession session,
                                          Consumer<RecordingSession> onStop,
                                          Runnable onDiscard,
                                          Double initialX, Double initialY,
                                          double opacity) {
        return show(session, onStop, onDiscard, initialX, initialY, opacity, true);
    }

    public static FloatingControlBar show(RecordingSession session,
            Consumer<RecordingSession> onStop, Runnable onDiscard,
            Double initialX, Double initialY, double opacity, boolean excludeFromCapture) {
        FloatingControlBar bar = new FloatingControlBar(session, onStop, onDiscard);
        bar.excludeFromCapture = excludeFromCapture;
        bar.idleOpacity = Double.isFinite(opacity) ? Math.max(0.1, Math.min(1, opacity)) : 0.5;
        bar.start(initialX, initialY);
        return bar;
    }

    // A small transparent margin keeps antialiased capsule edges inside the
    // scene. No drop shadow: it adds visual clutter to a half-transparent bar.
    private static final double EDGE_PADDING = 2;

    private boolean excludeFromCapture = true;

    private void start(Double initialX, Double initialY) {
        HBox capsule = buildRoot();
        capsule.getStyleClass().add("control-bar");

        // The scene root stays completely transparent: only the capsule paints.
        StackPane root = new StackPane(capsule);
        root.setPadding(new Insets(EDGE_PADDING));
        // Scene.fill alone does not override Modena's .root background.
        root.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        root.setPickOnBounds(false);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        OverlayWindow.applyStylesheets(scene);

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.setTitle(I18n.t("overlay.bar.title"));
        stage.setResizable(false);
        OverlayWindow.makeFloating(stage);

        installDragging(capsule);
        installOpacityBehaviour(capsule);

        String nativeTitle = "ScreenRecorder-ControlBar-" + java.util.UUID.randomUUID();
        stage.setTitle(nativeTitle);
        // Start invisible so no unprotected painted frame enters the recording.
        capsule.setOpacity(0);
        stage.show();
        if (requiresCaptureExclusion(excludeFromCapture) && !CaptureExclusion.apply(nativeTitle, true)) {
            // Safe fallback: do not expose a bar the user explicitly excluded.
            stage.close();
            org.firefly.screenrec.util.Log.warn("Floating bar hidden: capture exclusion unavailable; use main window/tray to stop.");
            return;
        }
        if (excludeFromCapture && org.firefly.screenrec.util.PlatformUtils.isLinux()) {
            org.firefly.screenrec.util.Log.info("Linux does not support capture exclusion; floating controls may appear in the video");
        }
        stage.setTitle(I18n.t("overlay.bar.title"));
        position(initialX, initialY);
        stage.toFront();
        javafx.application.Platform.runLater(() -> { if (stage != null) position(initialX, initialY); });

        // Only fade the painted capsule; the scene root remains transparent.
        capsule.setOpacity(0);
        capsule.setScaleX(0.92);
        capsule.setScaleY(0.92);
        FadeTransition fade = new FadeTransition(Duration.millis(200), capsule);
        fade.setToValue(idleOpacity);
        ScaleTransition scale = new ScaleTransition(Duration.millis(240), capsule);
        scale.setToX(1);
        scale.setToY(1);
        scale.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.4, 1));
        fade.play();
        scale.play();

        startTimer();
        startDotPulse();
        observeState();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private HBox buildRoot() {
        recordingDot = new Circle(5);
        recordingDot.getStyleClass().add("recording-dot");

        timerLabel = new Label("00:00");
        timerLabel.getStyleClass().add("recording-timer");

        HBox status = new HBox(8, recordingDot, timerLabel);
        status.setAlignment(Pos.CENTER_LEFT);
        status.setMinWidth(Region.USE_PREF_SIZE);

        pauseButton = UiFactory.iconButton(Icons.PAUSE, I18n.t("overlay.bar.pauseHint"));
        pauseButton.getStyleClass().add("control-bar-button");
        pauseButton.setOnAction(e -> togglePause());

        Button stopButton = new Button();
        stopButton.setGraphic(UiFactory.iconBox(Icons.STOP, 16));
        stopButton.setAccessibleText(I18n.t("overlay.bar.stop"));
        stopButton.getStyleClass().addAll("control-bar-stop", "button");
        stopButton.setTextFill(Color.WHITE);
        UiFactory.installTooltip(stopButton, I18n.t("overlay.bar.stopHint"));
        stopButton.setOnAction(e -> stop());

        Button discardButton = UiFactory.iconButton(Icons.TRASH, I18n.t("overlay.bar.discard"));
        discardButton.getStyleClass().add("control-bar-button");
        discardButton.setOnAction(e -> discard());

        Region divider = new Region();
        divider.setMinWidth(1);
        divider.setPrefWidth(1);
        divider.setMinHeight(20);
        divider.setStyle("-fx-background-color: -border-strong;");

        HBox root = new HBox(11, status, divider, pauseButton, stopButton, discardButton);
        root.setAlignment(Pos.CENTER);
        return root;
    }

    /**
     * Makes the bar translucent at rest and fully opaque while the pointer is
     * over it, so the timer stays readable when the user reaches for a control.
     */
    private void installOpacityBehaviour(HBox root) {
        root.setOnMouseEntered(e -> animateOpacity(root, 1.0));
        root.setOnMouseExited(e -> animateOpacity(root, idleOpacity));
    }

    private void animateOpacity(Region node, double target) {
        if (opacityFade != null) {
            opacityFade.stop();
        }
        opacityFade = new FadeTransition(Duration.millis(140), node);
        opacityFade.setToValue(target);
        opacityFade.play();
    }

    private void installDragging(HBox root) {
        root.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        root.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        root.setStyle("-fx-cursor: move;");
    }

    private void position(Double initialX, Double initialY) {
        stage.sizeToScene();
        double w = stage.getWidth();
        double h = stage.getHeight();

        var target = session.profile().source();
        var monitor = org.firefly.screenrec.core.screen.MonitorService.monitorAt(
                target.x() + target.width() / 2.0, target.y() + target.height() / 2.0);
        Rectangle2D visual = monitor.visualBounds();
        if (initialX != null && initialY != null && Double.isFinite(initialX) && Double.isFinite(initialY)) {
            var candidates = Screen.getScreensForRectangle(initialX, initialY, 1, 1);
            if (!candidates.isEmpty()) visual = candidates.get(0).getVisualBounds();
        } else {
            var candidates = Screen.getScreensForRectangle(visual.getMinX(), visual.getMinY(), visual.getWidth(), visual.getHeight());
            if (!candidates.isEmpty()) visual = candidates.get(0).getVisualBounds();
            initialX = null; initialY = null;
        }
        double x;
        double y;

        if (initialX != null && initialY != null) {
            x = clamp(initialX, visual.getMinX() + 8, visual.getMaxX() - w - 8);
            y = clamp(initialY, visual.getMinY() + 8, visual.getMaxY() - h - 8);
        } else {
            // Default: bottom-right corner, inset from the screen edges so the
            // bar sits clear of the taskbar and the window resize border.
            x = visual.getMaxX() - w - 24;
            y = visual.getMaxY() - h - 24;
        }
        stage.setX(x);
        stage.setY(y);
    }

    public static boolean requiresCaptureExclusion(boolean requested) {
        return requested && org.firefly.screenrec.util.PlatformUtils.isWindows();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Live updates
    // ------------------------------------------------------------------

    private void startTimer() {
        timer = new Timeline(new KeyFrame(Duration.millis(200), e -> updateTimer()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        updateTimer();
    }

    private void updateTimer() {
        if (timerLabel == null) {
            return;
        }
        long millis = session.elapsedMillis();
        timerLabel.setText(formatDuration(millis));
    }

    private void startDotPulse() {
        dotPulse = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(recordingDot.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(700),
                        new KeyValue(recordingDot.opacityProperty(), 0.28,
                                Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1400),
                        new KeyValue(recordingDot.opacityProperty(), 1.0,
                                Interpolator.EASE_BOTH)));
        dotPulse.setCycleCount(Animation.INDEFINITE);
        dotPulse.play();
    }

    private void observeState() {
        stateListener = (obs, old, state) ->
                // Listener fires on the FFmpeg reader thread; marshal to FX.
                org.firefly.screenrec.util.FxScheduler.run(() -> applyState(state));
        session.stateProperty().addListener(stateListener);
        applyState(session.state());
    }

    private void applyState(RecordingState state) {
        if (state == null || stage == null) {
            return;
        }
        updatePauseUi(state);
        if (state == RecordingState.STOPPING || state == RecordingState.FINISHED
                || state == RecordingState.ERROR) {
            close();
        }
        if (state == RecordingState.PAUSED && dotPulse != null) {
            dotPulse.pause();
            recordingDot.setOpacity(0.35);
        } else if (state == RecordingState.RECORDING && dotPulse != null) {
            dotPulse.play();
        }
    }

    private void updatePauseUi(RecordingState state) {
        boolean paused = state == RecordingState.PAUSED;
        pauseButton.setGraphic(UiFactory.iconBox(paused ? Icons.PLAY : Icons.PAUSE, 18));
        UiFactory.installTooltip(pauseButton,
                paused ? I18n.t("overlay.bar.resumeHint") : I18n.t("overlay.bar.pauseHint"));
        timerLabel.setOpacity(paused ? 0.55 : 1.0);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Toggles pause/resume. Public so global hotkeys can drive it. */
    public void togglePause() {
        RecordingState state = session.state();
        if (state == RecordingState.RECORDING) {
            session.pause();
        } else if (state == RecordingState.PAUSED) {
            session.resume();
        }
    }

    /** Requests a stop-and-save. */
    public void stop() {
        if (session.state().isActive()) {
            org.firefly.screenrec.util.FxScheduler.background(() -> session.stop());
        }
        if (onStop != null) {
            onStop.accept(session);
        }
    }

    private void discard() {
        if (onDiscard != null) {
            onDiscard.run();
        }
    }

    /** Closes the bar, animating out. */
    public void close() {
        if (stage == null) {
            return;
        }
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        if (stateListener != null) {
            session.stateProperty().removeListener(stateListener);
            stateListener = null;
        }
        if (dotPulse != null) {
            dotPulse.stop();
        }
        if (opacityFade != null) {
            opacityFade.stop();
            opacityFade = null;
        }
        Stage toClose = stage;
        stage = null;
        // Fade the capsule rather than the transparent wrapper.
        Region target = toClose.getScene().getRoot() instanceof StackPane wrapper
                ? (Region) wrapper.getChildren().get(0)
                : (Region) toClose.getScene().getRoot();
        FadeTransition fade = new FadeTransition(Duration.millis(150), target);
        fade.setToValue(0);
        ScaleTransition scale = new ScaleTransition(Duration.millis(150), target);
        scale.setToY(0.94);
        fade.setOnFinished(e -> toClose.close());
        fade.play();
        scale.play();
    }

    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /** Current window position, for persisting between recordings. */
    public double windowX() {
        return stage == null ? 0 : stage.getX();
    }

    public double windowY() {
        return stage == null ? 0 : stage.getY();
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /** Formats a duration as {@code HH:MM:SS} (or {@code MM:SS} under an hour). */
    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}

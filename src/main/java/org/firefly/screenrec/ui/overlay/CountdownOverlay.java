package org.firefly.screenrec.ui.overlay;

import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * A translucent full-screen countdown shown before a recording starts.
 *
 * <p>Counts down from the configured number of seconds, animating each tick,
 * then closes and invokes the completion callback exactly once.
 */
public final class CountdownOverlay {

    private final int seconds;
    private final Rectangle2D bounds;
    private final Runnable onFinished;
    private final Runnable onCancelled;

    private Stage stage;
    private Timeline timeline;
    private boolean finished;

    private CountdownOverlay(int seconds, Rectangle2D bounds, Runnable onFinished,
                             Runnable onCancelled) {
        this.seconds = Math.max(1, seconds);
        this.bounds = bounds;
        this.onFinished = onFinished;
        this.onCancelled = onCancelled;
    }

    /**
     * Shows a countdown covering the given screen bounds.
     *
     * @param seconds      number of ticks (1–10)
     * @param bounds       screen area to cover, in screen coordinates
     * @param onFinished   invoked on the FX thread when the countdown completes
     * @param onCancelled  invoked when the user cancels (Esc / click)
     */
    public static void show(int seconds, Rectangle2D bounds, Runnable onFinished,
                            Runnable onCancelled) {
        new CountdownOverlay(seconds, bounds, onFinished, onCancelled).start();
    }

    private void start() {
        Label number = new Label(String.valueOf(seconds));
        number.getStyleClass().add("countdown-number");

        Label caption = new Label(I18n.t("overlay.countdown.start", seconds));
        caption.getStyleClass().add("countdown-caption");

        Label hint = new Label(I18n.t("overlay.countdown.cancel"));
        hint.getStyleClass().add("countdown-caption");
        hint.setOpacity(0.65);

        VBox box = new VBox(10, number, caption, hint);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setMinSize(0, 0);
        box.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);

        StackPane root = new StackPane(box);
        root.getStyleClass().add("countdown-overlay");
        root.setPickOnBounds(true);

        Rectangle2D placement = windowBounds(bounds, org.firefly.screenrec.util.PlatformUtils.isLinux());
        if (org.firefly.screenrec.util.PlatformUtils.isLinux()) root.getStyleClass().add("countdown-card");
        Scene scene = new Scene(root, placement.getWidth(), placement.getHeight());
        scene.setFill(Color.TRANSPARENT);
        OverlayWindow.applyStylesheets(scene);

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                cancel();
            }
        });
        root.setOnMouseClicked(e -> cancel());

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setTitle("ScreenRecorder Countdown");
        stage.setX(placement.getMinX());
        stage.setY(placement.getMinY());
        stage.setWidth(placement.getWidth());
        stage.setHeight(placement.getHeight());
        OverlayWindow.makeClickThroughFalse(stage);

        stage.setOnCloseRequest(e -> {
            e.consume();
            cancel();
        });

        stage.show();
        stage.toFront();
        // Reapply bounds after native DPI/window placement has settled.
        stage.setX(placement.getMinX());
        stage.setY(placement.getMinY());
        stage.setWidth(placement.getWidth());
        stage.setHeight(placement.getHeight());

        javafx.application.Platform.runLater(() -> {
            if (stage != null) {
                stage.setX(placement.getMinX()); stage.setY(placement.getMinY());
            }
        });
        runCountdown(number);
    }

    /** A small centered Linux window avoids WM fullscreen heuristics and oversized native surfaces. */
    public static Rectangle2D windowBounds(Rectangle2D target, boolean compact) {
        if (!compact) return target;
        double width = Math.min(440, target.getWidth());
        double height = Math.min(320, target.getHeight());
        return new Rectangle2D(target.getMinX() + (target.getWidth() - width) / 2,
                target.getMinY() + (target.getHeight() - height) / 2, width, height);
    }

    private void runCountdown(Label number) {
        timeline = new Timeline();
        double tick = 1000;

        for (int i = seconds; i >= 1; i--) {
            final int value = i;
            double at = (seconds - i) * tick;
            timeline.getKeyFrames().add(new KeyFrame(Duration.millis(at), e -> {
                number.setText(String.valueOf(value));
                pulse(number);
            }));
        }

        timeline.getKeyFrames().add(new KeyFrame(Duration.millis(seconds * tick), e -> finish()));
        timeline.play();
    }

    private void pulse(Label number) {
        ScaleTransition scale = new ScaleTransition(Duration.millis(260), number);
        scale.setFromX(1.35);
        scale.setFromY(1.35);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(Interpolator.SPLINE(0.2, 0.95, 0.4, 1));
        scale.play();

        FadeTransition fade = new FadeTransition(Duration.millis(240), number);
        fade.setFromValue(0.35);
        fade.setToValue(1.0);
        fade.play();
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        closeStage(() -> {
            if (onFinished != null) {
                onFinished.run();
            }
        });
    }

    private void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        if (timeline != null) {
            timeline.stop();
        }
        closeStage(() -> {
            if (onCancelled != null) {
                onCancelled.run();
            }
        });
    }

    private void closeStage(Runnable after) {
        if (stage == null) {
            after.run();
            return;
        }
        StackPane root = (StackPane) stage.getScene().getRoot();
        FadeTransition fade = new FadeTransition(Duration.millis(140), root);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            stage.close();
            stage = null;
            after.run();
        });
        fade.play();
    }

    /** Hides without running either callback (used during app shutdown). */
    public static void forceClose(CountdownOverlay overlay) {
        if (overlay != null && overlay.stage != null) {
            overlay.finished = true;
            if (overlay.timeline != null) {
                overlay.timeline.stop();
            }
            overlay.stage.close();
        }
    }
}

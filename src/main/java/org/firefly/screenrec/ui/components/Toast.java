package org.firefly.screenrec.ui.components;

import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.Icons;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Lightweight toast notifications anchored to the bottom-right of a host pane.
 *
 * <p>Toasts stack, auto-dismiss, and can be closed early by clicking.
 */
public final class Toast {

    public enum Kind {
        INFO(Icons.INFO, null, 4000),
        SUCCESS(Icons.CHECK, "toast-success", 3800),
        WARNING(Icons.WARNING, "toast-warning", 5000),
        ERROR(Icons.ERROR, "toast-error", 6500);

        private final String icon;
        private final String styleClass;
        private final int defaultMillis;

        Kind(String icon, String styleClass, int defaultMillis) {
            this.icon = icon;
            this.styleClass = styleClass;
            this.defaultMillis = defaultMillis;
        }
    }

    private static final int MAX_VISIBLE = 4;

    private final StackPane host;
    private final VBox container = new VBox(10);
    private final Deque<Region> visible = new ArrayDeque<>();

    private Toast(StackPane host) {
        this.host = host;
        container.setAlignment(Pos.BOTTOM_RIGHT);
        container.setMouseTransparent(false);
        container.setPickOnBounds(false);
        StackPane.setAlignment(container, Pos.BOTTOM_RIGHT);
        container.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        if (!host.getChildren().contains(container)) {
            host.getChildren().add(container);
        }
    }

    /** Installs toast support into a host pane (usually the root StackPane). */
    public static Toast install(StackPane host) {
        return new Toast(host);
    }

    // ------------------------------------------------------------------
    // Convenience API
    // ------------------------------------------------------------------

    public void info(String title, String message) {
        show(Kind.INFO, title, message, Kind.INFO.defaultMillis);
    }

    public void success(String title, String message) {
        show(Kind.SUCCESS, title, message, Kind.SUCCESS.defaultMillis);
    }

    public void warning(String title, String message) {
        show(Kind.WARNING, title, message, Kind.WARNING.defaultMillis);
    }

    public void error(String title, String message) {
        show(Kind.ERROR, title, message, Kind.ERROR.defaultMillis);
    }

    public void show(Kind kind, String title, String message) {
        show(kind, title, message, kind.defaultMillis);
    }

    /** Shows a toast; safe to call from any thread. */
    public void show(Kind kind, String title, String message, int millis) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> show(kind, title, message, millis));
            return;
        }
        Region node = build(kind, title, message);
        add(node);
        scheduleDismiss(node, millis);
    }

    // ------------------------------------------------------------------
    // Implementation
    // ------------------------------------------------------------------

    private Region build(Kind kind, String title, String message) {
        HBox toast = new HBox(11);
        toast.getStyleClass().add("toast");
        if (kind.styleClass != null) {
            toast.getStyleClass().add(kind.styleClass);
        }
        toast.setAlignment(Pos.TOP_LEFT);

        Region icon = UiFactory.iconBox(kind.icon, 20);
        if (kind == Kind.ERROR) {
            icon.getStyleClass().add("text-danger");
        } else if (kind == Kind.SUCCESS) {
            icon.getStyleClass().add("text-accent");
        }
        // Colour the SVG via CSS class on the shape.
        icon.getChildrenUnmodifiable().forEach(c -> {
            if (c instanceof javafx.scene.Group g) {
                g.getChildren().forEach(shape -> {
                    if (shape instanceof javafx.scene.shape.SVGPath p) {
                        p.getStyleClass().add(switch (kind) {
                            case SUCCESS -> "text-accent";
                            case ERROR -> "text-danger";
                            case WARNING -> "text-accent";
                            case INFO -> "text-accent";
                        });
                    }
                });
            }
        });

        VBox text = new VBox(3);
        text.setAlignment(Pos.CENTER_LEFT);
        if (title != null && !title.isBlank()) {
            Label t = new Label(title);
            t.getStyleClass().add("toast-title");
            t.setWrapText(true);
            text.getChildren().add(t);
        }
        if (message != null && !message.isBlank()) {
            Label m = new Label(message);
            m.getStyleClass().add("toast-message");
            m.setWrapText(true);
            m.setMaxWidth(300);
            text.getChildren().add(m);
        }

        toast.getChildren().addAll(icon, text);
        toast.setMaxWidth(400);
        toast.setOnMouseClicked(e -> dismiss(toast));
        UiFactory.installTooltip(toast, I18n.t("toast.dismiss"));
        return toast;
    }

    private void add(Region node) {
        while (visible.size() >= MAX_VISIBLE) {
            Region oldest = visible.pollFirst();
            if (oldest != null) {
                container.getChildren().remove(oldest);
            }
        }
        visible.addLast(node);
        container.getChildren().add(node);

        node.setOpacity(0);
        node.setTranslateY(18);

        FadeTransition fade = new FadeTransition(Duration.millis(200), node);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(240), node);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.3, 1));
        slide.play();
        fade.play();
    }

    private void scheduleDismiss(Region node, int millis) {
        PauseTransition pause = new PauseTransition(Duration.millis(millis));
        pause.setOnFinished(e -> dismiss(node));
        pause.play();
    }

    /** Animates a toast out and removes it. */
    public void dismiss(Region node) {
        if (!container.getChildren().contains(node)) {
            return;
        }
        visible.remove(node);

        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setToValue(0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), node);
        slide.setToY(12);
        SequentialTransition seq = new SequentialTransition(fade, slide);
        seq.setOnFinished(e -> container.getChildren().remove(node));
        seq.play();
    }

    /** Removes every visible toast, animated. */
    public void clear() {
        new java.util.ArrayList<>(visible).forEach(this::dismiss);
    }
}

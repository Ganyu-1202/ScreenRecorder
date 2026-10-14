package org.firefly.screenrec.ui.components;

import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders tooltips <em>inside</em> the application window rather than as
 * separate popup windows.
 *
 * <p>JavaFX's stock {@link javafx.scene.control.Tooltip} is a floating
 * {@code PopupWindow}. On an {@code UNDECORATED} stage that overlay window
 * repeatedly steals the mouse away from its owner node, which fires a stream of
 * exit/enter events and makes the tooltip visibly flicker before it settles —
 * the classic "hint flashes several times" symptom.
 *
 * <p>This layer instead paints a single {@link Label} on top of the window
 * content and positions it next to the hovered node manually. Because nothing
 * leaves the window there is no enter/exit fight, so the hint simply appears and
 * stays put. It also gives every hint one consistent look.
 *
 * <p>Usage: install once per window, then attach hints through
 * {@link #attach(Node, String)}.
 */
public final class TooltipLayer {

    private static final Object LAYER_KEY = new Object();

    public static TooltipLayer forScene(javafx.scene.Scene scene) {
        Object existing = scene.getProperties().get(LAYER_KEY);
        if (existing == null) existing = scene.getRoot().getProperties().get(LAYER_KEY);
        if (existing instanceof TooltipLayer layer) return layer;
        if (!(scene.getRoot() instanceof StackPane)) {
            javafx.scene.Parent oldRoot = scene.getRoot();
            scene.setRoot(new StackPane(oldRoot));
        }
        return new TooltipLayer((StackPane) scene.getRoot());
    }

    private static final Duration SHOW_DELAY = Duration.millis(420);

    private final StackPane host;
    private final Label bubble = new Label();

    /** Per-node hint text, keyed by the node it belongs to. */
    private final Map<Node, String> hints = new HashMap<>();

    /** The node whose hint is currently armed (mouse inside), if any. */
    private Node armed;
    /** The node whose hint is currently on screen, if any. */
    private Node showing;

    private final PauseTransition showTimer = new PauseTransition(SHOW_DELAY);

    public TooltipLayer(StackPane host) {
        this.host = host;
        host.getProperties().put(LAYER_KEY, this);
        if (host.getScene() != null) host.getScene().getProperties().put(LAYER_KEY, this);
        host.sceneProperty().addListener((o, old, scene) -> {
            if (scene != null) scene.getProperties().put(LAYER_KEY, this);
        });

        bubble.getStyleClass().add("tip-bubble");
        bubble.setMouseTransparent(true);
        bubble.setVisible(false);
        bubble.setManaged(false);
        bubble.setWrapText(true);
        bubble.setMaxWidth(340);
        bubble.setPickOnBounds(false);
        StackPane.setAlignment(bubble, Pos.TOP_LEFT);
        host.getChildren().add(bubble);

        showTimer.setOnFinished(e -> {
            String text = armed == null ? null : hints.get(armed);
            if (armed != null && armed.isHover() && text != null) {
                show(armed, text);
            }
        });

        // Any structural change means the anchor may have moved; hide rather
        // than leave a stale bubble floating over the wrong control.
        host.widthProperty().addListener((o, a, b) -> hide());
        host.heightProperty().addListener((o, a, b) -> hide());
    }

    /**
     * Attaches a hint to a node using this layer.
     *
     * <p>Safe to call repeatedly: the previous entry is replaced, so re-labelling
     * a button after a language switch cannot stack duplicate state.
     */
    public void attach(Node node, String text) {
        if (node == null) {
            return;
        }
        boolean firstTime = !hints.containsKey(node);
        hints.put(node, text);

        if (firstTime) {
            node.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> onEntered(node));
            node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> onExited(node));
            // A press means the user has moved on; the hint must not linger.
            node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> hide());
        }
    }

    private void onEntered(Node node) {
        if (hints.get(node) == null) {
            return;
        }
        armed = node;
        showTimer.playFromStart();
    }

    private void onExited(Node node) {
        if (armed == node) {
            armed = null;
        }
        showTimer.stop();
        if (showing == null || showing == node) {
            hide();
        }
    }

    /** Positions and reveals the bubble for the given node. */
    private void show(Node node, String text) {
        if (text == null || text.isBlank() || node.getScene() == null) {
            return;
        }
        bubble.toFront();
        showing = node;
        bubble.setText(text);
        bubble.setVisible(true);

        // Force a layout pass so the bubble knows its own size before placing.
        bubble.applyCss();
        bubble.autosize();

        position(node);
    }

    private void position(Node node) {
        Window window = node.getScene().getWindow();
        if (window == null) {
            return;
        }
        Bounds local = node.localToScene(node.getBoundsInLocal(), true);
        Point2D inHost = host.sceneToLocal(
                local.getMinX() + node.getScene().getX(),
                local.getMinY() + node.getScene().getY());

        double bubbleW = Math.min(bubble.prefWidth(-1), bubble.getMaxWidth());
        double bubbleH = bubble.prefHeight(bubbleW);

        // Default: below the node, left-aligned.
        double x = inHost.getX();
        double y = inHost.getY() + local.getHeight() + 6;

        double maxX = Math.max(8, host.getWidth() - bubbleW - 8);
        double maxY = Math.max(8, host.getHeight() - bubbleH - 8);
        x = Math.max(8, Math.min(x, maxX));
        if (y > maxY) {
            // Not enough room below — flip above the node.
            y = inHost.getY() - bubbleH - 6;
        }
        y = Math.max(8, Math.min(y, maxY));

        bubble.relocate(x, y);
    }

    /** Hides the bubble immediately. */
    public void hide() {
        showTimer.stop();
        bubble.setVisible(false);
        showing = null;
    }
}

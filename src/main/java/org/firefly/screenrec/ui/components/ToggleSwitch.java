package org.firefly.screenrec.ui.components;

import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * An iOS-style animated toggle switch.
 *
 * <p>Exposes JavaFX properties so it drops into existing binding code exactly
 * like a {@code CheckBox}.
 */
public final class ToggleSwitch extends StackPane {

    private static final double WIDTH = 42;
    private static final double HEIGHT = 24;
    private static final double KNOB = 18;
    private static final double PADDING = 3;

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Circle thumb = new Circle(KNOB / 2);
    private final TranslateTransition slide;

    public ToggleSwitch() {
        this(false);
    }

    public ToggleSwitch(boolean initial) {
        getStyleClass().add("toggle-switch");
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setFocusTraversable(true);

        thumb.getStyleClass().add("toggle-thumb");
        thumb.setManaged(false);
        getChildren().add(thumb);

        slide = new TranslateTransition(Duration.millis(160), thumb);
        // JavaFX requires every control point to lie within [0,1]; this is the
        // standard "ease-out" curve used by Material Design motion.
        slide.setInterpolator(Interpolator.SPLINE(0.34, 0.94, 0.64, 1));

        setOnMouseClicked(e -> setSelected(!isSelected()));
        setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.SPACE
                    || e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                setSelected(!isSelected());
                e.consume();
            }
        });

        selected.addListener((obs, was, is) -> {
            updateStyle();
            animateKnob(is);
        });

        setSelected(initial);
        updateStyle();
        // Position the knob without animation on first layout.
        applyKnobPosition(isSelected(), false);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        applyKnobPosition(isSelected(), false);
    }

    private void applyKnobPosition(boolean on, boolean animate) {
        double startX = PADDING + KNOB / 2;
        double endX = WIDTH - PADDING - KNOB / 2;
        double targetX = on ? endX : startX;

        if (animate) {
            slide.stop();
            slide.setToX(targetX - thumb.getCenterX());
            slide.play();
        } else {
            slide.stop();
            thumb.setTranslateX(0);
            thumb.setCenterX(targetX);
            thumb.setCenterY(HEIGHT / 2);
        }
    }

    private void animateKnob(boolean on) {
        double startX = PADDING + KNOB / 2;
        double endX = WIDTH - PADDING - KNOB / 2;
        double targetX = on ? endX : startX;
        slide.stop();
        slide.setFromX(thumb.getCenterX() - targetX + thumb.getTranslateX());
        slide.setToX(0);
        thumb.setCenterX(targetX);
        thumb.setTranslateX(startX - targetX);
        slide.play();
    }

    private void updateStyle() {
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), isSelected());
        setAccessibleText(isSelected() ? "on" : "off");
    }

    // ------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    /**
     * Builds a labelled row: description on the left, switch on the right.
     *
     * @param title       primary label
     * @param description optional secondary line
     */
    public static javafx.scene.layout.HBox labeledRow(String title, String description,
                                                      ToggleSwitch toggle) {
        javafx.scene.layout.VBox text = new javafx.scene.layout.VBox(2);
        Label t = UiFactory.body(title);
        text.getChildren().add(t);
        if (description != null && !description.isBlank()) {
            text.getChildren().add(UiFactory.caption(description));
        }

        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getChildren().addAll(text, UiFactory.hSpacer(), toggle);
        return row;
    }
}

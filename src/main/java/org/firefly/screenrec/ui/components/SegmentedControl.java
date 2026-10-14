package org.firefly.screenrec.ui.components;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A segmented control: a row of mutually exclusive options rendered as one
 * connected pill. Generic over the value type it represents.
 *
 * @param <T> the value behind each segment
 */
public final class SegmentedControl<T> extends HBox {

    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final ToggleGroup group = new ToggleGroup();
    private final List<Segment<T>> segments = new ArrayList<>();

    private record Segment<T>(T value, ToggleButton button) {
    }

    public SegmentedControl() {
        getStyleClass().add("segmented");
        setAlignment(Pos.CENTER_LEFT);
    }

    /** Adds a segment with a text label. */
    public SegmentedControl<T> add(T value, String label) {
        return addSegment(value, label, null);
    }

    /** Adds a segment with a text label and a tooltip. */
    public SegmentedControl<T> add(T value, String label, String tooltip) {
        return addSegment(value, label, tooltip);
    }

    private SegmentedControl<T> addSegment(T value, String label, String tooltip) {
        ToggleButton btn = new ToggleButton(label);
        btn.getStyleClass().add("segmented-item");
        btn.setToggleGroup(group);
        btn.setFocusTraversable(true);
        if (tooltip != null) {
            UiFactory.installTooltip(btn, tooltip);
        }

        btn.setOnAction(e -> {
            if (btn.isSelected()) {
                this.value.set(value);
            } else {
                // Prevent deselecting everything.
                btn.setSelected(true);
            }
        });

        segments.add(new Segment<>(value, btn));
        getChildren().add(btn);
        return this;
    }

    /** Adds all items using the supplied label function. */
    public SegmentedControl<T> addAll(List<T> items, Function<T, String> labeler) {
        items.forEach(item -> add(item, labeler.apply(item)));
        return this;
    }

    /** Selects the given value programmatically. */
    public void select(T value) {
        for (Segment<T> s : segments) {
            if (java.util.Objects.equals(s.value(), value)) {
                s.button().setSelected(true);
                this.value.set(value);
                return;
            }
        }
    }

    public ObjectProperty<T> valueProperty() {
        return value;
    }

    public T getValue() {
        return value.get();
    }

    public void setValue(T v) {
        select(v);
    }
}

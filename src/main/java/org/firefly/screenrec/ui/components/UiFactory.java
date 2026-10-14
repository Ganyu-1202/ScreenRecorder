package org.firefly.screenrec.ui.components;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.Locale;

/**
 * Factory helpers for building consistently styled UI elements in code,
 * which keeps view classes focused on layout and behaviour.
 */
public final class UiFactory {

    /** Node property key under which the currently installed tooltip is stored. */
    private static final Object TOOLTIP_KEY = new Object();

    private UiFactory() {
    }

    // ------------------------------------------------------------------
    // Icons
    // ------------------------------------------------------------------

    /** A 20×20 icon that inherits the current text colour. */
    public static SVGPath icon(String svgPath) {
        return icon(svgPath, 20);
    }

    /** An icon of the given size. */
    public static SVGPath icon(String svgPath, double size) {
        SVGPath path = new SVGPath();
        path.setContent(svgPath);
        double scale = size / 24.0;
        path.setScaleX(scale);
        path.setScaleY(scale);
        path.getStyleClass().addAll("svg-path", "app-icon");
        // Wrap so the scaled bounds still layout correctly.
        return path;
    }

    /** Wraps an icon path in a layout node with an exact bounding box. */
    public static Region iconBox(String svgPath, double size) {
        SVGPath path = new SVGPath();
        path.setContent(svgPath);
        double scale = size / 24.0;
        path.setScaleX(scale);
        path.setScaleY(scale);
        path.getStyleClass().add("svg-path");
        Group group = new Group(path);
        Region holder = new Region();
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setShape(null);
        // Use a StackPane-like approach: place the group inside by overriding layout.
        holder.setManaged(true);
        holder.getStyleClass().add("icon-holder");
        return new IconHolder(group, size);
    }

    /** A Region that centres a scaled {@link Group} inside a fixed square. */
    private static final class IconHolder extends Region {
        private final Group group;

        IconHolder(Group group, double size) {
            this.group = group;
            getChildren().add(group);
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
        }

        @Override
        protected void layoutChildren() {
            double size = getWidth();
            group.autosize();
            double sx = (size - group.getBoundsInLocal().getWidth()) / 2;
            double sy = (size - group.getBoundsInLocal().getHeight()) / 2;
            group.relocate(sx, sy);
        }
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    public static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-title");
        return l;
    }

    public static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-heading");
        return l;
    }

    public static Label subheading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-subheading");
        return l;
    }

    public static Label body(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-body");
        return l;
    }

    public static Label secondary(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-secondary");
        return l;
    }

    public static Label caption(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("text-caption");
        return l;
    }

    public static Label sectionLabel(String text) {
        Label l = new Label(text.toUpperCase(Locale.ROOT));
        l.getStyleClass().add("section-label");
        return l;
    }

    // ------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------

    /** A button with an icon and text. */
    public static Button button(String text, String iconPath, String... styleClasses) {
        Button b = new Button(text);
        if (iconPath != null) {
            b.setGraphic(iconBox(iconPath, 17));
        }
        b.getStyleClass().addAll(styleClasses);
        return b;
    }

    /** An icon-only button, with a tooltip. */
    public static Button iconButton(String iconPath, String tooltip, String... styleClasses) {
        Button b = new Button();
        b.setGraphic(iconBox(iconPath, 18));
        b.getStyleClass().add("button-icon");
        b.getStyleClass().addAll(styleClasses);
        if (tooltip != null) {
            installTooltip(b, tooltip);
        }
        return b;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** A horizontal spacer that pushes following nodes to the right. */
    public static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** A vertical spacer. */
    public static Region vSpacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    /** A 1px horizontal divider. */
    public static Region divider() {
        Region r = new Region();
        r.getStyleClass().add("divider");
        r.setPrefHeight(1);
        r.setMinHeight(1);
        return r;
    }

    /** A row containing an icon and a label, used for metadata. */
    public static HBox iconLabel(String iconPath, String text, String labelStyle) {
        HBox box = new HBox(7);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        box.getChildren().addAll(iconBox(iconPath, 15), body(text));
        return box;
    }

    /** A status chip with a style variant. */
    public static Label chip(String text, String variant) {
        Label l = new Label(text);
        l.getStyleClass().add("chip");
        if (variant != null) {
            l.getStyleClass().add("chip-" + variant);
        }
        return l;
    }

    // ------------------------------------------------------------------
    // Tooltips
    // ------------------------------------------------------------------

    /**
     * Installs a tooltip with a snappier show delay, which makes the UI feel
     * more responsive than the JavaFX default.
     *
     * <p>Calling this again for the same node <em>replaces</em> the previous
     * tooltip. {@code Tooltip.install} only ever adds listeners, so re-labelling
     * a control (for example after a language switch) would otherwise leave a
     * growing stack of tooltips fighting to show themselves.
     */
    public static void installTooltip(Node node, String text) {
        if (node == null) {
            return;
        }
        node.getProperties().put(TOOLTIP_KEY, text == null ? "" : text);
        if (node.getScene() != null) {
            TooltipLayer.forScene(node.getScene()).attach(node, text);
        }
        if (node.getProperties().putIfAbsent("screenrec.tooltip.scene-listener", Boolean.TRUE) == null) {
            node.sceneProperty().addListener((o, old, scene) -> {
                if (scene != null) TooltipLayer.forScene(scene).attach(node,
                        (String) node.getProperties().get(TOOLTIP_KEY));
            });
        }
    }

    /** Returns the node unchanged, for fluent call sites. */
    public static <T extends Node> T withTooltip(T node, String text) {
        installTooltip(node, text);
        return node;
    }
}

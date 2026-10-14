package org.firefly.screenrec.ui.components;

import org.firefly.screenrec.ui.theme.Icons;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The application's left-hand navigation rail.
 *
 * <p>Emits the selected view through an observable property and animates the
 * active indicator so switching views feels responsive.
 */
public final class Sidebar extends VBox {

    /** The views reachable from the sidebar. */
    public enum Nav {
        RECORD("nav.record", Icons.RECORD),
        LIBRARY("nav.library", Icons.LIBRARY),
        EDITOR("nav.editor", Icons.EDIT),
        SETTINGS("nav.settings", Icons.SETTINGS);

        private final String labelKey;
        private final String icon;

        Nav(String labelKey, String icon) {
            this.labelKey = labelKey;
            this.icon = icon;
        }

        /** Translation key for this view's display name. */
        public String labelKey() {
            return labelKey;
        }

        /** Localised display name for this view. */
        public String label() {
            return org.firefly.screenrec.ui.i18n.I18n.t(labelKey);
        }

        public String icon() {
            return icon;
        }
    }

    private record Item(Nav nav, HBox node, javafx.scene.Node icon, Label label) {
    }

    private final ObjectProperty<Nav> selected = new SimpleObjectProperty<>(Nav.RECORD);
    private final List<Item> items = new ArrayList<>();

    public Sidebar() {
        getStyleClass().add("app-sidebar");
        setPrefWidth(216);
        setMinWidth(216);
        setMaxWidth(216);
        setSpacing(3);
        setPadding(new javafx.geometry.Insets(14, 12, 14, 12));
        build();
        select(Nav.RECORD);
    }

    private void build() {
        // Brand header.
        HBox brand = new HBox(10);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new javafx.geometry.Insets(6, 6, 20, 8));

        Region logoBox = org.firefly.screenrec.ui.theme.AppIcons.vector("icon", 30, false);

        Label brandName = new Label("ScreenRecorder");
        brandName.getStyleClass().add("text-subheading");

        brand.getChildren().addAll(logoBox, brandName);
        getChildren().add(brand);

        for (Nav nav : Nav.values()) {
            getChildren().add(buildItem(nav));
        }

        getChildren().add(UiFactory.vSpacer());

        // Footer: FFmpeg status chip (updated externally via statusNode).
        VBox footer = new VBox(6);
        footer.setPadding(new javafx.geometry.Insets(10, 4, 0, 4));
        footer.getChildren().add(UiFactory.caption(org.firefly.screenrec.ui.i18n.I18n.t("app.footer")));
        getChildren().add(footer);
    }

    private HBox buildItem(Nav nav) {
        javafx.scene.Node icon = nav == Nav.RECORD
                ? org.firefly.screenrec.ui.theme.AppIcons.vector("screen_record", 19, true)
                : UiFactory.icon(nav.icon(), 19);
        icon.getStyleClass().add("nav-icon");

        Label label = new Label(nav.label());
        label.getStyleClass().add("nav-item-label");

        HBox item = new HBox(12);
        item.getStyleClass().add("nav-item");
        item.setAlignment(Pos.CENTER_LEFT);
        item.getChildren().addAll(icon, label);
        item.setOnMouseClicked(e -> select(nav));
        UiFactory.installTooltip(item, nav.label());

        items.add(new Item(nav, item, icon, label));
        return item;
    }

    /** Selects a navigation entry and fires the observable property. */
    public void select(Nav nav) {
        for (Item item : items) {
            boolean active = item.nav() == nav;
            item.node().getStyleClass().remove("nav-item-active");
            if (active) {
                item.node().getStyleClass().add("nav-item-active");
                animateIndicator(item.node());
            }
        }
        selected.set(nav);
    }

    /** A small horizontal nudge reinforces the selection change. */
    private void animateIndicator(HBox node) {
        TranslateTransition t = new TranslateTransition(Duration.millis(180), node);
        t.setFromX(-4);
        t.setToX(0);
        t.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.3, 1));
        t.play();
    }

    public ObjectProperty<Nav> selectedProperty() {
        return selected;
    }

    public Nav selected() {
        return selected.get();
    }
}

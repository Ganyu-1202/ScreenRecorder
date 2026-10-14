package org.firefly.screenrec.ui.theme;

import org.firefly.screenrec.util.Log;
import javafx.scene.Scene;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Loads and hot-swaps application stylesheets.
 *
 * <p>Three sheets compose the look: {@code base.css} (structure & primitives),
 * a theme sheet ({@code dark.css} / {@code light.css}), and
 * {@code components.css} (concrete components).
 */
public final class ThemeManager {

    public enum Theme {
        DARK("dark", "Dark"),
        LIGHT("light", "Light");

        private final String id;
        private final String displayName;

        Theme(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Theme fromId(String id) {
            if (id != null) {
                for (Theme t : values()) {
                    if (t.id.equalsIgnoreCase(id.trim())) {
                        return t;
                    }
                }
            }
            return DARK;
        }
    }

    private static final String CSS_ROOT = "/org/firefly/screenrec/css/";

    /** Marks the injected system-font rule so it can be replaced on a theme swap. */
    private static final String FONT_SHEET_PREFIX = "data:text/css;base64,";

    private Theme current = Theme.DARK;
    private static String accentColor = "#ff4d5e";

    public static void setAccentColor(String color) {
        accentColor = color != null && color.matches("#[0-9a-fA-F]{6}") ? color : "#ff4d5e";
    }

    public static String accentCss(String hex, boolean dark) {
        javafx.scene.paint.Color c = javafx.scene.paint.Color.web(hex);
        String contrast = (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) > 0.55
                ? "#101319" : "#ffffff";
        String rgb = Math.round(c.getRed()*255) + "," + Math.round(c.getGreen()*255) + "," + Math.round(c.getBlue()*255);
        StringBuilder derived = new StringBuilder(" -sidebar-item-active: rgba(" + rgb + ",0.13);");
        for (String alpha : new String[]{"34", "46", "30", "42", "62"}) {
            derived.append(" -accent-shadow-").append(alpha).append(": rgba(")
                    .append(rgb).append(",0.").append(alpha).append(");");
        }
        return ".root {" + derived + " -accent: " + hex + "; -accent-hover: derive(" + hex + ", 18%);"
                + " -accent-pressed: derive(" + hex + ", -18%); -accent-muted: derive(" + hex + ", -35%);"
                + " -accent-soft: rgba(" + Math.round(c.getRed()*255) + "," + Math.round(c.getGreen()*255)
                + "," + Math.round(c.getBlue()*255) + ",0.14); -accent-contrast: " + contrast
                + "; -accent-text: derive(" + hex + "," + (dark ? "30%" : "-30%") + ");"
                + " -border-focus: " + hex + "; -accent-gradient: linear-gradient(to bottom right, "
                + hex + ", derive(" + hex + ", 25%)); }";
    }

    public ThemeManager(String initialThemeId) {
        this.current = Theme.fromId(initialThemeId);
    }

    public Theme current() {
        return current;
    }

    public String currentId() {
        return current.id();
    }

    /**
     * Applies the given theme to a scene, replacing any previously applied
     * ScreenRecorder stylesheets.
     */
    public void apply(Scene scene, Theme theme) {
        if (scene == null) {
            return;
        }
        current = theme;
        scene.getStylesheets().clear();
        addSheet(scene, "base.css");
        addSheet(scene, theme.id() + ".css");
        addSheet(scene, "components.css");
        applySystemFont(scene);
        scene.getStylesheets().add(FONT_SHEET_PREFIX + Base64.getEncoder().encodeToString(
                accentCss(accentColor, theme == Theme.DARK).getBytes(StandardCharsets.UTF_8)));
        Log.debug("Applied theme: " + theme.id());
    }

    /**
     * Pins the platform's real UI font onto {@code .root}.
     *
     * <p>Implemented as a generated data-URI stylesheet rather than
     * {@code root.setStyle(...)}. JavaFX does <em>not</em> propagate an inline
     * style from a parent to its children, so an inline font declaration only
     * affected the root node itself; child controls kept their own font and
     * Chinese text fell back to SimSun. A matched CSS rule does participate in
     * inheritance, so a stylesheet is required here.
     *
     * <p>Appended last so it wins over the {@code base.css} fallback stack,
     * and re-added on every theme change (the sheet list is cleared first, so
     * no duplicates accumulate).
     */
    private void applySystemFont(Scene scene) {
        String rule = SystemFont.rootCssRule();
        String dataUri = FONT_SHEET_PREFIX + Base64.getEncoder()
                .encodeToString(rule.getBytes(StandardCharsets.UTF_8));
        scene.getStylesheets().add(dataUri);
    }

    /** Re-applies the current theme (used when a scene is created lazily). */
    public void applyCurrent(Scene scene) {
        apply(scene, current);
    }

    private void addSheet(Scene scene, String fileName) {
        try {
            URL url = ThemeManager.class.getResource(CSS_ROOT + fileName);
            if (url == null) {
                Log.warn("Stylesheet not found on classpath: " + CSS_ROOT + fileName);
                return;
            }
            scene.getStylesheets().add(Objects.requireNonNull(url).toExternalForm());
        } catch (RuntimeException e) {
            Log.warn("Failed to load stylesheet " + fileName + ": " + e.getMessage());
        }
    }

    /** Toggles between dark and light. */
    public Theme toggle(Scene scene) {
        Theme next = current == Theme.DARK ? Theme.LIGHT : Theme.DARK;
        apply(scene, next);
        return next;
    }
}

package org.firefly.screenrec.ui.theme;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.scene.text.Font;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves the platform's real UI font at runtime.
 *
 * <p>A hard-coded {@code -fx-font-family} stack is fragile: JavaFX silently
 * falls back to the generic {@code System} font when a named family is not
 * installed, so the intended look only appears on machines that happen to
 * have the first entry. Instead we probe the fonts actually available and
 * pick the best match for the current platform, falling back to the toolkit
 * default when nothing matches.
 */
public final class SystemFont {

    private SystemFont() {
    }

    /** Ordered candidates per platform — first installed family wins. */
    private static final Map<PlatformUtils.OS, String[]> CANDIDATES = new LinkedHashMap<>();

    static {
        // Candidate order matters for CJK: a family must cover BOTH Latin and
        // Han glyphs, otherwise JavaFX falls back to SimSun for Chinese text
        // while Latin text still looks right. Microsoft YaHei UI is the
        // Windows CJK UI face and covers both, so it leads.
        CANDIDATES.put(PlatformUtils.OS.WINDOWS, new String[]{
                "Microsoft YaHei UI",
                "Microsoft YaHei",
                "Segoe UI Variable Text",
                "Segoe UI",
                "Tahoma"
        });
        // macOS: PingFang SC is the modern CJK UI face (full Latin + Han).
        // Proprietary names resolve through the font registry.
        CANDIDATES.put(PlatformUtils.OS.MACOS, new String[]{
                "PingFang SC",
                "SF Pro Text",
                ".AppleSystemUIFont",
                "Helvetica Neue"
        });
        // Linux: depends on the desktop environment, so try the common CJK-
        // capable UI faces first, then fall back to plain Latin ones.
        CANDIDATES.put(PlatformUtils.OS.LINUX, new String[]{
                "Noto Sans CJK SC",
                "Source Han Sans SC",
                "WenQuanYi Micro Hei",
                "Inter",
                "Noto Sans",
                "Ubuntu",
                "Cantarell",
                "DejaVu Sans",
                "Liberation Sans"
        });
    }

    private static String cached;

    /**
     * The best available UI font family for this platform, falling back to
     * the toolkit default family when no candidate is installed.
     */
    public static synchronized String family() {
        if (cached != null) {
            return cached;
        }
        Set<String> installed = installedFamilies();
        String[] candidates = CANDIDATES.get(PlatformUtils.current());

        if (candidates != null) {
            for (String candidate : candidates) {
                if (installed.contains(candidate.toLowerCase(Locale.ROOT))) {
                    cached = candidate;
                    Log.info("UI font: " + candidate + " (platform match)");
                    return cached;
                }
            }
        }

        cached = Font.getDefault().getFamily();
        Log.info("UI font: " + cached + " (platform default)");
        return cached;
    }

    /** Lower-cased set of installed font family names. */
    private static Set<String> installedFamilies() {
        Set<String> names = new TreeSet<>();
        for (String family : Font.getFamilies()) {
            names.add(family.toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * A complete CSS rule pinning the resolved family onto {@code .root}.
     *
     * <p>This intentionally returns a <em>rule</em>, not a declaration for
     * {@code Node.setStyle(...)}: JavaFX does not inherit an inline style from
     * a parent to its children. Only a matched CSS rule participates in the
     * {@code -fx-font-family} inheritance chain, so the font must be injected
     * as a stylesheet (see {@link ThemeManager}) to reach controls.
     */
    public static String rootCssRule() {
        return ".root { -fx-font-family: \"" + family() + "\"; }";
    }

    /** Clears the memoised result (used by tests). */
    static synchronized void reset() {
        cached = null;
    }
}

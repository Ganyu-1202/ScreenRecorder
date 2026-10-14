package org.firefly.screenrec.core.i18n;

/**
 * Supported interface languages.
 *
 * <p>The {@link #id()} is the stable value persisted in settings JSON; the
 * {@link #displayName()} is what the user sees in the picker.
 */
public enum Lang {

    /** English (fallback language). */
    EN("en", "English"),

    /** Simplified Chinese. */
    ZH_CN("zh-CN", "简体中文");

    private final String id;
    private final String displayName;

    Lang(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Stable identifier used in settings persistence. */
    public String id() {
        return id;
    }

    /** Human-readable name shown in the language picker (in its own script). */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a language from its persisted id, tolerating common variants
     * (case differences, {@code zh_CN}, {@code zh-Hans}, {@code zh}).
     *
     * @param id the stored id, may be {@code null}
     * @return the matching language, or {@link #EN} when unknown
     */
    public static Lang fromId(String id) {
        if (id == null || id.isBlank()) {
            return EN;
        }
        String normalized = id.trim().toLowerCase().replace('_', '-');
        for (Lang lang : values()) {
            if (lang.id.toLowerCase().equals(normalized)) {
                return lang;
            }
        }
        // Tolerate Chinese variants that are not exactly "zh-CN".
        if (normalized.equals("zh") || normalized.startsWith("zh-")) {
            return ZH_CN;
        }
        return EN;
    }
}

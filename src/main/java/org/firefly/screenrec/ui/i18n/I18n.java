package org.firefly.screenrec.ui.i18n;

import org.firefly.screenrec.core.i18n.Lang;
import org.firefly.screenrec.core.i18n.Messages;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * JavaFX-facing facade over {@link Messages}.
 *
 * <p>Exposes the active language as an observable property so individual
 * labels can bind to it and refresh without rebuilding a whole view. Most of
 * the UI simply calls {@link #t(String)}; language switching rebuilds the
 * view tree, which is simpler and cheap for such a rare action.
 */
public final class I18n {

    private static final ObjectProperty<Lang> LANGUAGE =
            new SimpleObjectProperty<>(Lang.EN);

    private I18n() {
    }

    /** Observable active language. */
    public static ObjectProperty<Lang> languageProperty() {
        return LANGUAGE;
    }

    /** Returns the active language. */
    public static Lang language() {
        return LANGUAGE.get();
    }

    /** Switches the active language in both the binding layer and the catalogue. */
    public static void setLanguage(Lang lang) {
        Lang resolved = lang == null ? Lang.EN : lang;
        Messages.setLanguage(resolved);
        LANGUAGE.set(resolved);
    }

    /** Convenience overload accepting a persisted id (e.g. {@code "zh-CN"}). */
    public static void setLanguage(String id) {
        setLanguage(Lang.fromId(id));
    }

    /** Translates a key using the active language. */
    public static String t(String key) {
        return Messages.get(key);
    }

    /** Translates a key and substitutes positional placeholders. */
    public static String t(String key, Object... params) {
        return Messages.get(key, params);
    }

    /**
     * Creates a binding that re-evaluates whenever the language changes,
     * letting a label track translations without a rebuild.
     *
     * @param key the message key
     * @return a live binding of the translated text
     */
    public static StringBinding binding(String key) {
        return Bindings.createStringBinding(() -> Messages.get(key), LANGUAGE);
    }

    /**
     * Creates a binding for a message with positional placeholders.
     *
     * @param key    the message key
     * @param params positional values
     * @return a live binding of the formatted text
     */
    public static StringBinding binding(String key, Object... params) {
        return Bindings.createStringBinding(() -> Messages.get(key, params), LANGUAGE);
    }
}

package org.firefly.screenrec.ui;

import javafx.scene.input.KeyCombination;
import java.util.LinkedHashMap;
import java.util.Map;

/** Window-scoped accelerators. Never claims to register OS global hotkeys. */
public final class Shortcuts {
    private Shortcuts() {}
    public static Map<String, String> defaults() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("start", "Shortcut+R");
        keys.put("pause", "F9");
        keys.put("stop", "F10");
        keys.put("library", "Shortcut+L");
        keys.put("settings", "Shortcut+Comma");
        return keys;
    }
    public static Map<String, String> resolve(Map<String, String> saved) {
        Map<String, String> result = defaults();
        for (String action : result.keySet()) {
            String value = saved.get(action);
            if (value != null) {
                try { if (KeyCombination.valueOf(value) instanceof javafx.scene.input.KeyCodeCombination) result.put(action, value); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String value : result.values()) {
            if (!used.add(canonical(KeyCombination.valueOf(value)))) return defaults();
        }
        return result;
    }
    private static String canonical(KeyCombination key) {
        String platform = System.getProperty("os.name", "").toLowerCase().contains("mac") ? "Meta" : "Ctrl";
        return key.getName().replace("Shortcut", platform);
    }
    public static boolean conflicts(Map<String, String> keys, String action, KeyCombination candidate) {
        return keys.entrySet().stream().anyMatch(e -> !e.getKey().equals(action)
                && canonical(KeyCombination.valueOf(e.getValue())).equals(canonical(candidate)));
    }
}

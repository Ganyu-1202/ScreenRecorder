package org.firefly.screenrec.core.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Loads and saves {@link AppSettings} as pretty-printed JSON at
 * {@code ~/.screenrecorder/settings.json}.
 *
 * <p>Writes are atomic (temp file + move) so a crash mid-save cannot corrupt
 * the user's configuration. A corrupt file is backed up rather than deleted.
 */
public final class SettingsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;

    public SettingsStore() {
        this(PlatformUtils.settingsFile());
    }

    public SettingsStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Loads settings, returning defaults when the file is missing or unreadable.
     */
    public AppSettings load() {
        if (!Files.exists(file)) {
            Log.info("No settings file yet, using defaults: " + file);
            AppSettings defaults = new AppSettings();
            applyDerivedDefaults(defaults);
            return defaults;
        }
        try {
            String json = Files.readString(file);
            if (json.isBlank()) {
                AppSettings defaults = new AppSettings();
                applyDerivedDefaults(defaults);
                return defaults;
            }
            AppSettings settings = MAPPER.readValue(json, AppSettings.class);
            applyDerivedDefaults(settings);
            Log.info("Settings loaded from " + file);
            return settings;
        } catch (IOException e) {
            Log.error("Could not read settings, backing up and using defaults", e);
            backupCorruptFile();
            AppSettings defaults = new AppSettings();
            applyDerivedDefaults(defaults);
            return defaults;
        }
    }

    /**
     * Saves settings atomically.
     *
     * @return true on success
     */
    public boolean save(AppSettings settings) {
        if (settings == null) {
            return false;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = MAPPER.writeValueAsString(settings);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            Log.debug("Settings saved to " + file);
            return true;
        } catch (IOException e) {
            Log.error("Could not save settings", e);
            return false;
        }
    }

    /** Fills in platform-dependent defaults that must not be persisted as blank. */
    private static void applyDerivedDefaults(AppSettings s) {
        if (s.getOutputDirectory() == null || s.getOutputDirectory().isBlank()) {
            s.setOutputDirectory(PlatformUtils.defaultOutputDir().toString());
        }
        if (s.getFps() <= 0) {
            s.setFps(60);
        }
        if (s.getFileNameTemplate() == null || s.getFileNameTemplate().isBlank()) {
            s.setFileNameTemplate("Recording_%Y%m%d_%H%M%S");
        }
        if (s.getTheme() == null || s.getTheme().isBlank()) {
            s.setTheme("dark");
        }
        if (s.getFormat() == null || s.getFormat().isBlank()) {
            s.setFormat("mp4");
        }
        if (s.getQuality() == null || s.getQuality().isBlank()) {
            s.setQuality("HIGH");
        }
        if (s.getEncoder() == null || s.getEncoder().isBlank()) {
            s.setEncoder("AUTO");
        }
        if (s.getCaptureBackend() == null || s.getCaptureBackend().isBlank()) {
            s.setCaptureBackend("AUTO");
        }
        if (s.getMicGain() <= 0) {
            s.setMicGain(1.0);
        }
        if (s.getSystemGain() <= 0) {
            s.setSystemGain(1.0);
        }
        if (s.getWindowWidth() < 640) {
            s.setWindowWidth(1120);
        }
        if (s.getWindowHeight() < 480) {
            s.setWindowHeight(760);
        }
    }

    private void backupCorruptFile() {
        try {
            Path backup = file.resolveSibling(
                    file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Log.warn("Corrupt settings backed up to " + backup);
        } catch (IOException e) {
            Log.warn("Could not back up corrupt settings: " + e.getMessage());
        }
    }

    /** Serializes settings to a JSON string (used by tests and export). */
    public static Optional<String> toJson(AppSettings settings) {
        try {
            return Optional.of(MAPPER.writeValueAsString(settings));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Parses settings from a JSON string (used by tests and import). */
    public static Optional<AppSettings> fromJson(String json) {
        try {
            AppSettings s = MAPPER.readValue(json, AppSettings.class);
            applyDerivedDefaults(s);
            return Optional.of(s);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}

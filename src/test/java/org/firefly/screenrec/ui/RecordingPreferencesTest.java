package org.firefly.screenrec.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.firefly.screenrec.core.settings.AppSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordingPreferencesTest {
    @Test void detectsAllEightEdgesWithoutInterceptingContent() {
        assertEquals("N", MainWindow.edgeAt(500, 3, 1000, 700));
        assertEquals("S", MainWindow.edgeAt(500, 697, 1000, 700));
        assertEquals("W", MainWindow.edgeAt(3, 350, 1000, 700));
        assertEquals("E", MainWindow.edgeAt(997, 350, 1000, 700));
        assertEquals("NW", MainWindow.edgeAt(3, 12, 1000, 700));
        assertEquals("NE", MainWindow.edgeAt(988, 3, 1000, 700));
        assertEquals("SW", MainWindow.edgeAt(3, 688, 1000, 700));
        assertEquals("SE", MainWindow.edgeAt(997, 697, 1000, 700));
        assertNull(MainWindow.edgeAt(500, 350, 1000, 700));
        assertNull(MainWindow.edgeAt(-1, 350, 1000, 700));
    }
    @Test void settingsRoundTripAndOldFilesKeepDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AppSettings old = mapper.readValue("{}", AppSettings.class);
        assertTrue(old.isShowControlBar());
        assertFalse(old.isMinimizeOnRecording());
        assertEquals(0.5, old.getControlBarOpacity());
        old.setShowControlBar(false);
        old.setMinimizeOnRecording(true);
        old.setControlBarOpacity(0.25);
        AppSettings restored = mapper.readValue(mapper.writeValueAsString(old), AppSettings.class);
        assertFalse(restored.isShowControlBar());
        assertTrue(restored.isMinimizeOnRecording());
        assertEquals(0.25, restored.getControlBarOpacity());
    }
    @Test void discardActuallyRemovesOutput(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path output = directory.resolve("discard-test.mp4");
        java.nio.file.Files.writeString(output, "test recording content");
        assertTrue(java.nio.file.Files.exists(output));
        RecordingController.deleteDiscardedOutput(output);
        assertFalse(java.nio.file.Files.exists(output));
        RecordingController.deleteDiscardedOutput(output);
        assertFalse(java.nio.file.Files.exists(output));
    }

    @Test void accentAndShortcutsRoundTrip() throws Exception {
        AppSettings settings = new AppSettings();
        settings.setAccentColor("#3B82F6");
        var keys = Shortcuts.defaults();
        keys.put("stop", "Ctrl+Shift+S");
        settings.setShortcuts(keys);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        AppSettings loaded = mapper.readValue(mapper.writeValueAsString(settings), AppSettings.class);
        assertEquals("#3b82f6", loaded.getAccentColor());
        assertEquals("Ctrl+Shift+S", Shortcuts.resolve(loaded.getShortcuts()).get("stop"));
        settings.setAccentColor("invalid");
        assertEquals("#ff4d5e", settings.getAccentColor());
        assertEquals(Shortcuts.defaults(), Shortcuts.resolve(java.util.Map.of("stop", "invalid_key")));
        assertTrue(Shortcuts.conflicts(Shortcuts.defaults(), "stop",
                javafx.scene.input.KeyCombination.valueOf("F9")));
        if (!System.getProperty("os.name").toLowerCase().contains("mac"))
            assertTrue(Shortcuts.conflicts(Shortcuts.defaults(), "stop",
                    javafx.scene.input.KeyCombination.valueOf("Ctrl+R")));
    }

    @Test void opacityRejectsInvalidValues() {
        AppSettings settings = new AppSettings();
        settings.setControlBarOpacity(Double.NaN);
        assertEquals(0.5, settings.getControlBarOpacity());
        settings.setControlBarOpacity(-1);
        assertEquals(0.1, settings.getControlBarOpacity());
        settings.setControlBarOpacity(2);
        assertEquals(1, settings.getControlBarOpacity());
    }
}

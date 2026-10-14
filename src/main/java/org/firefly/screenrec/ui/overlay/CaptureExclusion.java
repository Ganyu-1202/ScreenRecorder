package org.firefly.screenrec.ui.overlay;

import java.nio.file.*;
import org.firefly.screenrec.util.Log;

/** In-process Win32 bridge: SetWindowDisplayAffinity cannot protect another process's window. */
public final class CaptureExclusion {
    private static boolean loaded;
    private CaptureExclusion() {}
    private static native int applyNative(String title, boolean excluded);
    public static synchronized boolean apply(String title, boolean excluded) {
        if (!System.getProperty("os.name").startsWith("Windows")) return false;
        try {
            if (!loaded) {
                Path code = Path.of(CaptureExclusion.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path base = Files.isDirectory(code) ? code : code.getParent();
                Path dll = base.resolve("ffmpeg/win/screenrec-capture.dll");
                if (!Files.isRegularFile(dll)) dll = base.getParent().resolve("ffmpeg/win/screenrec-capture.dll");
                if (!Files.isRegularFile(dll)) dll = Path.of("ffmpeg/win/screenrec-capture.dll").toAbsolutePath();
                System.load(dll.toAbsolutePath().toString());
                loaded = true;
            }
            int error = applyNative(title, excluded);
            if (error != 0) Log.warn("Floating bar capture exclusion failed: Win32 error " + error);
            else Log.info("Floating bar capture exclusion=" + excluded);
            return error == 0;
        } catch (Exception | LinkageError e) {
            Log.warn("Capture exclusion unavailable: " + e.getMessage());
            return false;
        }
    }
}

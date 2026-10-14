package org.firefly.screenrec;

import org.junit.jupiter.api.Test;
import org.firefly.screenrec.util.WindowsFileReveal;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class WindowsFileRevealTest {
    @Test void pathIsEncodedInsteadOfInterpolatedIntoShellSyntax() {
        Path path = Path.of("target", "中文目录 with spaces", "video's, clip.mp4");
        String script = WindowsFileReveal.script(path);
        String encoded = Base64.getEncoder().encodeToString(
                path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(script.contains("FromBase64String('" + encoded + "')"));
        assertFalse(script.contains("video's, clip.mp4"));
        assertTrue(script.contains("SHOpenFolderAndSelectItems($item, 0"));
        assertTrue(script.contains("finally { [ScreenRecorderFileReveal]::ILFree($item) }"));
    }
}

package org.firefly.screenrec.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.io.IOException;

/** Uses the Windows Shell API rather than Explorer's ambiguous /select syntax. */
public final class WindowsFileReveal {
    private WindowsFileReveal() {}

    public static String script(Path file) {
        String encoded = Base64.getEncoder().encodeToString(
                file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        return """
                $ErrorActionPreference = 'Stop'
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class ScreenRecorderFileReveal {
                    [DllImport("shell32.dll", CharSet=CharSet.Unicode)]
                    public static extern IntPtr ILCreateFromPathW(string path);
                    [DllImport("shell32.dll")]
                    public static extern int SHOpenFolderAndSelectItems(IntPtr item, uint count, IntPtr children, uint flags);
                    [DllImport("shell32.dll")]
                    public static extern void ILFree(IntPtr item);
                }
                '@
                $path = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'))
                $item = [ScreenRecorderFileReveal]::ILCreateFromPathW($path)
                if ($item -eq [IntPtr]::Zero) { throw 'Cannot resolve file in Windows Shell' }
                try {
                    $hr = [ScreenRecorderFileReveal]::SHOpenFolderAndSelectItems($item, 0, [IntPtr]::Zero, 0)
                    if ($hr -lt 0) { [Runtime.InteropServices.Marshal]::ThrowExceptionForHR($hr) }
                } finally { [ScreenRecorderFileReveal]::ILFree($item) }
                """.formatted(encoded);
    }

    public static void reveal(Path file) throws IOException, InterruptedException {
        String command = Base64.getEncoder().encodeToString(script(file).getBytes(StandardCharsets.UTF_16LE));
        var result = ProcessRunner.run(java.util.List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                "-STA", "-EncodedCommand", command), 15000);
        if (!result.ok()) throw new IOException("Windows file reveal failed: " + result.stderr());
    }
}

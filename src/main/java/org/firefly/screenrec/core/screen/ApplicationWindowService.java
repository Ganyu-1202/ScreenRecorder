package org.firefly.screenrec.core.screen;

import org.firefly.screenrec.core.recorder.CaptureSource;
import org.firefly.screenrec.util.ProcessRunner;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Visible main application windows on Windows. Enumerated off the FX thread. */
public final class ApplicationWindowService {
    private ApplicationWindowService() {}
    public record Window(String process, String title, int x, int y, int width, int height) {
        public CaptureSource source() { return CaptureSource.window(title, x, y, width, height); }
        @Override public String toString() { return process + " · " + title; }
    }
    private static List<Window> listX11() throws java.io.IOException {
        if (org.firefly.screenrec.util.PlatformUtils.isWayland()) return List.of();
        var ffmpeg = org.firefly.screenrec.core.recorder.FfmpegLocator.create().findFfmpeg();
        if (ffmpeg.isEmpty()) return List.of();
        var helper = ffmpeg.get().resolveSibling("x11-windows");
        if (!java.nio.file.Files.isExecutable(helper)) return List.of();
        var result = ProcessRunner.run(List.of(helper.toString()), 8000);
        if (!result.ok()) throw new java.io.IOException("X11 window enumeration failed: " + result.stderr());
        List<Window> windows = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            String[] p = line.split("\\|", -1);
            if (p.length != 6) continue;
            try { windows.add(new Window(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Integer.parseInt(p[5]))); } catch (NumberFormatException ignored) { }
        }
        windows.sort(Comparator.comparing(Window::toString, String.CASE_INSENSITIVE_ORDER));
        return windows;
    }

    public static List<Window> list() throws java.io.IOException {
        if (org.firefly.screenrec.util.PlatformUtils.isLinux()) return listX11();
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) return List.of();
        String script = """
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
            Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WindowBounds { [StructLayout(LayoutKind.Sequential)] public struct Rect { public int L,T,R,B; } [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr c); [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h,int a,out Rect r,int size); [DllImport("user32.dll")] public static extern int GetSystemMetrics(int n); [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out Rect r); [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h); [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h); }'
            [void][WindowBounds]::SetThreadDpiAwarenessContext([IntPtr](-4))
            Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -ne '' } | ForEach-Object {
              $h=$_.MainWindowHandle; $r=New-Object WindowBounds+Rect
              if ([WindowBounds]::IsWindowVisible($h) -and ![WindowBounds]::IsIconic($h) -and [WindowBounds]::GetWindowRect($h,[ref]$r) -and ($r.R-$r.L) -gt 1 -and ($r.B-$r.T) -gt 1) {
                $visible=New-Object WindowBounds+Rect
                if ([WindowBounds]::DwmGetWindowAttribute($h,9,[ref]$visible,16) -eq 0) { $r=$visible }
                $vx=[WindowBounds]::GetSystemMetrics(76); $vy=[WindowBounds]::GetSystemMetrics(77)
                $r.L=[Math]::Max($r.L,$vx); $r.T=[Math]::Max($r.T,$vy)
                $r.R=[Math]::Min($r.R,$vx+[WindowBounds]::GetSystemMetrics(78)); $r.B=[Math]::Min($r.B,$vy+[WindowBounds]::GetSystemMetrics(79))
                if (($r.R-$r.L) -lt 2 -or ($r.B-$r.T) -lt 2) { return }
                $title=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.MainWindowTitle))
                Write-Output ($_.ProcessName+'|'+$title+'|'+$r.L+'|'+$r.T+'|'+($r.R-$r.L)+'|'+($r.B-$r.T))
              }
            }
            """;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        var result = ProcessRunner.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded), 15000);
        if (!result.ok()) throw new IllegalStateException("Window enumeration failed: " + result.stderr());
        List<Window> windows = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            String[] p = line.split("\\|", -1);
            if (p.length != 6) continue;
            try { windows.add(new Window(p[0], new String(Base64.getDecoder().decode(p[1]), StandardCharsets.UTF_8),
                    Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]))); }
            catch (IllegalArgumentException ignored) {}
        }
        windows.sort(Comparator.comparing(Window::toString, String.CASE_INSENSITIVE_ORDER));
        return windows;
    }
}

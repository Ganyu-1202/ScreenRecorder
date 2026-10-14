package org.firefly.screenrec.core.screen;
import org.firefly.screenrec.util.ProcessRunner;
import org.firefly.screenrec.util.Log;
import javafx.geometry.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class WindowsDisplayBounds {
 private static List<Rectangle2D> cache = List.of();
 private static long checked;
 static synchronized List<Rectangle2D> read() {
  if (!System.getProperty("os.name").startsWith("Windows")) return List.of();
  if (System.nanoTime()-checked < 60_000_000_000L) return cache;
  checked=System.nanoTime();
  String script="""
   Add-Type 'using System; using System.Runtime.InteropServices; public class DisplayDpi { [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr c); }'
   [void][DisplayDpi]::SetThreadDpiAwarenessContext([IntPtr](-4))
   Add-Type -AssemblyName System.Windows.Forms
   [System.Windows.Forms.Screen]::AllScreens | Sort-Object @{Expression={-not $_.Primary}},@{Expression={$_.Bounds.X}},@{Expression={$_.Bounds.Y}} | ForEach-Object { $b=$_.Bounds; Write-Output ($b.X.ToString()+'|'+$b.Y+'|'+$b.Width+'|'+$b.Height) }
   """;
  try {
   var result=ProcessRunner.run(List.of("powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))),15000);
   List<Rectangle2D> list=new ArrayList<>();
   if(!result.ok()) throw new IllegalStateException(result.stderr());
   for(String line:result.stdout().split("\\R")) {
    String[] p=line.split("\\|"); if(p.length==4) list.add(new Rectangle2D(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3])));
   }
   cache=List.copyOf(list);
   Log.info("Native physical displays: " + cache);
  } catch(Exception e) { Log.warn("Native display bounds unavailable: "+e.getMessage()); cache=List.of(); }
  return cache;
 }
}

package org.firefly.screenrec;

import java.nio.file.*;
import java.util.List;

/**
 * Compares the three realistic stop strategies against the real FFmpeg binary
 * to prove which one produces a playable MP4 (moov atom present).
 */
public class StopStrategyTest {
    public static void main(String[] args) throws Exception {
        Path ffmpeg = Paths.get(args[0]).toAbsolutePath();
        String strategy = args[1];
        Path out = Paths.get(args[2]).toAbsolutePath();
        int seconds = Integer.parseInt(args[3]);
        Files.deleteIfExists(out);

        List<String> cmd = List.of(ffmpeg.toString(),
                "-hide_banner", "-loglevel", "warning",
                "-f", "gdigrab", "-framerate", "30", "-draw_mouse", "1",
                "-offset_x", "0", "-offset_y", "0", "-video_size", "1920x1080",
                "-i", "desktop",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast",
                "-crf", "20", "-g", "60",
                "-filter_complex", "[0:v]fps=30,format=yuv420p[vout]",
                "-map", "[vout]", "-movflags", "+faststart", "-progress", "pipe:1",
                out.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread drain = new Thread(() -> {
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { }
            } catch (Exception ignored) { }
        });
        drain.setDaemon(true);
        drain.start();

        Thread.sleep(seconds * 1000L);

        switch (strategy) {
            case "q" -> {
                // The canonical FFmpeg graceful stop: write 'q' to stdin.
                try (var os = p.getOutputStream()) {
                    os.write('q');
                    os.flush();
                }
                System.out.println("sent q");
            }
            case "close" -> {
                p.getOutputStream().close();
                System.out.println("closed stdin");
            }
            case "destroy" -> {
                p.destroy();
                System.out.println("destroyed");
            }
            default -> throw new IllegalArgumentException("strategy: q|close|destroy");
        }

        boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("exited=" + done + " code=" + (done ? p.exitValue() : "n/a"));
        System.out.println("size=" + (Files.exists(out) ? Files.size(out) : -1));
    }
}

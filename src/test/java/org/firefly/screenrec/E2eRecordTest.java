package org.firefly.screenrec;

import org.firefly.screenrec.core.recorder.*;
import java.nio.file.*;
import java.util.List;

/**
 * End-to-end check: build the real capture command, run it, stop it gracefully
 * by closing stdin (exactly how the app stops a recording), then inspect output.
 */
public class E2eRecordTest {
    public static void main(String[] args) throws Exception {
        Path ffmpeg = Paths.get(args[0]).toAbsolutePath();
        Path out = Paths.get(args[1]).toAbsolutePath();
        int seconds = Integer.parseInt(args[2]);
        Files.deleteIfExists(out);

        CaptureSource src = CaptureSource.monitor("0", "Primary", 0, 0, 1920, 1080, 1.0);
        RecordingProfile profile = new RecordingProfile.Builder()
                .source(src)
                .captureBackend(CaptureBackend.GDIgrab)
                .encoder(EncoderBackend.LIBX264)
                .outputSize(1920, 1080)
                .fps(30)
                .quality(VideoQuality.HIGH)
                .format(OutputFormat.MP4)
                .outputFile(out)
                .drawMouseCursor(true)
                .build();

        List<String> cmd = new FfmpegCommandBuilder(ffmpeg).build(profile);
        System.out.println("CMD: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain output so the process never blocks on a full pipe.
        Thread drain = new Thread(() -> {
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* discard */ }
            } catch (Exception ignored) { }
        });
        drain.setDaemon(true);
        drain.start();

        Thread.sleep(seconds * 1000L);
        // Graceful stop: close stdin so FFmpeg finalizes the container.
        p.getOutputStream().close();
        boolean done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); }
        System.out.println("gracefulExit=" + p.exitValue());
        long size = Files.exists(out) ? Files.size(out) : -1;
        System.out.println("size=" + size);
    }
}

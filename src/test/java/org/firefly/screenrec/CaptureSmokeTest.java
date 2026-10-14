package org.firefly.screenrec;

import org.firefly.screenrec.core.recorder.CaptureBackend;
import org.firefly.screenrec.core.recorder.EncoderBackend;
import org.firefly.screenrec.core.recorder.FfmpegCommandBuilder;
import org.firefly.screenrec.core.recorder.OutputFormat;
import org.firefly.screenrec.core.recorder.RecordingProfile;
import org.firefly.screenrec.core.recorder.VideoQuality;
import org.firefly.screenrec.core.recorder.CaptureSource;
import org.firefly.screenrec.core.screen.ScreenInfo;

import java.nio.file.*;
import java.util.List;

/** Standalone smoke test: builds and runs a real capture command. */
public class CaptureSmokeTest {
    public static void main(String[] args) throws Exception {
        Path ffmpeg = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        int seconds = Integer.parseInt(args[2]);

        // Synthetic 1920x1080 primary monitor at origin.
        ScreenInfo screen = new ScreenInfo(0, "0", "Primary", 0, 0, 1920, 1080,
                1920, 1080, 1.0, 1.0, true);

        CaptureSource source = CaptureSource.monitor("0", "Primary", 0, 0, 1920, 1080, 1.0);
        RecordingProfile profile = new RecordingProfile.Builder()
                .source(source)
                .captureBackend(CaptureBackend.DDAGrab)
                .encoder(EncoderBackend.LIBX264)
                .outputSize(1920, 1080)
                .fps(30)
                .quality(VideoQuality.HIGH)
                .format(OutputFormat.MP4)
                .outputFile(out)
                .drawMouseCursor(true)
                .build();

        List<String> cmd = new FfmpegCommandBuilder(ffmpeg).build(profile);
        // Bound the test duration.
        cmd.add("-t");
        cmd.add(String.valueOf(seconds));

        System.out.println("CMD: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 40) {
                System.out.println("[ffmpeg] " + line);
            }
        }
        int code = p.waitFor();
        System.out.println("exit=" + code);
        System.out.println("exists=" + Files.exists(out)
                + " size=" + (Files.exists(out) ? Files.size(out) : 0));
    }
}

package org.firefly.screenrec;

import org.firefly.screenrec.core.recorder.*;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives the REAL RecordingSession end to end: start a capture, let it run,
 * call stop(), and report whether the produced file is playable.
 */
public class SessionE2eTest {
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
                .fps(30)
                .quality(VideoQuality.HIGH)
                .format(OutputFormat.MP4)
                .outputFile(out)
                .drawMouseCursor(true)
                .build();

        CountDownLatch finished = new CountDownLatch(1);
        RecordingSession.Listener listener = new RecordingSession.Listener() {
            @Override public void onStateChanged(RecordingState state) {
                System.out.println("state=" + state);
            }
            @Override public void onStats(RecordingStats.Snapshot stats) { }
            @Override public void onLog(String line) { }
            @Override public void onFinished(Path file, java.time.Duration duration, boolean success) {
                System.out.println("onFinished file=" + file + " dur=" + duration + " ok=" + success);
                finished.countDown();
            }
        };

        RecordingSession session = new RecordingSession(profile, ffmpeg, listener);
        System.out.println("start=" + session.start());
        Thread.sleep(seconds * 1000L);

        System.out.println("stopping...");
        long t0 = System.currentTimeMillis();
        boolean clean = session.stop();
        long stopMs = System.currentTimeMillis() - t0;
        System.out.println("stop clean=" + clean + " tookMs=" + stopMs);
        finished.await(10, TimeUnit.SECONDS);

        System.out.println("finalSize=" + (Files.exists(out) ? Files.size(out) : -1));
        System.exit(0);
    }
}

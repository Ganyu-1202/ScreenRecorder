package org.firefly.screenrec;

import org.firefly.screenrec.core.media.*;
import org.firefly.screenrec.util.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class FfmpegPreviewPlayerTest {
    @TempDir Path temp;
    @Test void commandHasBoundedPreviewAndNoWholeFileTranscode() {
        var cmd = FfmpegPreviewPlayer.videoCommand(Path.of("ffmpeg"), Path.of("clip with spaces.mkv"), 1250, false);
        assertTrue(cmd.contains("rawvideo"));
        assertTrue(cmd.contains("bgra"));
        assertTrue(cmd.contains("1.250"));
        assertTrue(cmd.contains("clip with spaces.mkv"));
        assertTrue(cmd.contains("-frames:v"));
        assertFalse(cmd.contains("libx264"));
        assertTrue(FfmpegPreviewPlayer.audioCommand(Path.of("ffmpeg"), Path.of("clip"), 0).contains("pcm_s16le"));
    }
    @Test void realDecodePauseSeekAndCloseWithoutAudioOutput() throws Exception {
        String binary = System.getProperty("preview.ffmpeg");
        assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)), "Set preview.ffmpeg for integration test");
        Path file = temp.resolve("synthetic clip.mkv");
        assertTrue(ProcessRunner.run(List.of(binary, "-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=320x180:rate=25", "-t", "3", "-c:v", "libx264", "-y", file.toString()), 10000).ok());
        var info = new MediaInfo(file, file.getFileName().toString(), Files.size(file), 3000, 320, 180,
                25, "h264", null, Instant.now());
        List<String> errors = new CopyOnWriteArrayList<>();
        try (var player = new FfmpegPreviewPlayer(Path.of(binary), info, errors::add)) {
            player.play();
            var frame = awaitFrame(player);
            assertEquals(640 * 360 * 4, frame.bgra().length);
            assertTrue(player.isPlaying());
            player.pause();
            double paused = player.positionMillis();
            Thread.sleep(150);
            assertEquals(paused, player.positionMillis(), 40);
            player.seek(1500);
            frame = awaitFrame(player);
            assertEquals(1500, frame.millis(), 45);
            assertFalse(player.isPlaying());
            player.setHighResolution(true);
            frame = awaitFrame(player);
            assertEquals(320, frame.width());
            assertEquals(180, frame.height());
            assertFalse(player.isPlaying());
            player.setHighResolution(false);
            frame = awaitFrame(player);
            assertEquals(640, frame.width());
            assertEquals(360, frame.height());
            player.play();
            awaitFrame(player);
            player.close();
            assertFalse(player.isPlaying());
            assertNull(player.pollFrame());
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }
    @Test void audioDecodesToPcmWithoutSystemCodec() throws Exception {
        String binary = System.getProperty("preview.ffmpeg");
        assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)));
        Path file = temp.resolve("tone.m4a");
        assertTrue(ProcessRunner.run(List.of(binary, "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:a", "aac", "-y", file.toString()), 10000).ok());
        Process process = new ProcessBuilder(FfmpegPreviewPlayer.audioCommand(Path.of(binary), file, 0)).start();
        byte[] pcm = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertTrue(pcm.length >= 44100 * 4 * 0.9, "Decoded PCM bytes: " + pcm.length);
        assertEquals(0, pcm.length % 4);
        boolean nonzero = false;
        for (byte b : pcm) if (b != 0) { nonzero = true; break; }
        assertTrue(nonzero);
    }

    private FfmpegPreviewPlayer.Frame awaitFrame(FfmpegPreviewPlayer player) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            var frame = player.pollFrame();
            if (frame != null) return frame;
            Thread.sleep(20);
        }
        fail("Decoder did not produce a frame"); return null;
    }
}

package org.firefly.screenrec;

import org.firefly.screenrec.core.recorder.*;
import org.firefly.screenrec.util.PlatformUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class LinuxRecordingTest {
    @TempDir Path temp;
    private RecordingProfile profile(CaptureBackend backend) {
        return RecordingProfile.builder().source(CaptureSource.region(0,0,320,240))
                .captureBackend(backend).encoder(EncoderBackend.LIBX264)
                .outputFile(temp.resolve("record.mp4")).countdownSeconds(0).build();
    }
    @Test void waylandDetectionDoesNotMistakeXwaylandForX11() {
        assertTrue(PlatformUtils.isWaylandSession(Map.of("XDG_SESSION_TYPE", "wayland", "DISPLAY", ":0")));
        assertTrue(PlatformUtils.isWaylandSession(Map.of("WAYLAND_DISPLAY", "wayland-0")));
        assertFalse(PlatformUtils.isWaylandSession(Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":1")));
    }
    @Test void nativeSizeX11HasNoEmptyFilterStage() {
        assumeTrue(PlatformUtils.isLinux() && !PlatformUtils.isWayland());
        var cmd = new FfmpegCommandBuilder(Path.of("ffmpeg")).build(profile(CaptureBackend.X11grab));
        assertTrue(cmd.contains("x11grab"));
        assertTrue(cmd.contains("[0:v]fps=30,format=yuv420p[vout]"));
        assertFalse(String.join(" ",cmd).contains("[0:v],"));
    }
    @Test void portalRequiresAnAuthorizedInput() {
        assumeTrue(PlatformUtils.isLinux());
        assertThrows(IllegalStateException.class, () -> new FfmpegCommandBuilder(Path.of("ffmpeg"))
                .build(profile(CaptureBackend.Portal)));
        Path fifo = temp.resolve("video.y4m");
        var cmd = new FfmpegCommandBuilder(Path.of("ffmpeg")).portalInput(fifo).build(profile(CaptureBackend.Portal));
        assertTrue(cmd.contains("yuv4mpegpipe")); assertTrue(cmd.contains(fifo.toString()));
        assertFalse(cmd.contains("kmsgrab")); assertFalse(cmd.contains("x11grab"));
        assertTrue(String.join(" ",cmd).contains("crop=320:240:0:0"));
    }
    @Test void bundledFfprobeIsAccepted() {
        assumeTrue(PlatformUtils.isLinux() && Files.isExecutable(Path.of("ffmpeg/linux/ffprobe")));
        Path probe = Path.of("ffmpeg/linux/ffprobe").toAbsolutePath();
        assertEquals(probe, FfmpegLocator.withExplicit(null, probe).findFfprobe().orElseThrow());
    }
    @Test void recordX11AndFinalizeMp4WithProjectEngine() throws Exception {
        assumeTrue(Boolean.getBoolean("screenrec.linux.integration"));
        assumeTrue(PlatformUtils.isLinux() && !PlatformUtils.isWayland());
        Path ffmpeg = Path.of("ffmpeg/linux/ffmpeg").toAbsolutePath();
        assertTrue(Files.isExecutable(ffmpeg));
        RecordingSession session = new RecordingSession(profile(CaptureBackend.X11grab), ffmpeg, null);
        try {
            assertTrue(session.start());
            Thread.sleep(1800);
            assertTrue(session.stopAndAwaitExit(6000));
            for (int i=0; i<50 && session.state()!=RecordingState.FINISHED; i++) Thread.sleep(100);
            assertEquals(RecordingState.FINISHED, session.state(), () -> String.join("\n",session.recentLog()));
            assertTrue(session.outputExists());
            var probe = org.firefly.screenrec.util.ProcessRunner.run(java.util.List.of(
                    ffmpeg.resolveSibling("ffprobe").toString(), "-v", "error", "-show_entries", "stream=codec_name,width,height",
                    "-of", "default=nw=1", session.profile().outputFile().toString()), 10000);
            assertTrue(probe.ok()); assertTrue(probe.stdout().contains("codec_name=h264"));
            assertTrue(probe.stdout().contains("width=320"));
        } finally { if(session.isActive()) session.stopAndAwaitExit(6000); }
    }
}

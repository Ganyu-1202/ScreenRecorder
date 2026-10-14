package org.firefly.screenrec.core.media;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Streaming preview. No JavaFX media, native codec DLLs or whole-file transcode. */
public final class FfmpegPreviewPlayer implements AutoCloseable {
    public static final int WIDTH = 640, HEIGHT = 360, FPS = 25;
    public record Frame(byte[] bgra, double millis, int width, int height) {}
    private final Path ffmpeg, source;
    private final double durationMs;
    private final boolean hasAudio;
    private final Consumer<String> error;
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private int previewWidth = WIDTH, previewHeight = HEIGHT;
    private final int sourceWidth, sourceHeight;
    private volatile Session session;
    private volatile double position;
    private volatile boolean playing, closed;

    public FfmpegPreviewPlayer(Path ffmpeg, MediaInfo info, Consumer<String> error) {
        this.ffmpeg = ffmpeg; source = info.path(); durationMs = Math.max(0, info.durationMs());
        hasAudio = info.hasAudio(); this.error = error;
        sourceWidth = info.width(); sourceHeight = info.height();
    }
    public boolean isPlaying() { return playing; }
    public double positionMillis() { return position; }
    public Frame pollFrame() { return latest.getAndSet(null); }
    public synchronized void play() {
        if (closed || playing) return;
        if (durationMs > 0 && position >= durationMs - 80) position = 0;
        start(true);
    }
    public synchronized void pause() { playing = false; stopSession(); }
    public synchronized void seek(double millis) {
        if (closed) return;
        position = Math.max(0, Math.min(durationMs > 0 ? Math.max(0, durationMs - 40) : Double.MAX_VALUE, millis));
        boolean resume = playing;
        stopSession(); start(resume);
    }
    public synchronized void setHighResolution(boolean high) {
        int width = WIDTH, height = HEIGHT;
        if (high && sourceWidth > 0 && sourceHeight > 0) {
            double scale = Math.min(1, Math.min(2560.0 / sourceWidth, 1600.0 / sourceHeight));
            width = Math.max(2, ((int) (sourceWidth * scale) / 2) * 2);
            height = Math.max(2, ((int) (sourceHeight * scale) / 2) * 2);
        }
        if (previewWidth == width && previewHeight == height) return;
        previewWidth = width; previewHeight = height;
        boolean resume = playing;
        stopSession(); start(resume);
    }
    private void start(boolean continuous) {
        latest.set(null);
        Session next = new Session(position, continuous);
        session = next; playing = continuous;
        daemon("preview-video", next::video);
        if (continuous && hasAudio) daemon("preview-audio", next::audio);
    }
    private void stopSession() {
        Session old = session; session = null;
        if (old != null) old.stop();
    }
    @Override public synchronized void close() {
        closed = true; playing = false; stopSession(); latest.set(null);
    }
    private static void daemon(String name, Runnable runnable) {
        Thread t = new Thread(runnable, name); t.setDaemon(true); t.start();
    }
    public static List<String> videoCommand(Path ffmpeg, Path source, double ms, boolean continuous) {
        return videoCommand(ffmpeg, source, ms, continuous, WIDTH, HEIGHT);
    }
    private static List<String> videoCommand(Path ffmpeg, Path source, double ms, boolean continuous, int width, int height) {
        List<String> cmd = inputCommand(ffmpeg, source, ms);
        cmd.addAll(List.of("-map", "0:v:0", "-an", "-vf",
                "fps=" + FPS + ",scale=" + width + ":" + height
                        + ":flags=lanczos:force_original_aspect_ratio=decrease,pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2",
                "-pix_fmt", "bgra", "-threads", "1"));
        if (!continuous) cmd.addAll(List.of("-frames:v", "1"));
        cmd.addAll(List.of("-f", "rawvideo", "pipe:1")); return cmd;
    }
    public static List<String> audioCommand(Path ffmpeg, Path source, double ms) {
        List<String> cmd = inputCommand(ffmpeg, source, ms);
        cmd.addAll(List.of("-map", "0:a:0", "-vn", "-ac", "2", "-ar", "44100",
                "-c:a", "pcm_s16le", "-f", "s16le", "pipe:1")); return cmd;
    }
    private static List<String> inputCommand(Path ffmpeg, Path source, double ms) {
        return new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
                "-ss", String.format(Locale.ROOT, "%.3f", ms / 1000), "-i", source.toString()));
    }
    private final class Session {
        final int width = previewWidth, height = previewHeight;
        final double base;
        final boolean continuous;
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean cancelled;
        volatile Process videoProcess, audioProcess;
        volatile SourceDataLine line;
        volatile long startNanos;
        volatile boolean audioClock;
        volatile double fallbackOffset;
        volatile long fallbackNanos;
        final StringBuilder diagnostics = new StringBuilder();
        Session(double base, boolean continuous) { this.base = base; this.continuous = continuous; }
        boolean active() { return !cancelled && session == this && !closed; }
        Process launch(List<String> cmd, boolean video) throws IOException {
            Process p = new ProcessBuilder(cmd).start();
            if (video) videoProcess = p; else audioProcess = p;
            if (!active()) { p.destroyForcibly(); throw new IOException("Preview cancelled"); }
            daemon("preview-log", () -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String text; while ((text = r.readLine()) != null) {
                        synchronized (diagnostics) {
                            if (diagnostics.length() < 4096) diagnostics.append(text).append('\n');
                        }
                    }
                } catch (IOException ignored) { }
            });
            return p;
        }
        double elapsed() {
            SourceDataLine l = line;
            if (audioClock && l != null) return l.getLongFramePosition() * 1000.0 / 44100;
            if (fallbackNanos > 0) return fallbackOffset + (System.nanoTime() - fallbackNanos) / 1_000_000.0;
            return startNanos == 0 ? 0 : (System.nanoTime() - startNanos) / 1_000_000.0;
        }
        void video() {
            try {
                Process p = launch(videoCommand(ffmpeg, source, base, continuous, width, height), true);
                try (InputStream in = p.getInputStream()) {
                    long index = 0;
                    while (active()) {
                        byte[] bytes = in.readNBytes(width * height * 4);
                        if (bytes.length != width * height * 4) break;
                        if (startNanos == 0) { startNanos = System.nanoTime(); started.countDown(); }
                        double due = index++ * 1000.0 / FPS;
                        while (active() && continuous && elapsed() < due) Thread.sleep(3);
                        if (!active()) return;
                        double time = base + due;
                        // Drop stale frames, keeping a single latest decoded frame for the UI.
                        if (!continuous || elapsed() - due < 120) latest.set(new Frame(bytes, time, width, height));
                        position = time;
                        if (!continuous) break;
                    }
                }
                int code = p.waitFor();
                if (active() && code != 0) throw new IOException("FFmpeg exit " + code + ": " + diagnostics);
                if (active() && continuous) {
                    // Allow the bounded audio tail to finish before reporting playback ended.
                    SourceDataLine l = line;
                    if (l != null && audioClock) l.drain();
                    if (active()) { playing = false; if (durationMs > 0) position = durationMs; }
                }
            } catch (Exception e) { if (active()) { playing = false; error.accept(e.toString()); } }
            finally { started.countDown(); stop(); }
        }
        void audio() {
            try {
                Process p = launch(audioCommand(ffmpeg, source, base), false);
                AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
                SourceDataLine output = AudioSystem.getSourceDataLine(format);
                output.open(format, 32768); line = output;
                if (!active()) { output.close(); return; }
                try (InputStream in = p.getInputStream()) {
                    byte[] chunk = new byte[4096];
                    int count = in.readNBytes(chunk, 0, chunk.length);
                    if (count == 0) return;
                    output.write(chunk, 0, count - count % 4);
                    started.await();
                    if (!active()) return;
                    output.start(); audioClock = true;
                    while (active() && (count = in.readNBytes(chunk, 0, chunk.length)) > 0) {
                        int offset = 0, length = count - count % 4;
                        while (active() && offset < length) offset += output.write(chunk, offset, length - offset);
                    }
                    if (active()) output.drain();
                }
                if (active() && p.waitFor() != 0) error.accept("Audio decode failed: " + diagnostics);
            } catch (Exception e) { if (active()) error.accept("Audio output unavailable: " + e.getMessage()); }
            finally {
                SourceDataLine l = line;
                if (audioClock && l != null) {
                    fallbackOffset = l.getLongFramePosition() * 1000.0 / 44100;
                    fallbackNanos = System.nanoTime(); audioClock = false;
                }
                if (l != null) l.close();
                Process p = audioProcess; if (p != null && p.isAlive()) p.destroyForcibly();
            }
        }
        void stop() {
            cancelled = true; started.countDown();
            Process v = videoProcess, a = audioProcess;
            if (v != null && v.isAlive()) v.destroyForcibly();
            if (a != null && a.isAlive()) a.destroyForcibly();
            SourceDataLine l = line;
            if (l != null) { l.stop(); l.flush(); l.close(); }
        }
    }
}

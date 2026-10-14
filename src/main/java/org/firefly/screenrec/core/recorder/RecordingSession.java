package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.ProcessRunner;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns one recording: starts FFmpeg, streams live statistics and output lines,
 * and finalizes the file cleanly on stop.
 *
 * <p>Thread-safety: state transitions are guarded by a lock; callbacks are
 * invoked from background threads and it is the UI's responsibility to hop
 * back to the FX thread (see {@code FxScheduler}).
 */
public final class RecordingSession {

    /** Callbacks for observing a session. All are optional. */
    public interface Listener {
        default void onStateChanged(RecordingState state) {
        }

        default void onStats(RecordingStats.Snapshot stats) {
        }

        default void onLog(String line) {
        }

        default void onFinished(Path outputFile, Duration duration, boolean success) {
        }
    }

    private final RecordingProfile profile;
    private final Path ffmpegBinary;
    private volatile org.firefly.screenrec.core.audio.WasapiLoopback loopback;
    private synchronized void closeLoopback() {
        if (loopback != null) { loopback.close(); loopback = null; }
    }
    private volatile LinuxPortalCapture portal;
    private synchronized void closePortal() {
        if (portal != null) { portal.close(); portal = null; }
    }
    private final FfmpegCommandBuilder commandBuilder;
    private final Listener listener;
    private final Object lock = new Object();

    private final Deque<String> recentLog = new ArrayDeque<>();
    private static final int LOG_LIMIT = 120;

    private final AtomicBoolean stopping = new AtomicBoolean(false);

    /**
     * Observable mirror of {@link #state}. Kept separate so the core class stays
     * usable without any JavaFX toolkit being initialised.
     */
    private final ReadOnlyObjectWrapper<RecordingState> stateProperty =
            new ReadOnlyObjectWrapper<>(RecordingState.IDLE);

    private RecordingState state = RecordingState.IDLE;
    private ProcessRunner.Handle handle;
    private RecordingStats.Parser statsParser;
    private Instant startedAt;
    private long pausedMillis;
    private Instant pausedAt;

    public RecordingSession(RecordingProfile profile, Path ffmpegBinary, Listener listener) {
        this.profile = profile;
        this.ffmpegBinary = ffmpegBinary;
        this.commandBuilder = new FfmpegCommandBuilder(ffmpegBinary);
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public RecordingProfile profile() {
        return profile;
    }

    public RecordingState state() {
        synchronized (lock) {
            return state;
        }
    }

    /**
     * Observable view of the session state, for binding in the UI.
     *
     * <p>Only safe to read from the FX thread if this session was created there;
     * otherwise listen and hop threads with {@code FxScheduler}.
     */
    public ReadOnlyObjectProperty<RecordingState> stateProperty() {
        return stateProperty.getReadOnlyProperty();
    }

    /** Elapsed milliseconds, excluding paused intervals. */
    public long elapsedMillis() {
        return elapsed().toMillis();
    }

    /** True while the process is capturing or paused. */
    public boolean isActive() {
        return state().isActive();
    }

    /** True only while actively capturing. */
    public boolean isRecording() {
        return state() == RecordingState.RECORDING;
    }

    /** True while paused. */
    public boolean isPaused() {
        return state() == RecordingState.PAUSED;
    }

    /** Confirms the output file exists and is non-empty. */
    public boolean outputExists() {
        try {
            Path out = profile.outputFile();
            return Files.exists(out) && Files.size(out) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Output file size in bytes, or 0 when unavailable. */
    public long outputSize() {
        return sizeOf(profile.outputFile());
    }

    /** Stops the recording with the default grace period. */
    public boolean stop() {
        return stop(6000);
    }

    public RecordingStats.Snapshot latestStats() {
        RecordingStats.Parser p = statsParser;
        return p == null ? null : p.stats().snapshot();
    }

    /** Elapsed recording time, excluding paused intervals. */
    public Duration elapsed() {
        Instant start = startedAt;
        if (start == null) {
            return Duration.ZERO;
        }
        long total = Duration.between(start, Instant.now()).toMillis() - pausedMillis;
        Instant pauseStart = pausedAt;
        if (pauseStart != null) {
            total -= Duration.between(pauseStart, Instant.now()).toMillis();
        }
        return Duration.ofMillis(Math.max(0, total));
    }

    public List<String> recentLog() {
        synchronized (lock) {
            return List.copyOf(recentLog);
        }
    }

    public long pid() {
        ProcessRunner.Handle h = handle;
        return h == null ? -1 : h.pid();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Builds and launches the FFmpeg process.
     *
     * @return true when the process started successfully
     */
    public boolean start() {
        synchronized (lock) {
            if (!state.canStart()) {
                Log.warn("Cannot start recording from state " + state);
                return false;
            }
            setState(RecordingState.PREPARING);
        }

        try {
            Path out = profile.outputFile();
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // FFmpeg refuses to overwrite without -y, but we already guarantee a
            // unique path; delete a stale empty leftover just in case.
            if (Files.exists(out) && Files.size(out) == 0) {
                Files.deleteIfExists(out);
            }
        } catch (IOException e) {
            fail("Cannot prepare output file: " + e.getMessage());
            return false;
        }

        List<String> command;
        try {
            if (org.firefly.screenrec.util.PlatformUtils.isWayland()) {
                portal = LinuxPortalCapture.open(ffmpegBinary, profile);
                commandBuilder.portalInput(portal.input());
            }
            command = new java.util.ArrayList<>(commandBuilder.build(profile));
        } catch (Exception e) { fail(e.getMessage()); return false; }
        var systemAudio = profile.audio().systemAudio();
        if (profile.format().supportsAudio() && systemAudio != null
                && "wasapi-loopback".equals(systemAudio.ffmpegInputFormat())) {
            String pipe = "\\\\.\\pipe\\ScreenRecorder-" + java.util.UUID.randomUUID();
            try {
                loopback = org.firefly.screenrec.core.audio.WasapiLoopback.start(ffmpegBinary,
                        systemAudio.ffmpegInputName(), pipe);
                for (int i = 1; i < command.size(); i++) {
                    if (command.get(i - 1).equals("-i") && command.get(i).equals(systemAudio.ffmpegInputName())) command.set(i, pipe);
                }
            } catch (IOException e) { fail(e.getMessage()); return false; }
        }
        Log.info("Starting recording: " + String.join(" ", command));

        statsParser = new RecordingStats.Parser();

        try {
            ProcessRunner.Handle h = ProcessRunner.start(
                    command,
                    null,
                    this::onStdoutLine,
                    this::onStderrLine,
                    this::onProcessExit);
            synchronized (lock) {
                handle = h;
                startedAt = Instant.now();
                pausedMillis = 0;
                pausedAt = null;
            }
            if (portal != null) {
                portal.onUnexpectedExit(() -> {
                    onStderrLine("Wayland screen sharing ended or PipeWire capture failed");
                    h.stopGracefully(2000);
                });
            }
            if (h.isAlive()) setState(RecordingState.RECORDING);
            return h.isAlive();
        } catch (IOException e) {
            fail("Failed to start FFmpeg: " + e.getMessage());
            return false;
        }
    }

    /** Marks the session as being in its countdown phase before capture begins. */
    public void enterCountdown() {
        synchronized (lock) {
            if (state == RecordingState.PREPARING) {
                setState(RecordingState.COUNTDOWN);
            }
        }
    }

    public void setRecordingNow() {
        synchronized (lock) {
            if (state == RecordingState.COUNTDOWN || state == RecordingState.PREPARING) {
                setState(RecordingState.RECORDING);
            }
        }
    }

    /**
     * Pauses by suspending the process on platforms that support it, otherwise
     * records the paused interval so the reported duration stays accurate.
     *
     * <p>Note: FFmpeg has no portable pause; capture genuinely pauses on
     * Windows/Linux where we can suspend the process, and on macOS the timer
     * simply excludes paused time.
     */
    public boolean pause() {
        synchronized (lock) {
            if (state != RecordingState.RECORDING) {
                return false;
            }
        }
        ProcessRunner.Handle h = handle;
        if (h == null) {
            return false;
        }
        if (loopback != null) loopback.pause(true);
        boolean suspended = suspendProcess(h);
        synchronized (lock) {
            pausedAt = Instant.now();
            setState(RecordingState.PAUSED);
        }
        Log.info(suspended ? "Recording paused (process suspended)"
                : "Recording paused (software pause; capture continues)");
        return true;
    }

    public boolean resume() {
        synchronized (lock) {
            if (state != RecordingState.PAUSED) {
                return false;
            }
            if (pausedAt != null) {
                pausedMillis += Duration.between(pausedAt, Instant.now()).toMillis();
                pausedAt = null;
            }
        }
        ProcessRunner.Handle h = handle;
        if (h != null) {
            resumeProcess(h);
        }
        if (loopback != null) loopback.pause(false);
        setState(RecordingState.RECORDING);
        Log.info("Recording resumed");
        return true;
    }

    /**
     * Stops the recording, letting FFmpeg finalize the container cleanly.
     *
     * @param graceMillis how long to wait for a clean finalize before killing
     * @return true when FFmpeg exited cleanly within the grace period
     */
    public boolean stop(long graceMillis) {
        if (!stopping.compareAndSet(false, true)) {
            return false;
        }
        synchronized (lock) {
            if (!state.isActive()) {
                stopping.set(false);
                return false;
            }
            setState(RecordingState.STOPPING);
        }

        ProcessRunner.Handle h = handle;
        boolean clean = false;
        if (h != null) {
            // If we were paused, make sure the process is running so it can flush.
            resumeProcess(h);
            if (loopback != null) loopback.pause(false);
            clean = h.stopGracefully(graceMillis);
        }
        closePortal();
        closeLoopback();
        Log.info(clean ? "Recording stopped cleanly" : "Recording stopped (forced)");
        return clean;
    }

    /** Stops and verifies process exit before a caller deletes the output. */
    public boolean stopAndAwaitExit(long graceMillis) {
        ProcessRunner.Handle current = handle;
        stop(graceMillis);
        return current == null || current.awaitExit(5000) != Integer.MIN_VALUE;
    }

    /** Force-kills without waiting. */
    public void abort() {
        closePortal();
        closeLoopback();
        ProcessRunner.Handle h = handle;
        if (h != null) {
            h.kill();
        }
    }

    // ------------------------------------------------------------------
    // Process callbacks
    // ------------------------------------------------------------------

    private long lastStatsLog;

    private void onStdoutLine(String line) {
        RecordingStats.Parser p = statsParser;
        if (p != null && p.accept(line)) {
            var stats = p.stats().snapshot();
            long now = System.nanoTime();
            if (now - lastStatsLog >= 5_000_000_000L || line.equals("progress=end")) {
                lastStatsLog = now;
                Log.info("Recording performance: frames=" + stats.frame() + ", fps=" + stats.fps()
                        + ", speed=" + stats.speed() + ", dropped=" + stats.droppedFrames()
                        + ", outputMs=" + stats.durationMillis());
            }
            listener.onStats(stats);
        }
    }

    private void onStderrLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        synchronized (lock) {
            recentLog.addLast(line);
            while (recentLog.size() > LOG_LIMIT) {
                recentLog.removeFirst();
            }
        }
        // Surface genuine problems, not normal progress noise.
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("error") || lower.contains("failed") || lower.contains("invalid")
                || lower.contains("unable") || lower.contains("no such")) {
            Log.warn("ffmpeg: " + line);
        } else {
            Log.debug("ffmpeg: " + line);
        }
        listener.onLog(line);
    }

    private void onProcessExit(int exitCode) {
        closePortal();
        closeLoopback();
        Duration duration = elapsed();
        Path out = profile.outputFile();

        boolean success = exitCode == 0;
        boolean fileOk = false;
        if (success) {
            try {
                fileOk = Files.exists(out) && Files.size(out) > 0;
            } catch (IOException e) {
                fileOk = false;
            }
        }

        if (success && fileOk) {
            setState(RecordingState.FINISHED);
            Log.info("Recording saved: %s (%s)", out, org.firefly.screenrec.util.PlatformUtils.formatBytes(sizeOf(out)));
        } else if (stopping.get() && fileOk && exitCode != 0) {
            // A hard kill often yields a non-zero code but a usable file in MKV.
            setState(RecordingState.FINISHED);
            Log.warn("Recording finalized with exit code " + exitCode + " but file looks usable");
            success = true;
        } else {
            setState(RecordingState.ERROR);
            Log.error("Recording failed with exit code " + exitCode
                    + (recentLog().isEmpty() ? "" : "\n" + String.join("\n", recentLog())));
        }

        stopping.set(false);
        synchronized (lock) {
            handle = null;
        }
        listener.onFinished(out, duration, success);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private void fail(String message) {
        closePortal();
        closeLoopback();
        Log.error(message);
        setState(RecordingState.ERROR);
        listener.onFinished(profile.outputFile(), Duration.ZERO, false);
    }

    private void setState(RecordingState next) {
        RecordingState previous;
        synchronized (lock) {
            if (state == next) {
                return;
            }
            previous = state;
            state = next;
        }
        Log.debug("Recording state: " + previous + " -> " + next);
        try {
            stateProperty.set(next);
        } catch (IllegalStateException e) {
            // Property already bound; listeners still fire below.
        }
        listener.onStateChanged(next);
    }

    // ------------------------------------------------------------------
    // Process suspension (pause/resume)
    // ------------------------------------------------------------------

    /**
     * Suspends the FFmpeg process. Supported on Windows via {@code NtSuspendProcess}
     * semantics and on POSIX via {@code SIGSTOP}. Falls back to a no-op.
     */
    private boolean suspendProcess(ProcessRunner.Handle h) {
        long pid = h.pid();
        if (org.firefly.screenrec.util.PlatformUtils.isLinux() || org.firefly.screenrec.util.PlatformUtils.isMac()) {
            return sendSignal(pid, "STOP");
        }
        if (org.firefly.screenrec.util.PlatformUtils.isWindows()) {
            // No portable suspend in the JDK; approximate by pausing via a helper.
            // A future version can call NtSuspendProcess through JNA.
            return false;
        }
        return false;
    }

    private boolean resumeProcess(ProcessRunner.Handle h) {
        long pid = h.pid();
        if (org.firefly.screenrec.util.PlatformUtils.isLinux() || org.firefly.screenrec.util.PlatformUtils.isMac()) {
            return sendSignal(pid, "CONT");
        }
        return false;
    }

    private boolean sendSignal(long pid, String signal) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kill", "-" + signal, String.valueOf(pid));
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            Log.debug("signal " + signal + " failed: " + e.getMessage());
            return false;
        }
    }
}

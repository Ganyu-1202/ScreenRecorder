package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.ProcessRunner;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

/** Owns the permission session and a private, user-only FIFO for its video. */
public final class LinuxPortalCapture implements AutoCloseable {
    private final Path directory;
    private final Process helper;
    private volatile boolean closing;
    private LinuxPortalCapture(Path directory, Process helper) {
        this.directory = directory; this.helper = helper;
    }
    public static Path helperPath(Path ffmpeg) { return ffmpeg.toAbsolutePath().resolveSibling("portal-capture"); }
    public Path input() { return directory.resolve("video.y4m"); }
    public static LinuxPortalCapture open(Path ffmpeg, RecordingProfile profile) throws IOException {
        Path exe = helperPath(ffmpeg);
        if (!Files.isExecutable(exe)) throw new IOException("Bundled Wayland portal-capture helper is missing: " + exe);
        Path dir = Files.createTempDirectory("screenrec-portal-");
        Process process = null;
        try {
            Path fifo = dir.resolve("video.y4m");
            if (!ProcessRunner.run(List.of("mkfifo", "-m", "600", fifo.toString()), 5000).ok())
                throw new IOException("Cannot create private capture FIFO");
            process = new ProcessBuilder(exe.toString(), fifo.toString(), String.valueOf(profile.fps()),
                    profile.drawMouseCursor() ? "1" : "0",
                    profile.source().kind() == CaptureSource.Kind.WINDOW ? "window" : "screen")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            Process running = process;
            CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
                try { return new BufferedReader(new InputStreamReader(running.getInputStream())).readLine(); }
                catch (IOException e) { throw new CompletionException(e); }
            });
            if (!"READY".equals(ready.get(130, TimeUnit.SECONDS))) throw new IOException("Screen sharing cancelled or denied");
            return new LinuxPortalCapture(dir, process);
        } catch (Exception e) {
            if (process != null) { process.destroy(); try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            Files.deleteIfExists(dir.resolve("video.y4m")); Files.deleteIfExists(dir);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Wayland portal: " + e.getMessage(), e);
        }
    }
    public void onUnexpectedExit(Runnable action) {
        helper.onExit().thenRun(() -> { if (!closing) action.run(); });
    }
    @Override public void close() {
        closing = true;
        helper.destroy();
        try {
            if (!helper.waitFor(5, TimeUnit.SECONDS)) {
                helper.descendants().forEach(ProcessHandle::destroyForcibly);
                helper.destroyForcibly(); helper.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(input()); Files.deleteIfExists(directory);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { }
    }
}

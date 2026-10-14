package org.firefly.screenrec.core.audio;

import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.firefly.screenrec.util.*;

/** Windows render-endpoint loopback via the bundled native helper. */
public final class WasapiLoopback implements AutoCloseable {
    private final Process process;
    private WasapiLoopback(Process process) { this.process = process; }
    public static Path helper(Path ffmpeg) { return ffmpeg.resolveSibling("wasapi-loopback.exe"); }
    public static List<AudioDevice> devices(Path ffmpeg) {
        if (ffmpeg == null || !Files.isRegularFile(helper(ffmpeg))) return List.of();
        try {
            var result = ProcessRunner.run(List.of(helper(ffmpeg).toString(), "--list"), 10000);
            if (!result.ok()) { Log.warn("WASAPI enumeration: " + result.stderr()); return List.of(); }
            return parseDevices(result.stdout());
        } catch (Exception e) { Log.warn("WASAPI enumeration failed: " + e.getMessage()); return List.of(); }
    }
    public static List<AudioDevice> parseDevices(String text) {
        List<AudioDevice> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] fields = line.split("\t", 2);
            if (fields.length != 2 || fields[0].isBlank()) continue;
            result.add(new AudioDevice("wasapi:" + fields[0], fields[1], AudioDevice.Kind.SYSTEM_OUTPUT,
                    "wasapi-loopback", fields[0], 2, 48000));
            Log.info("WASAPI playback endpoint: " + fields[1] + "; id=" + fields[0]);
        }
        return result;
    }
    public static WasapiLoopback start(Path ffmpeg, String endpoint, String pipe) throws IOException {
        Process process = new ProcessBuilder(helper(ffmpeg).toString(), endpoint, pipe).start();
        WasapiLoopback capture = new WasapiLoopback(process);
        Thread errors = new Thread(() -> {
            try (var reader = process.errorReader(StandardCharsets.UTF_8)) {
                reader.lines().forEach(line -> Log.warn("WASAPI: " + line));
            } catch (IOException ignored) { }
        }, "wasapi-errors");
        errors.setDaemon(true); errors.start();
        CompletableFuture<String> ready = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try { ready.complete(process.inputReader(StandardCharsets.UTF_8).readLine()); }
            catch (IOException e) { ready.completeExceptionally(e); }
        }, "wasapi-ready");
        waiter.setDaemon(true); waiter.start();
        try {
            if (!"READY".equals(ready.get(10, TimeUnit.SECONDS))) throw new IOException("WASAPI initialization failed");
            return capture;
        } catch (Exception e) { capture.close(); throw new IOException("Could not start WASAPI loopback", e); }
    }
    public boolean isAlive() { return process.isAlive(); }
    public void pause(boolean paused) {
        try { process.getOutputStream().write((paused ? "p\n" : "r\n").getBytes(StandardCharsets.US_ASCII)); process.getOutputStream().flush(); }
        catch (IOException e) { Log.warn("WASAPI control failed: " + e.getMessage()); }
    }
    public void close() {
        process.destroy();
        try { if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
        catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}

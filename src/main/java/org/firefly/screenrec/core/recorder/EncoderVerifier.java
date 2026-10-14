package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks whether an encoder can actually encode on this machine.
 *
 * <p>Listing encoders with {@code ffmpeg -encoders} only proves the encoder was
 * <em>compiled in</em>. Hardware encoders are routinely advertised but unusable:
 * an NVIDIA driver older than the one FFmpeg was built against reports
 * "Driver does not support the required nvenc API version", QSV needs a working
 * GPU/iGPU, and AMF needs matching AMD drivers. In all of those cases the
 * encoder shows up in the list but fails the moment a real recording starts —
 * after the user has already pressed the record button.
 *
 * <p>This probe runs a one-frame synthetic encode so the failure is discovered
 * up front, letting the app fall back to something that works instead of
 * aborting the recording.
 *
 * <p>Results are cached per FFmpeg binary because a probe costs a process
 * launch, and encoders do not change while the app is running.
 */
public final class EncoderVerifier {

    /** Frames to encode — one is enough to open the encoder and write a packet. */
    private static final int PROBE_FRAMES = 1;

    private final Path ffmpeg;
    private final Map<EncoderBackend, Boolean> cache = new LinkedHashMap<>();

    public EncoderVerifier(Path ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /**
     * Returns true when the encoder successfully produced output for a synthetic
     * frame. Never throws: any failure means "not usable".
     */
    public synchronized boolean isUsable(EncoderBackend encoder) {
        if (encoder == null || encoder == EncoderBackend.AUTO) {
            return false;
        }
        Boolean cached = cache.get(encoder);
        if (cached != null) {
            return cached;
        }
        boolean usable = probe(encoder);
        cache.put(encoder, usable);
        if (usable) {
            Log.info("Encoder usable: %s (%s)", encoder.displayName(), encoder.ffmpegName());
        } else {            Log.warn(String.format("Encoder unusable on this system, will be skipped: %s (%s)",
                    encoder.displayName(), encoder.ffmpegName()));
        }
        return usable;
    }

    /** Probes the given candidates in order and returns the first usable one. */
    public synchronized Optional<EncoderBackend> firstUsable(List<EncoderBackend> candidates) {
        for (EncoderBackend candidate : candidates) {
            if (isUsable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Runs a tiny encode through the encoder under test.
     *
     * <p>The input is a synthetic {@code testsrc} frame, so no capture device is
     * touched and the probe is safe to run before recording begins.
     */
    private boolean probe(EncoderBackend encoder) {
        if (ffmpeg == null) {
            return false;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg.toString());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-f");
        cmd.add("lavfi");
        cmd.add("-i");
        cmd.add("testsrc=size=320x240:rate=30:duration=" + (PROBE_FRAMES / 30.0));
        cmd.add("-frames:v");
        cmd.add(String.valueOf(PROBE_FRAMES));
        cmd.add("-c:v");
        cmd.add(encoder.ffmpegName());
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-f");
        cmd.add("null");
        cmd.add("-");

        try {
            ProcessRunner.Result result = ProcessRunner.run(cmd, 20_000);
            if (result.exitCode() != 0) {
                Log.debug("Encoder probe failed for " + encoder.ffmpegName()
                        + " (exit " + result.exitCode() + "): " + result.stderr());
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.debug("Encoder probe error for " + encoder.ffmpegName() + ": " + e.getMessage());
            return false;
        }
    }
}

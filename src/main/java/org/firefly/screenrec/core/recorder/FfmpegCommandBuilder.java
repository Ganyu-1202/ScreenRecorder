package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.core.audio.AudioConfig;
import org.firefly.screenrec.core.audio.AudioDevice;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import org.firefly.screenrec.util.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates a {@link RecordingProfile} into a concrete FFmpeg argument list.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Arguments are produced as a {@link List}, never a shell string, so paths
 *       containing spaces or quotes are safe.</li>
 *   <li>Inputs are added in a defined order (mic, system audio, then video) and filters address them by input index, so the audio graph is
 *       deterministic and testable.</li>
 *   <li>{@code -progress pipe:1} is used so the UI can show live statistics.</li>
 *   <li>Stopping is done by closing stdin, letting FFmpeg finalize the container
 *       cleanly rather than being killed.</li>
 * </ul>
 */
public final class FfmpegCommandBuilder {

    /** Frame rate assumed for filters that must match the capture rate. */
    private final Path ffmpegPath;

    /**
     * Cached result of the {@code hwupload_cuda} capability probe: {@code null}
     * until the first capture pipeline is built, then {@code TRUE}/{@code FALSE}.
     */
    private Boolean cudaUploadChecked;
    private Path portalInput;

    public FfmpegCommandBuilder portalInput(Path input) { this.portalInput = input; return this; }

    public FfmpegCommandBuilder(Path ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /**
     * Builds the full FFmpeg command for the given profile.
     */
    public List<String> build(RecordingProfile profile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath.toString());

        cmd.add("-hide_banner");
        // NOTE: do NOT pass -nostdin. Stopping a recording works by closing the
        // process stdin so FFmpeg finalizes the container (writes the MP4 moov
        // atom) instead of being killed mid-write. -nostdin would make FFmpeg
        // ignore that and guarantee a corrupt/truncated file.
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-stats_period");
        cmd.add("0.5");

        CaptureBackend backend = resolveCaptureBackend(profile);

        // Initialize blocking DirectShow inputs before starting live desktop capture.
        // Opening them after video starts can stall desktop updates while retaining
        // apparently healthy output FPS through duplicated frames.
        AudioConfig audio = profile.audio();
        List<String> audioFilters = new ArrayList<>();
        int micInputIndex = -1, sysInputIndex = -1, inputIndex = 0;
        if (profile.format().supportsAudio() && audio.hasMicrophone()) {
            addAudioInput(cmd, audio.microphone()); micInputIndex = inputIndex++;
        }
        if (profile.format().supportsAudio() && audio.hasSystemAudio()) {
            addAudioInput(cmd, audio.systemAudio()); sysInputIndex = inputIndex++;
        }
        int videoInputIndex = inputIndex;
        cmd.add("-thread_queue_size"); cmd.add("16");
        List<String> videoFilters = new ArrayList<>();
        switch (backend) {
            case DDAGrab -> addDdagrabInput(cmd, profile, videoFilters);
            case GDIgrab -> addGdigrabInput(cmd, profile, videoFilters);
            case AVFoundation -> addAvFoundationInput(cmd, profile, videoFilters);
            case X11grab -> addX11grabInput(cmd, profile, videoFilters);
            case Portal -> addPortalInput(cmd, profile, videoFilters);
            case KMSgrab -> addKmsgrabInput(cmd, profile, videoFilters);
            default -> addGdigrabInput(cmd, profile, videoFilters);
        }
        if (videoInputIndex != 0) {
            videoFilters.replaceAll(chain -> chain.replace("[0:v]", "[" + videoInputIndex + ":v]"));
        }

        // ---- Encoding options ----
        EncoderBackend encoder = resolveEncoder(profile);
        addVideoEncoding(cmd, profile, encoder);
        addAudioEncoding(cmd, profile, audio, micInputIndex, sysInputIndex, audioFilters);

        // ---- Filters ----
        List<String> allFilters = new ArrayList<>(videoFilters);
        allFilters.addAll(audioFilters);
        if (!allFilters.isEmpty()) {
            cmd.add("-filter_complex");
            cmd.add(String.join(";", allFilters));
        }

        // ---- Mapping ----
        cmd.add("-map");
        cmd.add("[vout]");
        if (profile.format().supportsAudio() && audio.hasAny()) {
            cmd.add("-map");
            cmd.add("[aout]");
        }

        // ---- Output options ----
        addOutputOptions(cmd, profile);

        cmd.add(profile.outputFile().toString());
        return cmd;
    } 

    // ==================================================================
    // Capture inputs
    // ==================================================================

    /**
     * Windows DXGI Desktop Duplication.
     *
     * <p>{@code ddagrab} produces D3D11 frames on the GPU. The most efficient
     * consumer is a CUDA hardware encoder ({@code h264_nvenc} / {@code hevc_nvenc}),
     * because the frames can be uploaded with {@code hwupload_cuda} and never
     * touch system memory. For any other encoder we must download the frames
     * back to system memory first.
     */
    private void addDdagrabInput(List<String> cmd, RecordingProfile profile,
                                 List<String> filters) {
        CaptureSource src = profile.source();
        int fps = profile.fps();

        cmd.add("-f");
        cmd.add("lavfi");
        cmd.add("-i");

        StringBuilder ddagrab = new StringBuilder("ddagrab=");
        ddagrab.append("framerate=").append(fps);
        ddagrab.append(":draw_mouse=").append(profile.drawMouseCursor() ? 1 : 0);
        ddagrab.append(":desktop_coords=1");
        ddagrab.append(":video_size=").append(src.width()).append('x').append(src.height());
        ddagrab.append(":offset_x=").append(src.x()).append(":offset_y=").append(src.y());
        cmd.add(ddagrab.toString());

        if (prefersCudaPipeline(profile)) {
            // GPU-only path: upload to CUDA, scale there, encode with NVENC.
            String chain = "[0:v]hwupload_cuda" + buildCudaScaleChain(profile)
                    + ",fps=" + fps + ",format=yuv420p[vout]";
            filters.add(chain);
        } else {
            // Download to system memory, then convert and encode on the CPU.
            String chain = "[0:v]hwdownload,format=bgra" + buildScaleChain(profile, true)
                    + ",fps=" + fps + ",format=yuv420p[vout]";
            filters.add(chain);
        }
    }

    /** True when the profile should keep frames on the GPU for encoding. */
    private boolean prefersCudaPipeline(RecordingProfile profile) {
        EncoderBackend encoder = profile.encoder();
        if (encoder != EncoderBackend.NVENC_H264 && encoder != EncoderBackend.NVENC_HEVC) {
            return false;
        }
        // A CUDA encoder is only useful with ddagrab when the D3D11 surface can
        // actually be shared with CUDA. That bridge is missing on plenty of
        // real machines (and in helper FFmpeg builds), in which case
        // hwupload_cuda fails with "Selected output not supported". Probe once
        // and cache the answer so we never emit a pipeline that cannot start.
        return cudaUploadAvailable();
    }

    /**
     * Probes whether {@code hwupload_cuda} can consume a {@code ddagrab} surface
     * on this machine. The result is cached for the lifetime of the builder.
     */
    private boolean cudaUploadAvailable() {
        if (cudaUploadChecked == null) {
            cudaUploadChecked = probeCudaUpload();
            Log.info("CUDA surface sharing for capture: %s", cudaUploadChecked ? "available" : "unavailable");
        }
        return cudaUploadChecked;
    }

    private boolean probeCudaUpload() {
        List<String> cmd = List.of(ffmpegPath.toString(), "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "ddagrab=framerate=30",
                "-filter_complex", "[0:v]hwupload_cuda[vout]",
                "-map", "[vout]", "-frames:v", "1", "-f", "null", "-");
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, 15_000);
            // Success needs the pipeline to actually run; exit code 0 and no
            // "not supported" style failure in the output.
            String combined = r.stdout() + "\n" + r.stderr();
            return r.exitCode() == 0 && !combined.contains("not supported");
        } catch (Exception e) {
            Log.debug("CUDA upload probe failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds the CUDA-side scaling chain (no leading comma) so frames never
     * leave the GPU. Uses {@code scale_cuda}; when no explicit output size was
     * requested the chain is empty and the native size is kept.
     */
    private String buildCudaScaleChain(RecordingProfile profile) {
        CaptureSource src = profile.source();
        int outW = profile.outputWidth();
        int outH = profile.outputHeight();
        if (outW <= 0 || outH <= 0 || (outW == src.width() && outH == src.height())) {
            return "";
        }
        return ",scale_cuda=" + outW + ":" + outH + ":format=yuv420p";
    }

    /** Windows GDI capture; simplest and most compatible. */
    private void addGdigrabInput(List<String> cmd, RecordingProfile profile,
                                 List<String> filters) {
        CaptureSource src = profile.source();
        cmd.add("-f");
        cmd.add("gdigrab");
        cmd.add("-framerate");
        cmd.add(String.valueOf(profile.fps()));
        cmd.add("-draw_mouse");
        cmd.add(profile.drawMouseCursor() ? "1" : "0");

        // Window DC capture returns black for many GPU-composited apps.
        // Capture the visible desktop rectangle instead, using physical coordinates.
        cmd.add("-offset_x"); cmd.add(String.valueOf(src.x()));
        cmd.add("-offset_y"); cmd.add(String.valueOf(src.y()));
        cmd.add("-video_size"); cmd.add(src.width() + "x" + src.height());
        cmd.add("-i"); cmd.add("desktop");

        // Build the filter chain from a list of stages so we never emit an empty
        // stage or a dangling comma (e.g. "[0:v],fps=30"), which FFmpeg rejects.
        List<String> stages = new ArrayList<>();
        String scale = buildScaleChain(profile, false);
        if (!scale.isBlank()) {
            stages.add(scale.replaceFirst("^,", ""));
        }
        stages.add("fps=" + profile.fps());
        stages.add("format=yuv420p");
        filters.add("[0:v]" + String.join(",", stages) + "[vout]");
    }

    /** macOS AVFoundation screen capture. */
    private void addAvFoundationInput(List<String> cmd, RecordingProfile profile,
                                      List<String> filters) {
        cmd.add("-f");
        cmd.add("avfoundation");
        cmd.add("-framerate");
        cmd.add(String.valueOf(profile.fps()));
        cmd.add("-capture_cursor");
        cmd.add(profile.drawMouseCursor() ? "1" : "0");
        cmd.add("-i");
        // "screenIndex:none" — video only here; audio devices are separate inputs.
        cmd.add(avFoundationScreenIndex(profile) + ":none");

        String chain = "[0:v]fps=" + profile.fps() + buildScaleChain(profile, true);
        chain += ",format=yuv420p[vout]";
        filters.add(chain);
    }

    /** Linux X11 capture. */
    private void addX11grabInput(List<String> cmd, RecordingProfile profile,
                                 List<String> filters) {
        CaptureSource src = profile.source();
        String display = System.getenv().getOrDefault("DISPLAY", ":0.0");
        cmd.add("-f");
        cmd.add("x11grab");
        cmd.add("-framerate");
        cmd.add(String.valueOf(profile.fps()));
        cmd.add("-draw_mouse");
        cmd.add(profile.drawMouseCursor() ? "1" : "0");
        cmd.add("-video_size");
        cmd.add(src.width() + "x" + src.height());
        cmd.add("-i");
        cmd.add(display + "+" + src.x() + "," + src.y());

        String chain = "[0:v]fps=" + profile.fps() + buildScaleChain(profile, true);
        chain += ",format=yuv420p[vout]";
        filters.add(chain);
    }

    private void addPortalInput(List<String> cmd, RecordingProfile profile, List<String> filters) {
        if (portalInput == null) throw new IllegalStateException("Wayland capture requires a portal session");
        cmd.addAll(List.of("-f", "yuv4mpegpipe", "-i", portalInput.toString()));
        String chain = "[0:v]fps=" + profile.fps();
        if (profile.source().kind() == CaptureSource.Kind.REGION) {
            var region = profile.source();
            chain += ",crop=" + region.evenWidth() + ":" + region.evenHeight() + ":" + region.x() + ":" + region.y();
        }
        if (profile.needsScaling()) chain += buildScaleChain(profile, true);
        chain += ",crop=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p[vout]";
        filters.add(chain);
    }

    /** Linux KMS/DRM capture (privileged). */
    private void addKmsgrabInput(List<String> cmd, RecordingProfile profile,
                                 List<String> filters) {
        cmd.add("-f");
        cmd.add("kmsgrab");
        cmd.add("-i");
        cmd.add("-");
        String chain = "[0:v]hwdownload,format=bgr0";
        chain += buildScaleChain(profile, true);
        chain += ",fps=" + profile.fps();
        chain += ",format=yuv420p[vout]";
        filters.add(chain);
    }

    private int avFoundationScreenIndex(RecordingProfile profile) {
        // AVFoundation exposes screens as indices starting at 1.
        if (profile.source().kind() == CaptureSource.Kind.MONITOR) {
            return Math.max(1, monitorIndexFor(profile.source()) + 1);
        }
        return 1;
    }

    private int monitorIndexFor(CaptureSource src) {
        // The services layer encodes the monitor ordinal into the id when possible.
        if (src.monitorId() != null) {
            try {
                String digits = src.monitorId().replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    return Integer.parseInt(digits);
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }

    /**
     * Builds the {@code scale}/{@code crop} portion of a filter chain.
     *
     * @param leadingComma when true the chain starts with a comma (append mode)
     */
    private String buildScaleChain(RecordingProfile profile, boolean leadingComma) {
        StringBuilder sb = new StringBuilder();
        boolean needScale = profile.needsScaling();
        if (profile.source().kind() == CaptureSource.Kind.WINDOW && !needScale) {
            return (leadingComma ? "," : "") + "crop=trunc(iw/2)*2:trunc(ih/2)*2:0:0";
        }
        if (needScale) {
            sb.append(leadingComma ? "," : "");
            sb.append("scale=").append(profile.outputWidth()).append(':')
                    .append(profile.outputHeight())
                    .append(":flags=bilinear");
            leadingComma = true;
        }
        // Ensure even dimensions for yuv420p even when no scaling is requested.
        if (!needScale) {
            CaptureSource src = profile.source();
            if (src.evenWidth() != src.width() || src.evenHeight() != src.height()) {
                sb.append(leadingComma ? "," : "");
                sb.append("crop=").append(src.evenWidth()).append(':').append(src.evenHeight())
                        .append(":0:0");
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // Audio inputs & encoding
    // ==================================================================

    private void addAudioInput(List<String> cmd, AudioDevice device) {
        cmd.add("-thread_queue_size"); cmd.add("64");
        if ("dshow".equals(device.ffmpegInputFormat())) {
            cmd.add("-audio_buffer_size"); cmd.add("50");
        }
        if ("wasapi-loopback".equals(device.ffmpegInputFormat())) {
            cmd.add("-probesize"); cmd.add("4096");
            cmd.add("-analyzeduration"); cmd.add("0");
            cmd.add("-f"); cmd.add("wav");
            cmd.add("-i"); cmd.add(device.ffmpegInputName());
            return;
        }
        cmd.add("-f");
        cmd.add(device.ffmpegInputFormat());
        cmd.add("-i");
        cmd.add(device.ffmpegInputName());
    }

    private void addAudioEncoding(List<String> cmd, RecordingProfile profile, AudioConfig audio,
                                  int micIndex, int sysIndex, List<String> filters) {
        if (!profile.format().supportsAudio() || !audio.hasAny()) {
            return;
        }

        if (audio.hasBoth()) {
            // Volume-adjust both inputs, then mix down.
            String micChain = "[" + micIndex + ":a]volume=" + fmt(audio.micGain()) + "[a1]";
            String sysChain = "[" + sysIndex + ":a]volume=" + fmt(audio.systemGain()) + "[a2]";
            filters.add(micChain);
            filters.add(sysChain);
            String mix = "[a1][a2]amix=inputs=2:duration=longest"
                    + ":dropout_transition=0:normalize=0[aout]";
            filters.add(mix);
        } else if (audio.hasMicrophone()) {
            filters.add("[" + micIndex + ":a]volume=" + fmt(audio.micGain())
                    + ",aformat=sample_fmts=fltp:sample_rates=" + audio.sampleRate()
                    + ":channel_layouts=" + (audio.mixToMono() ? "mono" : "stereo") + "[aout]");
        } else if (audio.hasSystemAudio()) {
            filters.add("[" + sysIndex + ":a]volume=" + fmt(audio.systemGain())
                    + ",aformat=sample_fmts=fltp:sample_rates=" + audio.sampleRate()
                    + ":channel_layouts=" + (audio.mixToMono() ? "mono" : "stereo") + "[aout]");
        }
    }

    // ==================================================================
    // Encoder & output options
    // ==================================================================

    private EncoderBackend resolveEncoder(RecordingProfile profile) {
        if (profile.encoder() != EncoderBackend.AUTO) {
            return profile.encoder();
        }
        return EncoderBackend.preferredForPlatform().get(0);
    }

    private CaptureBackend resolveCaptureBackend(RecordingProfile profile) {
        if (PlatformUtils.isWayland()) return CaptureBackend.Portal;
        CaptureBackend requested = profile.captureBackend();
        if (requested == CaptureBackend.AUTO) {
            var preferred = CaptureBackend.preferredForPlatform();
            requested = preferred.isEmpty() ? CaptureBackend.AUTO : preferred.get(0);
        }
        if (requested != CaptureBackend.DDAGrab) return requested;
        var src = profile.source();
        String source = "ddagrab=desktop_coords=1:draw_mouse=0:video_size=" + src.width() + "x" + src.height()
                + ":offset_x=" + src.x() + ":offset_y=" + src.y();
        try {
            var result = ProcessRunner.run(List.of(ffmpegPath.toString(), "-v", "error", "-f", "lavfi", "-i",
                    source, "-vf", "hwdownload,format=bgra", "-c:v", "rawvideo", "-frames:v", "1", "-f", "null", "-"), 10000);
            if (result.ok()) {
                Log.info("GPU desktop rectangle verified: " + src);
                return CaptureBackend.DDAGrab;
            }
            Log.warn("GPU rectangle unavailable; GDI fallback (cursor flicker possible): " + result.stderr());
        } catch (Exception e) {
            Log.warn("GPU rectangle probe failed; GDI fallback: " + e.getMessage());
        }
        return CaptureBackend.GDIgrab;
    }

    private void addVideoEncoding(List<String> cmd, RecordingProfile profile,
                                  EncoderBackend encoder) {
        // GIF has its own encoder and palette handling in the output stage.
        if (profile.format() == OutputFormat.GIF) {
            return;
        }
        if (encoder == EncoderBackend.AUTO) {
            encoder = EncoderBackend.preferredForPlatform().get(0);
        }
        cmd.add("-c:v");
        cmd.add(encoder.ffmpegName());
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");

        int bitrate = profile.bitrateBps();
        int maxrate = (int) (bitrate * 1.5);
        int bufsize = bitrate * 2;

        switch (encoder.family()) {
            case "nvenc" -> {
                cmd.add("-preset");
                cmd.add("p5");
                cmd.add("-tune");
                cmd.add("hq");
                cmd.add("-rc");
                cmd.add("vbr");
                cmd.add("-cq");
                cmd.add(String.valueOf(profile.quality().cq()));
                cmd.add("-b:v");
                cmd.add(bitrate + "");
                cmd.add("-maxrate");
                cmd.add(maxrate + "");
                cmd.add("-bufsize");
                cmd.add(bufsize + "");
                if (encoder.isHevc()) {
                    cmd.add("-tag:v");
                    cmd.add("hvc1");
                }
                cmd.add("-spatial-aq");
                cmd.add("1");
            }
            case "qsv" -> {
                cmd.add("-preset");
                cmd.add("medium");
                cmd.add("-global_quality");
                cmd.add(String.valueOf(profile.quality().cq() * 2));
                cmd.add("-b:v");
                cmd.add(bitrate + "");
                cmd.add("-maxrate");
                cmd.add(maxrate + "");
                cmd.add("-bufsize");
                cmd.add(bufsize + "");
                if (encoder.isHevc()) {
                    cmd.add("-tag:v");
                    cmd.add("hvc1");
                }
            }
            case "amf" -> {
                cmd.add("-quality");
                cmd.add("balanced");
                cmd.add("-rc");
                cmd.add("cbr");
                cmd.add("-b:v");
                cmd.add(bitrate + "");
                cmd.add("-maxrate");
                cmd.add(maxrate + "");
                cmd.add("-bufsize");
                cmd.add(bufsize + "");
                if (encoder.isHevc()) {
                    cmd.add("-tag:v");
                    cmd.add("hvc1");
                }
            }
            case "videotoolbox" -> {
                cmd.add("-b:v");
                cmd.add(bitrate + "");
                cmd.add("-maxrate");
                cmd.add(maxrate + "");
                cmd.add("-bufsize");
                cmd.add(bufsize + "");
                cmd.add("-profile:v");
                cmd.add("high");
            }
            case "vaapi" -> {
                cmd.add("-b:v");
                cmd.add(bitrate + "");
                cmd.add("-maxrate");
                cmd.add(maxrate + "");
                cmd.add("-bufsize");
                cmd.add(bufsize + "");
            }
            case "x265" -> {
                cmd.add("-preset");
                cmd.add("medium");
                if (!profile.hasExplicitBitrate()) {
                    cmd.add("-crf"); cmd.add(String.valueOf(profile.quality().cq() + 6));
                }
                cmd.add("-b:v"); cmd.add(profile.hasExplicitBitrate() ? String.valueOf(bitrate) : "0");
                cmd.add("-tag:v");
                cmd.add("hvc1");
            }
            default -> { // libx264 and anything else
                cmd.add("-preset");
                cmd.add("veryfast");
                if (!profile.hasExplicitBitrate()) {
                    cmd.add("-crf"); cmd.add(String.valueOf(profile.quality().cq()));
                }
                cmd.add("-b:v"); cmd.add(profile.hasExplicitBitrate() ? String.valueOf(bitrate) : "0");
                cmd.add("-maxrate");
                cmd.add(maxrate + "");
                cmd.add("-bufsize");
                cmd.add(bufsize + "");
                cmd.add("-profile:v");
                cmd.add("high");
                cmd.add("-level");
                cmd.add("4.2");
            }
        }

        cmd.add("-g");
        cmd.add(String.valueOf(Math.max(1, profile.fps() * 2))); // 2s keyframe interval
    }

    private void addOutputOptions(List<String> cmd, RecordingProfile profile) {
        if (profile.format() == OutputFormat.GIF) {
            cmd.add("-loop");
            cmd.add("0");
            cmd.add("-progress");
            cmd.add("pipe:1");
            return;
        }

        AudioConfig audio = profile.audio();
        if (profile.format().supportsAudio() && audio.hasAny()) {
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add(audio.bitrateKbps() + "k");
            cmd.add("-ar");
            cmd.add(String.valueOf(audio.sampleRate()));
            cmd.add("-ac");
            cmd.add(audio.mixToMono() ? "1" : "2");
        }

        cmd.add("-movflags");
        if (profile.format() == OutputFormat.MP4 || profile.format() == OutputFormat.MOV) {
            cmd.add("+faststart");
        } else {
            cmd.add("+faststart");
        }

        // Live progress on stdout for the UI.
        cmd.add("-progress");
        cmd.add("pipe:1");
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}

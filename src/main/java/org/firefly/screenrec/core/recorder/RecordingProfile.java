package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.core.audio.AudioConfig;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Complete, immutable description of a recording job: what to capture, at what
 * quality, encoded how, and where it is written.
 *
 * <p>This is the single input to {@link FfmpegCommandBuilder}; keeping it
 * immutable makes the resulting command deterministic and easy to test.
 */
public final class RecordingProfile {

    private final CaptureSource source;
    private final CaptureBackend captureBackend;
    private final EncoderBackend encoder;

    private final int outputWidth;
    private final int outputHeight;
    private final int fps;
    private final VideoQuality quality;
    private final int explicitBitrateBps;

    private final OutputFormat format;
    private final Path outputFile;

    private final AudioConfig audio;
    private final boolean drawMouseCursor;
    private final boolean highlightClicks;
    private final int countdownSeconds;
    private final boolean captureWebcam;

    private RecordingProfile(Builder b) {
        this.source = Objects.requireNonNull(b.source, "source");
        this.captureBackend = b.captureBackend == null ? CaptureBackend.AUTO : b.captureBackend;
        this.encoder = b.encoder == null ? EncoderBackend.AUTO : b.encoder;

        this.outputWidth = b.outputWidth > 0 ? even(b.outputWidth) : even(b.source.width());
        this.outputHeight = b.outputHeight > 0 ? even(b.outputHeight) : even(b.source.height());

        this.fps = b.fps > 0 ? b.fps : 30;
        this.quality = b.quality == null ? VideoQuality.HIGH : b.quality;
        this.explicitBitrateBps = b.explicitBitrateBps;

        this.format = b.format == null ? OutputFormat.MP4 : b.format;
        this.outputFile = Objects.requireNonNull(b.outputFile, "outputFile");

        this.audio = b.audio == null ? AudioConfig.none() : b.audio;
        this.drawMouseCursor = b.drawMouseCursor;
        this.highlightClicks = b.highlightClicks;
        this.countdownSeconds = Math.max(0, b.countdownSeconds);
        this.captureWebcam = b.captureWebcam;
    }

    private static int even(int v) {
        if (v <= 0) {
            return 2;
        }
        return v % 2 == 0 ? v : v - 1;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public CaptureSource source() {
        return source;
    }

    public CaptureBackend captureBackend() {
        return captureBackend;
    }

    public EncoderBackend encoder() {
        return encoder;
    }

    public int outputWidth() {
        return outputWidth;
    }

    public int outputHeight() {
        return outputHeight;
    }

    public int fps() {
        return fps;
    }

    public VideoQuality quality() {
        return quality;
    }

    /** Effective video bitrate in bits per second. */
    public boolean hasExplicitBitrate() { return explicitBitrateBps > 0; }

    public int bitrateBps() {
        if (explicitBitrateBps > 0) {
            return explicitBitrateBps;
        }
        return quality.targetBitrateBps(outputWidth, outputHeight, fps);
    }

    public OutputFormat format() {
        return format;
    }

    public Path outputFile() {
        return outputFile;
    }

    public AudioConfig audio() {
        return audio;
    }

    public boolean drawMouseCursor() {
        return drawMouseCursor;
    }

    public boolean highlightClicks() {
        return highlightClicks;
    }

    public int countdownSeconds() {
        return countdownSeconds;
    }

    public boolean captureWebcam() {
        return captureWebcam;
    }

    /** True when the output resolution differs from the captured area. */
    public boolean needsScaling() {
        return outputWidth != source.evenWidth() || outputHeight != source.evenHeight();
    }

    @Override
    public String toString() {
        return "RecordingProfile{" + source + " " + outputWidth + "x" + outputHeight + "@" + fps
                + " " + encoder + " " + format + " audio=" + audio.hasAny()
                + " -> " + outputFile + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.source = source;
        b.captureBackend = captureBackend;
        b.encoder = encoder;
        b.outputWidth = outputWidth;
        b.outputHeight = outputHeight;
        b.fps = fps;
        b.quality = quality;
        b.explicitBitrateBps = explicitBitrateBps;
        b.format = format;
        b.outputFile = outputFile;
        b.audio = audio;
        b.drawMouseCursor = drawMouseCursor;
        b.highlightClicks = highlightClicks;
        b.countdownSeconds = countdownSeconds;
        b.captureWebcam = captureWebcam;
        return b;
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static final class Builder {
        private CaptureSource source;
        private CaptureBackend captureBackend = CaptureBackend.AUTO;
        private EncoderBackend encoder = EncoderBackend.AUTO;
        private int outputWidth;
        private int outputHeight;
        private int fps = 30;
        private VideoQuality quality = VideoQuality.HIGH;
        private int explicitBitrateBps;
        private OutputFormat format = OutputFormat.MP4;
        private Path outputFile;
        private AudioConfig audio = AudioConfig.none();
        private boolean drawMouseCursor = true;
        private boolean highlightClicks = false;
        private int countdownSeconds = 3;
        private boolean captureWebcam = false;

        public Builder source(CaptureSource s) {
            this.source = s;
            return this;
        }

        public Builder captureBackend(CaptureBackend b) {
            this.captureBackend = b;
            return this;
        }

        public Builder encoder(EncoderBackend e) {
            this.encoder = e;
            return this;
        }

        public Builder outputSize(int w, int h) {
            this.outputWidth = w;
            this.outputHeight = h;
            return this;
        }

        public Builder fps(int fps) {
            if (fps > 0) {
                this.fps = Math.min(fps, 240);
            }
            return this;
        }

        public Builder quality(VideoQuality q) {
            this.quality = q;
            return this;
        }

        public Builder explicitBitrateBps(int bps) {
            this.explicitBitrateBps = Math.max(0, bps);
            return this;
        }

        public Builder format(OutputFormat f) {
            this.format = f;
            return this;
        }

        public Builder outputFile(Path p) {
            this.outputFile = p;
            return this;
        }

        public Builder audio(AudioConfig a) {
            this.audio = a;
            return this;
        }

        public Builder drawMouseCursor(boolean v) {
            this.drawMouseCursor = v;
            return this;
        }

        public Builder highlightClicks(boolean v) {
            this.highlightClicks = v;
            return this;
        }

        public Builder countdownSeconds(int s) {
            this.countdownSeconds = Math.max(0, s);
            return this;
        }

        public Builder captureWebcam(boolean v) {
            this.captureWebcam = v;
            return this;
        }

        public RecordingProfile build() {
            return new RecordingProfile(this);
        }
    }
}

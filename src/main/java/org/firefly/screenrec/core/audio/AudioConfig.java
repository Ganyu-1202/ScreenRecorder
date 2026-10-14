package org.firefly.screenrec.core.audio;

/**
 * Audio capture configuration: which sources to record and how to mix them.
 *
 * <p>Microphone and system audio are encoded as separate FFmpeg inputs and mixed
 * with {@code amix}, so each can carry independent gain.
 */
public final class AudioConfig {

    private final AudioDevice microphone;
    private final AudioDevice systemAudio;
    private final double micGain;
    private final double systemGain;
    private final boolean mixToMono;
    private final int sampleRate;
    private final int bitrateKbps;

    private AudioConfig(Builder b) {
        this.microphone = b.microphone;
        this.systemAudio = b.systemAudio;
        this.micGain = b.micGain;
        this.systemGain = b.systemGain;
        this.mixToMono = b.mixToMono;
        this.sampleRate = b.sampleRate;
        this.bitrateKbps = b.bitrateKbps;
    }

    public AudioDevice microphone() {
        return microphone;
    }

    public AudioDevice systemAudio() {
        return systemAudio;
    }

    public double micGain() {
        return micGain;
    }

    public double systemGain() {
        return systemGain;
    }

    public boolean mixToMono() {
        return mixToMono;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int bitrateKbps() {
        return bitrateKbps;
    }

    public boolean hasMicrophone() {
        return microphone != null;
    }

    public boolean hasSystemAudio() {
        return systemAudio != null;
    }

    public boolean hasAny() {
        return hasMicrophone() || hasSystemAudio();
    }

    public boolean hasBoth() {
        return hasMicrophone() && hasSystemAudio();
    }

    public int sourceCount() {
        return (hasMicrophone() ? 1 : 0) + (hasSystemAudio() ? 1 : 0);
    }

    /** A configuration with audio disabled. */
    public static AudioConfig none() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.microphone = microphone;
        b.systemAudio = systemAudio;
        b.micGain = micGain;
        b.systemGain = systemGain;
        b.mixToMono = mixToMono;
        b.sampleRate = sampleRate;
        b.bitrateKbps = bitrateKbps;
        return b;
    }

    public static final class Builder {
        private AudioDevice microphone;
        private AudioDevice systemAudio;
        private double micGain = 1.0;
        private double systemGain = 1.0;
        private boolean mixToMono = false;
        private int sampleRate = 48_000;
        private int bitrateKbps = 192;

        public Builder microphone(AudioDevice d) {
            this.microphone = d;
            return this;
        }

        public Builder systemAudio(AudioDevice d) {
            this.systemAudio = d;
            return this;
        }

        public Builder micGain(double gain) {
            this.micGain = clampGain(gain);
            return this;
        }

        public Builder systemGain(double gain) {
            this.systemGain = clampGain(gain);
            return this;
        }

        public Builder mixToMono(boolean mono) {
            this.mixToMono = mono;
            return this;
        }

        public Builder sampleRate(int rate) {
            if (rate > 0) {
                this.sampleRate = rate;
            }
            return this;
        }

        public Builder bitrateKbps(int kbps) {
            if (kbps > 0) {
                this.bitrateKbps = kbps;
            }
            return this;
        }

        private static double clampGain(double g) {
            return Math.max(0.0, Math.min(g, 4.0));
        }

        public AudioConfig build() {
            return new AudioConfig(this);
        }
    }
}

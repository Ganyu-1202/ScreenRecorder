package org.firefly.screenrec.core.audio;

import java.util.Objects;

/**
 * A selectable audio input, either a physical microphone or a system-audio
 * loopback device.
 */
public final class AudioDevice {

    public enum Kind {
        /** A regular input device (microphone, line-in, USB interface). */
        MICROPHONE,
        /** Speakers/system output captured via a loopback device. */
        SYSTEM_OUTPUT
    }

    private final String id;
    private final String name;
    private final Kind kind;
    private final String ffmpegInputFormat;
    private final String ffmpegInputName;
    private final int channels;
    private final int sampleRate;

    public AudioDevice(String id, String name, Kind kind,
                       String ffmpegInputFormat, String ffmpegInputName,
                       int channels, int sampleRate) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.ffmpegInputFormat = ffmpegInputFormat;
        this.ffmpegInputName = ffmpegInputName;
        this.channels = Math.max(1, channels);
        this.sampleRate = sampleRate > 0 ? sampleRate : 48000;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** FFmpeg {@code -f} value, e.g. {@code dshow}, {@code pulse}, {@code avfoundation}. */
    public String ffmpegInputFormat() {
        return ffmpegInputFormat;
    }

    /** FFmpeg {@code -i} value, e.g. the dshow device name or pulse source. */
    public String ffmpegInputName() {
        return ffmpegInputName;
    }

    public int channels() {
        return channels;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public boolean isMicrophone() {
        return kind == Kind.MICROPHONE;
    }

    public boolean isSystemAudio() {
        return kind == Kind.SYSTEM_OUTPUT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AudioDevice other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name;
    }
}

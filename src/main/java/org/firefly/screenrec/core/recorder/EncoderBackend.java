package org.firefly.screenrec.core.recorder;

import org.firefly.screenrec.util.PlatformUtils;

import java.util.List;
import java.util.Locale;

/**
 * Video encoder backends, ordered from best (hardware accelerated) to most
 * compatible (software). Each backend maps to a concrete FFmpeg encoder name.
 */
public enum EncoderBackend {

    /** NVIDIA NVENC — H.264 */
    NVENC_H264("h264_nvenc", "NVIDIA NVENC (H.264)", true, "nvenc"),
    /** NVIDIA NVENC — H.265 */
    NVENC_HEVC("hevc_nvenc", "NVIDIA NVENC (H.265)", true, "nvenc"),
    /** Intel Quick Sync — H.264 */
    QSV_H264("h264_qsv", "Intel Quick Sync (H.264)", true, "qsv"),
    /** Intel Quick Sync — H.265 */
    QSV_HEVC("hevc_qsv", "Intel Quick Sync (H.265)", true, "qsv"),
    /** AMD AMF — H.264 */
    AMF_H264("h264_amf", "AMD AMF (H.264)", true, "amf"),
    /** AMD AMF — H.265 */
    AMF_HEVC("hevc_amf", "AMD AMF (H.265)", true, "amf"),
    /** Apple VideoToolbox — H.264 */
    VIDEOTOOLBOX_H264("h264_videotoolbox", "Apple VideoToolbox (H.264)", true, "videotoolbox"),
    /** Apple VideoToolbox — H.265 */
    VIDEOTOOLBOX_HEVC("hevc_videotoolbox", "Apple VideoToolbox (H.265)", true, "videotoolbox"),
    /** Linux VAAPI — H.264 */
    VAAPI_H264("h264_vaapi", "VAAPI (H.264)", true, "vaapi"),
    /** Software x264 */
    LIBX264("libx264", "x264 (software, most compatible)", false, "x264"),
    /** Software x265 */
    LIBX265("libx265", "x265 (software)", false, "x265"),
    /** Fallback: whatever the muxer picks */
    AUTO("", "Automatic (best available)", true, "auto");

    private final String ffmpegName;
    private final String displayName;
    private final boolean hardware;
    private final String family;

    EncoderBackend(String ffmpegName, String displayName, boolean hardware, String family) {
        this.ffmpegName = ffmpegName;
        this.displayName = displayName;
        this.hardware = hardware;
        this.family = family;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isHardware() {
        return hardware;
    }

    /** Group key such as {@code nvenc}, {@code qsv}, {@code x264}. */
    public String family() {
        return family;
    }

    public boolean isHevc() {
        return ffmpegName.contains("hevc") || ffmpegName.contains("h265");
    }

    /**
     * Hardware encoders preferred on the current platform, most preferable first.
     */
    public static List<EncoderBackend> preferredForPlatform() {
        if (PlatformUtils.isMac()) {
            return List.of(VIDEOTOOLBOX_H264, VIDEOTOOLBOX_HEVC, LIBX264, LIBX265);
        }
        if (PlatformUtils.isLinux()) {
            return List.of(NVENC_H264, NVENC_HEVC, VAAPI_H264, QSV_H264, AMF_H264, LIBX264, LIBX265);
        }
        if (PlatformUtils.isWindows()) {
            return List.of(NVENC_H264, NVENC_HEVC, QSV_H264, QSV_HEVC, AMF_H264, AMF_HEVC,
                    LIBX264, LIBX265);
        }
        return List.of(LIBX264, LIBX265);
    }

    public static EncoderBackend fromFfmpegName(String name) {
        if (name == null) {
            return AUTO;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (EncoderBackend b : values()) {
            if (b.ffmpegName.equalsIgnoreCase(n)) {
                return b;
            }
        }
        return AUTO;
    }

    /** Resolves a persisted identifier (enum name or FFmpeg encoder name). */
    public static EncoderBackend fromId(String id) {
        if (id == null || id.isBlank()) {
            return AUTO;
        }
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (EncoderBackend b : values()) {
            if (b.name().equalsIgnoreCase(s) || b.ffmpegName.equalsIgnoreCase(s)) {
                return b;
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

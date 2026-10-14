package org.firefly.screenrec.core.i18n;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight, dependency-free message catalogue.
 *
 * <p>Messages live in plain UTF-8 source rather than {@code .properties}
 * bundles, which would force Chinese text into ISO-8859-1 {@code \\uXXXX}
 * escapes and make the catalogue unreadable. English is always the fallback:
 * an unknown key returns the key itself so missing translations are obvious
 * rather than silent.
 *
 * <p>This class contains no JavaFX types so the core layer stays UI-free.
 */
public final class Messages {

    private static final Map<String, String[]> TABLE = new LinkedHashMap<>();
    private static volatile Lang current = Lang.EN;

    private Messages() {
    }

    /** Registers a message; {@code en} is the fallback when a translation is absent. */
    private static void put(String key, String en, String zh) {
        TABLE.put(key, new String[]{en, zh});
    }

    /** Returns the active language. */
    public static Lang getLanguage() {
        return current;
    }

    /** Sets the active language; {@code null} resets to English. */
    public static void setLanguage(Lang lang) {
        current = lang == null ? Lang.EN : lang;
    }

    /** Convenience overload accepting a persisted id. */
    public static void setLanguage(String id) {
        setLanguage(Lang.fromId(id));
    }

    /**
     * Looks up a message in the active language.
     *
     * @param key the message key
     * @return the translated text, the English text, or the key when unknown
     */
    public static String get(String key) {
        String[] pair = TABLE.get(key);
        if (pair == null) {
            return key;
        }
        return current == Lang.ZH_CN ? pair[1] : pair[0];
    }

    /**
     * Looks up a message and substitutes {@code {0}}, {@code {1}}, … placeholders.
     *
     * @param key    the message key
     * @param params positional values
     * @return the formatted message
     */
    public static String get(String key, Object... params) {
        String text = get(key);
        for (int i = 0; i < params.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(params[i]));
        }
        return text;
    }

    /** Number of registered keys (used by tests). */
    public static int size() {
        return TABLE.size();
    }

    static {
        put("tray.open", "Open ScreenRecorder", "打开 ScreenRecorder");
        put("tray.record", "Record display", "录制显示器");
        put("tray.stop", "Stop recording", "停止录制");
        put("tray.folder", "Open recordings folder", "打开录制文件夹");
        put("tray.quit", "Quit", "退出");
        put("tray.recording", "ScreenRecorder — recording…", "ScreenRecorder — 正在录制…");

        // ---- Sidebar / navigation -------------------------------------
        put("nav.record", "Record", "录制");
        put("nav.library", "Library", "素材库");
        put("nav.editor", "Editor", "编辑器");
        put("nav.settings", "Settings", "设置");
        put("app.name", "ScreenRecorder", "ScreenRecorder");
        put("app.footer", "Powered by FFmpeg", "由 FFmpeg 驱动");

        // ---- Window chrome --------------------------------------------
        put("win.minimize", "Minimize", "最小化");
        put("win.maximize", "Maximize", "最大化");
        put("win.restore", "Restore", "还原");
        put("win.close", "Close", "关闭");
        put("win.recording", "Recording in progress", "正在录制");
        put("win.recBadge", "REC", "录制中");
        put("win.saved", "Recording saved", "录制已保存");
        put("win.problem", "Recording problem", "录制出现问题");
        put("win.closeConfirm", "Stop and save the current recording?",
                "停止并保存当前录制？");
        put("win.closeConfirmDetail", "Closing now will finish and save your recording.",
                "现在关闭将结束并保存你的录制。");
        put("win.comingSoon", "This section is coming in a later update.",
                "该功能将在后续版本中推出。");

        // ---- Common ---------------------------------------------------
        put("common.change", "Change…", "更改…");
        put("common.detect", "Detect again", "重新检测");
        put("common.cancel", "Cancel", "取消");
        put("common.close", "Close", "关闭");
        put("common.open", "Open", "打开");
        put("common.delete", "Delete", "删除");
        put("common.about", "About", "关于");
        put("common.unknown", "unknown location", "未知位置");
        put("common.unknownError", "unknown error", "未知错误");
        put("toast.saved", "Recording saved", "录制已保存");
        put("toast.problem", "Recording problem", "录制出错了");
        put("dialog.recording.title", "Recording in progress", "正在录制");
        put("dialog.recording.header", "Stop and save the current recording?",
                "要停止并保存当前录制吗？");
        put("dialog.recording.body",
                "Closing now will finish and save your recording.",
                "现在关闭会先完成并保存你的录制内容。");

        // ---- Settings: shell ------------------------------------------
        put("settings.title", "Settings", "设置");
        put("settings.subtitle", "Tune how ScreenRecorder looks, sounds and records.",
                "调整 ScreenRecorder 的外观、声音与录制方式。");

        // ---- Settings: appearance -------------------------------------
        put("settings.accent", "Accent color", "主题颜色");
        put("settings.accent.desc", "Pick a palette color or fine-tune RGB (0–255). Changes preview instantly.", "选择预设色，或调整 RGB（0–255），即时预览并自动保存。");
        put("settings.accent.preview", "Color preview", "主题色预览");
        put("settings.shortcuts", "Keyboard shortcuts", "快捷键");
        put("settings.shortcuts.desc", "Click a binding, then press the new keys. Esc cancels. Works only while the app window is focused (not system-wide).", "点击快捷键框后按下新的按键组合；Esc 取消。仅软件窗口获得焦点时生效，不是系统全局热键。");
        put("settings.shortcuts.capture", "Press new keys", "按下新的按键组合");
        put("settings.shortcuts.conflict", "This shortcut is already bound to another action.", "此快捷键已绑定其他操作，请使用不同按键。");
        put("settings.shortcuts.start", "Start recording", "开始录制");
        put("settings.shortcuts.pause", "Pause / resume", "暂停／继续");
        put("settings.shortcuts.stop", "Stop recording", "停止录制");
        put("settings.shortcuts.library", "Open library", "打开素材库");
        put("settings.shortcuts.settings", "Open settings", "打开设置");
        put("settings.shortcuts.reset", "Restore default shortcuts", "恢复默认快捷键");
        put("settings.appearance", "Appearance", "外观");
        put("settings.appearance.desc", "Choose a theme. Changes apply instantly.",
                "选择主题，更改立即生效。");
        put("settings.theme", "Theme", "主题");
        put("settings.theme.dark", "Dark", "深色");
        put("settings.theme.light", "Light", "浅色");
        put("settings.language", "Language", "语言");
        put("settings.language.desc",
                "Pick the interface language. The window is rebuilt instantly.",
                "选择界面语言，窗口会立即重建。");

        // ---- Settings: behaviour --------------------------------------
        put("settings.behavior", "Recording behaviour", "录制行为");
        put("settings.behavior.desc", "Fine-tune what gets baked into your videos.",
                "精细调整录进视频里的内容。");
        put("settings.cursor", "Show mouse cursor", "显示鼠标指针");
        put("settings.cursor.desc", "Include the pointer in the recording",
                "在录制中显示鼠标指针");
        put("settings.clicks", "Highlight clicks", "高亮点击");
        put("settings.clicks.desc", "Draw a ring around every click (Windows/macOS)",
                "在每次点击处绘制圆环（Windows/macOS）");
        put("settings.controlBar.exclude", "Exclude floating bar from recorded video", "不将录制悬浮栏录入视频");
        put("settings.controlBar.exclude.desc", "Applies to the next recording. Requires Windows 10 2004+ and a compatible capture backend.", "下次录制生效。需要 Windows 10 2004 及以上版本及兼容的采集方式。");
        put("settings.controlBar", "Show floating bar while recording", "录制时显示悬浮栏");
        put("settings.controlBar.desc", "When hidden, stop from the main window or tray menu",
                "关闭后可从主窗口或系统托盘菜单停止录制");
        put("settings.controlBar.transparency", "Floating bar transparency", "悬浮栏透明度");
        put("settings.controlBar.transparency.desc", "0% is opaque; hovering makes the bar fully readable",
                "0% 为完全不透明；鼠标移入时恢复不透明，便于操作");
        put("settings.minimizeOnRecording", "Minimize the app when recording starts", "开始录制后自动最小化软件");
        put("settings.minimizeOnRecording.desc", "Minimize only after recording has started successfully",
                "仅在录制成功启动后最小化，不影响倒计时或区域选择");
        put("settings.countdown", "Countdown before recording", "录制前倒计时");
        put("settings.countdown.desc", "Countdown before recording", "录制开始前的倒计时");

        // ---- Settings: audio ------------------------------------------
        put("settings.audio", "Audio levels", "音频音量");
        put("settings.audio.desc", "Balance the microphone against system sound.",
                "平衡麦克风与系统声音的音量。");
        put("settings.micVolume", "Microphone volume", "麦克风音量");
        put("settings.sysVolume", "System audio volume", "系统声音音量");
        put("settings.mono", "Mix to mono", "混合为单声道");
        put("settings.mono.desc",
                "Merge both channels — smaller files, works with more players",
                "合并两个声道 — 文件更小，兼容更多播放器");

        // ---- Settings: saving -----------------------------------------
        put("settings.saving", "Saving", "保存");
        put("settings.saving.desc", "Where recordings go and what they are called.",
                "录制文件的保存位置与命名方式。");
        put("settings.saveTo", "Save recordings to", "录制保存到");
        put("settings.chooseFolder", "Choose folder…", "选择文件夹…");
        put("settings.chooseFolder.desc", "Choose where recordings are saved",
                "选择录制文件的保存位置");
        put("settings.namePrefix", "File name prefix", "文件名前缀");
        put("settings.example", "Example", "示例");

        // ---- Settings: engine -----------------------------------------
        put("settings.engine", "Recording engine (FFmpeg)", "录制引擎（FFmpeg）");
        put("settings.engine.desc",
                "ScreenRecorder uses FFmpeg for hardware-accelerated capture.",
                "ScreenRecorder 使用 FFmpeg 进行硬件加速录制。");
        put("settings.engine.ready", "FFmpeg is ready", "FFmpeg 已就绪");
        put("settings.engine.detected", "FFmpeg detected", "已检测到 FFmpeg");
        put("settings.engine.notFound", "FFmpeg not found", "未找到 FFmpeg");
        put("settings.engine.stillNotFound", "FFmpeg still not found", "仍未找到 FFmpeg");
        put("settings.engine.scanning", "Scanning for FFmpeg…", "正在检测 FFmpeg…");
        put("settings.engine.installed", "FFmpeg installed successfully", "FFmpeg 安装成功");
        put("settings.engine.download", "Download FFmpeg", "下载 FFmpeg");
        put("settings.engine.downloading", "Downloading…", "正在下载…");
        put("settings.engine.custom", "Use custom build…", "使用自定义版本…");
        put("settings.engine.pickTitle", "Select the FFmpeg executable", "选择 FFmpeg 可执行文件");
        put("settings.engine.pickHint", "Please select the ffmpeg executable", "请选择 ffmpeg 可执行文件");
        put("settings.engine.exeFilter", "Executable", "可执行文件");
        put("settings.engine.openData", "Open data folder", "打开数据目录");
        put("settings.engine.cache", "Cache", "缓存");
        put("settings.engine.needFfmpeg",
                "ScreenRecorder needs FFmpeg to record. It is free and open source — ",
                "ScreenRecorder 需要 FFmpeg 才能录制。它是免费开源的 — ");
        put("settings.engine.autoHint",
                "click Download to fetch a build automatically, or point to an ",
                "点击「下载」自动获取构建，或指定一个 ");
        put("settings.engine.autoHint2", "existing installation.", "已安装的位置。");
        put("settings.engine.encoders", " video encoder(s) and ", " 个视频编码器与 ");
        put("settings.engine.devices", " audio device(s) detected.", " 个音频设备。");
        put("settings.engine.captureMethod", "Capture method", "采集方式");
        put("settings.engine.encoder", "Video encoder", "视频编码器");
        put("settings.engine.downloadFailed", "Download failed — check your connection",
                "下载失败 — 请检查网络连接");
        put("settings.engine.downloadFailedDetail", "Download failed: ", "下载失败：");

        // ---- Record view ----------------------------------------------
        put("record.title", "Record your screen", "录制你的屏幕");
        put("record.subtitle",
                "Capture a display, a region or your full desktop — with system and microphone audio.",
                "可录制单个显示器、指定区域或整个桌面 — 支持系统声音与麦克风。");
        put("record.captureSource", "Capture source", "录制源");
        put("record.fullScreen", "Full screen", "全屏");
        put("record.display", "Display", "显示器");
        put("record.region", "Region", "区域");
        put("record.application", "Window", "窗口");
        put("record.chooseApplication", "Choose an open application window", "请选择正在运行的程序窗口");
        put("record.refreshApplications", "Refresh", "刷新列表");
        put("record.applicationHint", "Records the visible desktop area occupied by the window. Keep it unobscured and do not move, resize or minimize it; audio uses system/microphone devices.", "录制窗口所在的桌面区域，请勿遮挡、移动、调整大小或最小化窗口；音频仍使用系统声音／麦克风。");
        put("record.applicationMissing", "Window is unavailable. Restore the application and refresh the list.", "程序窗口不可用，请打开或还原程序后刷新列表。");
        put("record.bitrateCustom", "Custom…", "自定义…");
        put("record.bitrateInput", "Enter bitrate in Mbps (1–200)", "请输入码率（1～200 Mbps）");
        put("record.manualWindow", "Choose a window manually…", "自行选择程序窗口…");
        put("record.portalRegionHint", "Select a screen in the system dialog. Crop coordinates are physical pixels relative to that screen.",
                "接下来在系统授权窗口中选择屏幕。裁剪坐标相对于所选屏幕左上角，单位为物理像素。");
        put("record.windowTitle", "Select an open window or enter its exact title", "选择已打开的程序窗口，或输入完整窗口标题");
        put("record.bitrate", "Video bitrate", "视频码率");
        put("record.bitrateAuto", "Automatic (quality-based)", "自动（根据画质）");
        put("record.noDisplay", "No display available", "无可用显示器");
        put("record.quality", "Quality", "画质");
        put("record.quality.low", "Low", "低");
        put("record.quality.medium", "Medium", "中");
        put("record.quality.high", "High", "高");
        put("record.quality.nearLossless", "Near-lossless", "接近无损");
        put("record.quality.ultra", "Ultra", "极高");
        put("record.resolution.source", "Source", "原始分辨率");
        put("record.micDesc", "Record your voice", "录制你的声音");
        put("record.change", "Change…", "更改…");
        put("record.recording", "Recording…", "录制中…");
        put("record.noDisplay", "No display available", "没有可用的显示器");
        put("record.quality.lossless", "LOSSLESS", "无损");
        put("record.resolution", "Resolution", "分辨率");
        put("record.source", "Source", "来源");
        put("record.frameRate", "Frame rate", "帧率");
        put("record.audio", "Audio", "音频");
        put("record.microphone", "Microphone", "麦克风");
        put("record.systemAudio", "System audio", "系统声音");
        put("record.systemAudio.desc", "Record sound from your computer",
                "录制电脑播放的声音");
        put("record.notAvailable", "No system loopback input detected. Connect or enable a playback device and refresh.",
                "未检测到系统回环输入。请连接或启用耳机／扬声器后刷新；也可检查回环辅助程序是否完整。");
        put("record.audio.empty", "No audio input detected. Check devices and permissions, then refresh.", "未检测到音频输入，请检查设备和权限后刷新。");
        put("record.audio.refresh", "Refresh audio devices", "刷新音频设备");
        put("record.output", "Output", "输出");
        put("record.saveTo", "Choose where recordings are saved", "选择录制保存位置");
        put("record.format", "Format", "格式");
        put("record.encoder", "Encoder", "编码器");
        put("record.start", "Start recording", "开始录制");
        put("record.stop", "Stop", "停止");
        put("record.pause", "Pause", "暂停");
        put("record.resume", "Resume", "继续");
        put("record.prepare", "Preparing…", "准备中…");
        put("record.shortcuts", "Customize keyboard shortcuts in Settings", "可在设置中自定义快捷键");

        // ---- Library view ---------------------------------------------
        put("library.title", "Library", "素材库");
        put("library.subtitle", "Every recording you have made.", "你录制的所有视频。");
        put("library.empty", "No recordings yet", "还没有录制文件");
        put("library.empty.hint", "Your finished recordings will appear here.",
                "录制完成的视频会出现在这里。");
        put("library.refresh", "Refresh", "刷新");
        put("library.openFolder", "Open folder", "打开文件夹");
        put("library.play", "Play", "播放");
        put("library.reveal", "Show in folder", "在文件夹中显示");
        put("library.delete", "Delete", "删除");
        put("library.search", "Search recordings…", "搜索录制文件…");
        put("library.copyPath", "Copy file path", "复制文件路径");
        put("library.noPreview", "No preview", "暂无预览");
        put("library.count", "{0} recording(s)", "共 {0} 个录制文件");
        put("library.countFiltered", "{0} of {1} recordings",
                "符合条件 {0} 个，共 {1} 个");
        put("library.openManual", "Cannot open the file automatically",
                "无法自动打开该文件");
        put("library.openFailed", "Could not open the file", "无法打开该文件");
        put("library.copied", "Path copied to clipboard", "路径已复制到剪贴板");
        put("library.deleteTitle", "Delete recording", "删除录制文件");
        put("library.deleteHeader", "Delete \"{0}\"?", "确定要删除“{0}”吗？");
        put("library.deleteBody",
                "This permanently removes the file from disk and cannot be undone.",
                "这将从磁盘中永久删除该文件，且无法恢复。");
        put("library.deleted", "Recording deleted", "录制文件已删除");
        put("library.deleteFailed", "Could not delete the file", "无法删除该文件");

        // ---- Editor view ----------------------------------------------
        put("editor.title", "Editor", "编辑器");
        put("editor.subtitle", "Trim and export a recording.", "裁剪并导出录制文件。");
        put("editor.open", "Open video…", "打开视频…");
        put("editor.empty", "Open a recording to start editing", "打开一个录制文件开始编辑");
        put("editor.trim", "Trim", "裁剪");
        put("editor.start", "Start", "起点");
        put("editor.end", "End", "终点");
        put("editor.export", "Export", "导出");
        put("editor.exporting", "Exporting…", "正在导出…");
        put("editor.pick", "Select a recording to edit", "选择一个录制文件开始编辑");
        put("editor.lossless", "Fast trim (no re-encode)", "快速裁剪（不重新编码）");
        put("editor.rescan", "Rescan recordings", "重新扫描录制文件");
        put("editor.back5", "Back 5s", "后退 5 秒");
        put("editor.play", "Play", "播放");
        put("editor.pause", "Pause", "暂停");
        put("editor.fullscreen", "Fullscreen", "全屏播放");
        put("editor.exitFullscreen", "Exit fullscreen", "退出全屏");
        put("editor.fullscreenHint", "Press Esc to return to the editor", "按 Esc 返回编辑器");
        put("editor.playPause", "Play / pause", "播放 / 暂停");
        put("editor.fwd5", "Forward 5s", "前进 5 秒");
        put("editor.trim.desc",
                "Drag the handles to choose the part you want to keep.",
                "拖动两端滑块，选择你要保留的片段。");
        put("editor.saveTrim", "Save trimmed copy", "保存裁剪副本");
        put("editor.extractAudio", "Extract audio (M4A)", "提取音频（M4A）");
        put("editor.exportGif", "Export GIF", "导出 GIF");
        put("editor.showFile", "Show file", "在文件夹中显示");
        put("editor.playError", "Could not play this file:", "无法播放该文件：");
        put("editor.preparingPreview", "Preparing compatible preview… Original file is unchanged.", "正在生成兼容预览…长视频可能需要较久，原文件不会修改。");
        put("editor.previewUnavailable", "Preview unavailable; trimming is still available when duration is known.", "预览不可用；已获取时长的视频仍可裁剪和导出。");
        put("editor.cannotPreview", "Cannot preview this file", "无法预览此文件");
        put("editor.keeping", "Keeping {0} → {1}   ({2})", "保留 {0} → {1}   ({2})");
        put("editor.pickFirst", "Pick a recording first", "请先选择一个录制文件");
        put("editor.rangeTooShort", "The selected range is too short", "选择的范围太短");
        put("editor.gifLimit",
                "GIF export is limited to 20 seconds — shorten the range",
                "GIF 导出最长 20 秒，请缩短范围");
        put("editor.noAudioTrack", "This recording has no audio track",
                "该录制文件没有音轨");
        put("editor.noFfmpeg", "FFmpeg is not available", "FFmpeg 不可用");
        put("editor.trimming", "Trimming…", "正在裁剪…");
        put("editor.extracting", "Extracting audio…", "正在提取音频…");
        put("editor.buildingGif", "Building GIF…", "正在生成 GIF…");
        put("editor.exportDone", "Export finished", "导出完成");
        put("editor.exportFailed", "Export failed", "导出失败");

        // ---- Recording overlays ---------------------------------------
        put("overlay.countdown.start", "Recording starts in {0} s", "{0} 秒后开始录制");
        put("overlay.countdown.cancel", "Press Esc to cancel", "按 Esc 取消");
        put("overlay.bar.title", "Recording Controls", "录制控制");
        put("overlay.bar.stop", "Stop", "停止");
        put("overlay.bar.stopHint", "Stop and save", "停止并保存");
        put("overlay.bar.pauseHint", "Pause", "暂停");
        put("overlay.bar.resumeHint", "Resume", "继续");
        put("overlay.bar.discard", "Stop and delete recording", "停止并删除本次录制");
        put("rec.discard.title", "Delete this recording?", "删除本次录制？");
        put("rec.discard.confirm", "Stop recording and permanently delete its video file? This cannot be undone.",
                "停止录制并永久删除本次视频文件？此操作无法撤销。");
        put("rec.discard.delete", "Stop and delete", "停止并删除");
        put("rec.discard.cancel", "Continue recording", "继续录制");
        put("rec.discard.failed", "Could not delete the recording. Check the file:", "未能删除本次录制，请检查文件：");
        put("overlay.region.title", "Select recording region", "选择录制区域");
        put("overlay.region.hint",
                "Drag to select an area  ·  Enter to confirm  ·  Esc to cancel",
                "拖动选择区域  ·  回车确认  ·  Esc 取消");

        // ---- Recording flow messages ----------------------------------
        put("rec.startFailed.ffmpeg",
                "FFmpeg is not available. Set its path in Settings, or download it.",
                "FFmpeg 不可用。请在设置中指定路径，或下载 FFmpeg。");
        put("rec.startFailed.noDisplay", "No display available to record.",
                "没有可录制的显示器。");
        put("rec.startFailed.spawn", "Could not start FFmpeg. Check the log for details.",
                "无法启动 FFmpeg。请查看日志了解详情。");
        put("rec.failed", "Recording failed. See the log for details.",
                "录制失败。请查看日志了解详情。");
        put("record.chooseFolder", "Choose where recordings are saved", "选择录制保存位置");
        put("toast.dismiss", "Click to dismiss", "点击关闭");
        put("dialog.ffmpeg.title", "FFmpeg required", "需要 FFmpeg");
        put("dialog.ffmpeg.header", "ScreenRecorder needs FFmpeg to record",
                "ScreenRecorder 需要 FFmpeg 才能录制");
        put("dialog.ffmpeg.body",
                "FFmpeg was not found on this computer.\n\n"
                        + "Open Settings → Recording engine and click \"Download FFmpeg\" to "
                        + "install it automatically, or point ScreenRecorder at an existing build.",
                "未在本机找到 FFmpeg。\n\n"
                        + "请打开「设置 → 录制引擎」，点击「下载 FFmpeg」自动安装，"
                        + "或指定一个已安装的位置。");
    }
}

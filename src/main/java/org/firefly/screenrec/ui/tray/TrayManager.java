package org.firefly.screenrec.ui.tray;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.core.recorder.RecordingSession;
import org.firefly.screenrec.ui.RecordingController;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.application.Platform;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.core.i18n.Lang;
import javafx.beans.value.ChangeListener;
import java.awt.EventQueue;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Optional system-tray icon with quick actions.
 *
 * <p>Tray support is best-effort: on Linux desktops without a tray host, or in
 * headless environments, initialisation silently no-ops and the app keeps
 * working through its normal window.
 */
public final class TrayManager {

    private final AppContext context;
    private final RecordingController controller;
    private final Consumer<String> onNotify;

    private TrayIcon trayIcon;
    private volatile org.firefly.screenrec.util.ProcessRunner.Handle linuxTray;
    private java.nio.file.Path linuxIconDirectory;
    private volatile boolean disposing;
    private Stage stage;
    private MenuItem recordItem;
    private MenuItem stopItem;
    private MenuItem openItem, folderItem, quitItem;
    private boolean recording;
    private final ChangeListener<Lang> languageListener = (obs, old, value) -> refreshLanguage();

    public TrayManager(AppContext context, RecordingController controller,
                       Consumer<String> onNotify) {
        this.context = context;
        this.controller = controller;
        this.onNotify = onNotify;
    }

    /** Installs the tray icon. Never throws — tray problems are non-fatal. */
    public void install(Stage stage) {
        this.stage = stage;
        if (PlatformUtils.isLinux()) {
            installLinuxIndicator();
            return;
        }
        if (!PlatformUtils.supportsTray() || !SystemTray.isSupported()) {
            Log.info("System tray is not available on this platform");
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();

            PopupMenu menu = new PopupMenu();

            openItem = new MenuItem(I18n.t("tray.open"));
            openItem.addActionListener(e -> runOnFx(() -> showStage()));
            menu.add(openItem);

            menu.addSeparator();

            recordItem = new MenuItem(I18n.t("tray.record"));
            recordItem.addActionListener(e -> runOnFx(() ->
                    controller.startRecording(
                            org.firefly.screenrec.core.recorder.CaptureSource.Kind.MONITOR,
                            null, null, null)));
            menu.add(recordItem);

            stopItem = new MenuItem(I18n.t("tray.stop"));
            stopItem.setEnabled(false);
            stopItem.addActionListener(e -> runOnFx(() -> controller.stopRecording()));
            menu.add(stopItem);

            menu.addSeparator();

            folderItem = new MenuItem(I18n.t("tray.folder"));
            folderItem.addActionListener(e -> runOnFx(() ->
                    FxScheduler.background(() ->
                            PlatformUtils.openFolder(context.outputDirectory()))));
            menu.add(folderItem);

            menu.addSeparator();

            quitItem = new MenuItem(I18n.t("tray.quit"));
            quitItem.addActionListener(e -> runOnFx(this::quit));
            menu.add(quitItem);

            trayIcon = new TrayIcon(createTrayImage(), "ScreenRecorder", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.setToolTip("ScreenRecorder");
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                        runOnFx(() -> showStage());
                    }
                }
            });

            tray.add(trayIcon);
            I18n.languageProperty().removeListener(languageListener);
            I18n.languageProperty().addListener(languageListener);
            updateRecordingState(context.isRecording());
            Log.info("System tray icon installed");
        } catch (AWTException | RuntimeException e) {
            Log.warn("Could not install the system tray icon: " + e.getMessage());
            trayIcon = null;
        }
    }

    private void installLinuxIndicator() {
        // GTK runs in its own process: never mix GTK and AWT event loops in the JVM.
        FxScheduler.background(() -> {
            try {
                var ffmpeg = context.ffmpegPath().orElseThrow();
                var helper = ffmpeg.resolveSibling("tray-indicator");
                if (!java.nio.file.Files.isExecutable(helper)) {
                    Log.warn("Native Linux tray helper is missing; keeping the main window available");
                    return;
                }
                linuxIconDirectory = java.nio.file.Files.createTempDirectory("screenrec-tray-");
                var icon = linuxIconDirectory.resolve("screenrecorder.png");
                try (var in = TrayManager.class.getResourceAsStream("/org/firefly/screenrec/icons/screen-recorder-256.png")) {
                    if (in == null) throw new java.io.IOException("Missing tray icon asset");
                    java.nio.file.Files.copy(in, icon);
                }
                linuxTray = org.firefly.screenrec.util.ProcessRunner.start(java.util.List.of(
                        helper.toString(), icon.toString(),
                        org.firefly.screenrec.core.i18n.Messages.getLanguage() == org.firefly.screenrec.core.i18n.Lang.ZH_CN ? "zh" : "en"),
                        null, line -> runOnFx(() -> {
                            switch (line) {
                                case "READY" -> { updateRecordingState(context.isRecording()); Log.info("Native Linux tray indicator ready"); }
                                case "open" -> showStage();
                                case "record" -> controller.startRecording(org.firefly.screenrec.core.recorder.CaptureSource.Kind.MONITOR, null, null, null);
                                case "stop" -> controller.stopRecording();
                                case "folder" -> FxScheduler.background(() -> PlatformUtils.openFolder(context.outputDirectory()));
                                case "quit" -> quit();
                                default -> { }
                            }
                        }), line -> Log.debug("Linux tray: " + line), code -> {
                            if (!disposing) Log.warn("Native Linux tray exited: " + code);
                        });
                if (disposing) linuxTray.kill();
                else linuxTray.writeLine(context.isRecording() ? "recording" : "idle");
            } catch (Exception e) {
                Log.warn("Native Linux tray unavailable: " + e.getMessage());
            }
        });
    }

    /** Enables/disables tray actions to match the current recording state. */
    public void updateRecordingState(boolean recording) {
        if (linuxTray != null) linuxTray.writeLine(recording ? "recording" : "idle");
        this.recording = recording;
        refreshLanguage();
    }

    /** AWT menu updates are dispatched on the AWT event thread. */
    private void refreshLanguage() {
        String open = I18n.t("tray.open");
        String record = I18n.t("tray.record");
        String stop = I18n.t("tray.stop");
        String folder = I18n.t("tray.folder");
        String quit = I18n.t("tray.quit");
        boolean active = recording;
        String tooltip = active ? I18n.t("tray.recording") : "ScreenRecorder";
        EventQueue.invokeLater(() -> {
            if (trayIcon == null) return;
            openItem.setLabel(open);
            recordItem.setLabel(record);
            stopItem.setLabel(stop);
            folderItem.setLabel(folder);
            quitItem.setLabel(quit);
            recordItem.setEnabled(!active);
            stopItem.setEnabled(active);
            trayIcon.setToolTip(tooltip);
        });
    }

    /** Removes the icon; safe to call multiple times. */
    public void dispose() {
        disposing = true;
        if (linuxTray != null) { linuxTray.writeLine("quit"); linuxTray.kill(); linuxTray = null; }
        if (linuxIconDirectory != null) {
            try {
                java.nio.file.Files.deleteIfExists(linuxIconDirectory.resolve("screenrecorder.png"));
                java.nio.file.Files.deleteIfExists(linuxIconDirectory);
            } catch (java.io.IOException e) { Log.debug("Tray icon cleanup: " + e.getMessage()); }
        }
        I18n.languageProperty().removeListener(languageListener);
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception e) {
                Log.debug("Tray removal failed: " + e.getMessage());
            }
            trayIcon = null;
        }
    }

    private void showStage() {
        if (stage == null) {
            return;
        }
        if (!stage.isShowing()) {
            stage.show();
        }
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.toFront();
        stage.requestFocus();
    }

    private void quit() {
        RecordingSession session = context.activeSession();
        if (session != null && session.isActive()) {
            session.stop();
        }
        dispose();
        Platform.exit();
        System.exit(0);
    }

    private void runOnFx(Runnable action) {
        FxScheduler.run(action);
    }

    /** Uses the same PNG branding as the application window. */
    private BufferedImage createTrayImage() {
        return javafx.embed.swing.SwingFXUtils.fromFXImage(
                org.firefly.screenrec.ui.theme.AppIcons.applicationPng(32), null);
    }
}

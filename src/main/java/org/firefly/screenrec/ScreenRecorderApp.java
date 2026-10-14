package org.firefly.screenrec;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.MainWindow;
import org.firefly.screenrec.ui.RecordingController;
import org.firefly.screenrec.ui.theme.ThemeManager;
import org.firefly.screenrec.ui.tray.TrayManager;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaFX application entry point.
 *
 * <p>Launched by {@link Launcher} when started outside the JavaFX module path,
 * or directly by {@code mvn javafx:run}.
 */
public class ScreenRecorderApp extends Application {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /** Allows {@code java -cp ... ScreenRecorderApp} to start the app directly. */
    public static void main(String[] args) {
        launch(args);
    }

    private AppContext context;
    private MainWindow window;
    private TrayManager trayManager;

    @Override
    public void init() {
        Log.init();
        Log.info("ScreenRecorder starting — " + PlatformUtils.current()
                + " / Java " + System.getProperty("java.version"));

        // AppContext.get() already performs FFmpeg discovery and capability
        // probing exactly once (and caches it). Re-running it here would spawn
        // the probe process several more times and delay startup by seconds.
        context = AppContext.get();
        Log.info("FFmpeg available: " + context.hasFfmpeg());

        // Restore the persisted UI language before any view is constructed so
        // the very first render is already localised.
        I18n.setLanguage(context.settings().getLanguage());
        Log.info("UI language: " + I18n.language().id());
    }

    @Override
    public void start(Stage primaryStage) {
        if (!STARTED.compareAndSet(false, true)) {
            Log.warn("ScreenRecorderApp.start called more than once — ignoring");
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Log.error("Uncaught exception on " + thread.getName(), error);
            showErrorDialog("An unexpected error occurred",
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        });

        ThemeManager.setAccentColor(context.settings().getAccentColor());
        ThemeManager themeManager = new ThemeManager(context.settings().getTheme());
        RecordingController controller = new RecordingController(context);
        trayManager = new TrayManager(context, controller, message ->
                Log.info("tray: " + message));

        window = new MainWindow(context, controller, themeManager, trayManager);
        window.show();

        // The tray needs the stage for "show window" actions.
        trayManager.install(window.stage());

        // Keep the title-bar REC indicator in sync with the recorder.
        context.activeSessionProperty().addListener((obs, old, session) -> {
            boolean recording = session != null && session.isActive();
            FxScheduler.run(() -> window.updateRecordingState(recording));
        });

        // Post-startup: if FFmpeg is missing, guide the user once the UI is up.
        if (!context.hasFfmpeg()) {
            Platform.runLater(this::promptForFfmpeg);
        } else {
            // Otherwise make sure we own our engine: when the current binary is an
            // unmanaged copy discovered on this machine, fetch the app's own
            // build in the background so the app stops depending on it.
            context.ensureManagedFfmpeg();
        }
    }

    private void promptForFfmpeg() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.t("dialog.ffmpeg.title"));
        alert.setHeaderText(I18n.t("dialog.ffmpeg.header"));
        alert.setContentText(I18n.t("dialog.ffmpeg.body"));
        alert.initOwner(window.stage());
        alert.showAndWait();
    }

    private void showErrorDialog(String header, String content) {
        FxScheduler.run(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("ScreenRecorder");
                alert.setHeaderText(header);
                alert.setContentText(content);
                alert.show();
            } catch (Exception e) {
                Log.error("Could not show the error dialog", e);
            }
        });
    }

    @Override
    public void stop() {
        Log.info("ScreenRecorder shutting down");
        if (window != null) {
            window.dispose();
        }
        if (trayManager != null) {
            trayManager.dispose();
        }
        if (context != null) {
            context.shutdown();
        }
    }
}

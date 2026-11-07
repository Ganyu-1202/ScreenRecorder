package org.firefly.screenrec.ui;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.ui.components.Sidebar;
import org.firefly.screenrec.ui.components.Toast;
import org.firefly.screenrec.ui.components.TooltipLayer;
import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.ThemeManager;
import org.firefly.screenrec.ui.tray.TrayManager;
import org.firefly.screenrec.ui.views.EditorView;
import org.firefly.screenrec.ui.views.LibraryView;
import org.firefly.screenrec.ui.views.RecordView;
import org.firefly.screenrec.ui.views.SettingsView;
import org.firefly.screenrec.util.FxScheduler;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.Map;

/**
 * The main window: a native system frame, a navigation sidebar, a swappable
 * content area and a toast layer.
 */
public final class MainWindow {

    private final AppContext context;
    private final RecordingController controller;
    private final ThemeManager themeManager;
    private final TrayManager trayManager;

    // Let each OS/window manager own the frame, work area, restore bounds,
    // dragging and resizing. A simulated maximized state cannot stay in sync
    // with taskbar/system-menu commands (or tiling window managers).
    private final Stage stage = new Stage(StageStyle.DECORATED);
    private final StackPane rootStack = new StackPane();
    private final BorderPane shell = new BorderPane();
    private final StackPane contentHost = new StackPane();

    private Sidebar sidebar = new Sidebar();

    private final Map<Sidebar.Nav, Node> viewCache = new EnumMap<>(Sidebar.Nav.class);

    private Toast toast;
    private TooltipLayer tooltips;
    private Label recordingIndicator;
    private Timeline recordingTicker;

    private RecordView recordView;
    private LibraryView libraryView;
    private SettingsView settingsView;
    private EditorView editorView;
    private Sidebar.Nav currentNav;

    private boolean forceQuit;

    public MainWindow(AppContext context, RecordingController controller,
                      ThemeManager themeManager, TrayManager trayManager) {
        this.context = context;
        this.controller = controller;
        this.themeManager = themeManager;
        this.trayManager = trayManager;
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    public void show() {
        org.firefly.screenrec.ui.theme.AppIcons.applyWindowIcons(stage);
        shell.setLeft(sidebar);
        shell.setTop(buildRecordingHeader());
        shell.setCenter(contentHost);

        contentHost.getStyleClass().add("content-host");

        rootStack.getChildren().add(shell);
        rootStack.getStyleClass().add("app-root");

        toast = Toast.install(rootStack);
        tooltips = new TooltipLayer(rootStack);

        // Size the scene from the stored geometry; the stage follows its scene,
        // so this is what actually decides the window's initial dimensions.
        double sw = sanitizeWidth(context.settings().getWindowWidth());
        double sh = sanitizeHeight(context.settings().getWindowHeight());
        Scene scene = new Scene(rootStack, sw, sh);
        themeManager.applyCurrent(scene);

        installShortcuts(scene);
        installWindowPersistence();
        installCloseBehaviour();

        sidebar.selectedProperty().addListener((obs, old, nav) -> {
            if (nav != null) {
                showView(nav);
            }
        });
        sidebar.select(Sidebar.Nav.RECORD);
        showView(Sidebar.Nav.RECORD);

        wireController();

        stage.setScene(scene);
        stage.setTitle(I18n.t("app.name"));
        stage.setMinWidth(880);
        stage.setMinHeight(600);
        NativeWindowState.install(stage);
        stage.show();

        // Geometry must be applied after the first layout pass. Sizing a
        // freshly shown stage is otherwise overridden by the scene's preferred
        // size, which is why the window ignored the stored dimensions.
        javafx.application.Platform.runLater(() -> {
            applyStoredPosition();
        });
    }

    public Stage stage() {
        return stage;
    }

    private void wireController() {
        controller.setOnStarted(() -> FxScheduler.run(() -> {
            if (context.settings().isMinimizeOnRecording()) {
                stage.setIconified(true);
            }
        }));
        controller.setOnDiscarded(() -> FxScheduler.run(() -> {
            if (libraryView != null) libraryView.refresh();
            if (recordView != null) recordView.setRecordingActive(false);
        }));
        controller.setOnSaved(path -> {
            FxScheduler.run(() -> {
                if (toast != null) {
                    toast.success(I18n.t("toast.saved"), path.getFileName().toString());
                }
                if (libraryView != null) {
                    libraryView.refresh();
                }
                if (recordView != null) {
                    recordView.setRecordingActive(false);
                }
            });
        });
        controller.setOnError(message -> FxScheduler.run(() -> {
            if (toast != null) {
                toast.error(I18n.t("toast.problem"), message);
            }
            if (recordView != null) {
                recordView.setRecordingActive(false);
            }
        }));
    }

    // ------------------------------------------------------------------
    // Recording status (window controls belong to the native title bar)
    // ------------------------------------------------------------------

    private Node buildRecordingHeader() {
        recordingIndicator = new Label("● " + I18n.t("win.recBadge"));
        recordingIndicator.getStyleClass().add("recording-badge");
        recordingIndicator.setVisible(false);
        recordingIndicator.setManaged(false);
        HBox header = new HBox(recordingIndicator);
        header.setAlignment(Pos.CENTER_RIGHT);
        header.setPadding(new Insets(5, 14, 5, 14));
        // Do not leave an empty second title bar when not recording. Keep the
        // header present while the badge blinks to avoid relayout every tick.
        header.setVisible(false);
        header.setManaged(false);
        return header;
    }

    private Node buildContentHost() {
        contentHost.getStyleClass().add("transparent-bg");
        contentHost.setPadding(Insets.EMPTY);
        return contentHost;
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void showView(Sidebar.Nav nav) {
        // Release the media player when leaving the editor, so a paused clip does
        // not keep a file handle open when the user deletes it from the Library.
        if (currentNav == Sidebar.Nav.EDITOR && nav != Sidebar.Nav.EDITOR
                && editorView != null) {
            editorView.shutdown();
        }
        currentNav = nav;
        Node view = viewCache.computeIfAbsent(nav, this::createView);
        contentHost.getChildren().setAll(view);
    }

    /**
     * Rebuilds every view and the chrome after a language change. Views are
     * constructed on demand, so clearing the cache and re-showing the current
     * one is enough to re-render all visible text.
     */
    public void reloadViews() {
        if (editorView != null) {
            editorView.shutdown();
        }
        viewCache.clear();
        recordView = null;
        libraryView = null;
        settingsView = null;
        editorView = null;
        refreshChromeText();
        rebuildSidebar();
        Sidebar.Nav target = currentNav == null ? Sidebar.Nav.RECORD : currentNav;
        currentNav = null;
        showView(target);
    }

    /** Updates strings living outside the cached views (title bar, badge). */
    private void refreshChromeText() {
        stage.setTitle(I18n.t("app.name"));
        if (recordingIndicator != null) {
            recordingIndicator.setText("● " + I18n.t("win.recBadge"));
        }
    }

    /** Recreates the sidebar so its navigation labels follow the language. */
    private void rebuildSidebar() {
        Sidebar.Nav selected = currentNav == null ? Sidebar.Nav.RECORD : currentNav;
        Sidebar fresh = new Sidebar();
        shell.setLeft(fresh);
        sidebar = fresh;
        fresh.selectedProperty().addListener((obs, old, nav) -> {
            if (nav != null) {
                showView(nav);
            }
        });
        fresh.select(selected);
    }

    private Node createView(Sidebar.Nav nav) {
        switch (nav) {
            case RECORD -> {
                recordView = new RecordView(context, controller, this::notify);
                return recordView;
            }
            case LIBRARY -> {
                libraryView = new LibraryView(context, this::notify);
                return libraryView;
            }
            case SETTINGS -> {
                settingsView = new SettingsView(context, themeManager, this::notify,
                        this::reloadViews);
                settingsView.setOnShortcutsChanged(() -> installShortcuts(stage.getScene()));
                return settingsView;
            }
            case EDITOR -> {
                editorView = new EditorView(context, this::notify);
                return editorView;
            }
            default -> {
                return placeholder(nav);
            }
        }
    }

    private Node placeholder(Sidebar.Nav nav) {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        Label title = UiFactory.heading(nav.label());
        Label text = UiFactory.secondary(I18n.t("win.comingSoon"));
        box.getChildren().addAll(title, text);
        return box;
    }

    private void notify(String message) {
        if (toast != null) {
            toast.info("ScreenRecorder", message);
        }
    }

    /** Shows a toast from outside the window (e.g. after a background task). */
    public void showToast(String title, String message) {
        FxScheduler.run(() -> {
            if (toast != null) {
                toast.info(title, message);
            }
        });
    }

    // ------------------------------------------------------------------
    // Recording indicator
    // ------------------------------------------------------------------

    private void startRecordingTicker() {
        stopRecordingTicker();
        recordingTicker = new Timeline(new KeyFrame(Duration.millis(600), e -> {
            boolean visible = recordingIndicator.isVisible();
            recordingIndicator.setVisible(!visible);
            recordingIndicator.setManaged(!visible);
        }));
        recordingTicker.setCycleCount(Animation.INDEFINITE);
        recordingTicker.play();
    }

    private void stopRecordingTicker() {
        if (recordingTicker != null) {
            recordingTicker.stop();
            recordingTicker = null;
        }
        recordingIndicator.setVisible(false);
        recordingIndicator.setManaged(false);
    }

    public void updateRecordingState(boolean recording) {
        shell.getTop().setVisible(recording);
        shell.getTop().setManaged(recording);
        if (recording) {
            startRecordingTicker();
            recordingIndicator.setVisible(true);
            recordingIndicator.setManaged(true);
        } else {
            stopRecordingTicker();
        }
        if (recordView != null) {
            recordView.setRecordingActive(recording);
        }
        if (trayManager != null) {
            trayManager.updateRecordingState(recording);
        }
    }

    // ------------------------------------------------------------------
    // Shortcuts
    // ------------------------------------------------------------------

    private void installShortcuts(Scene scene) {
        scene.getAccelerators().clear();
        java.util.Map<String, Runnable> actions = new java.util.HashMap<>();
        actions.put("pause", () -> controller.togglePause());
        actions.put("stop", () -> controller.stopRecording());
        actions.put("start", () -> {
            sidebar.select(Sidebar.Nav.RECORD);
            controller.startRecording(org.firefly.screenrec.core.recorder.CaptureSource.Kind.MONITOR,
                    null, null, null);
        });
        actions.put("library", () -> sidebar.select(Sidebar.Nav.LIBRARY));
        actions.put("settings", () -> sidebar.select(Sidebar.Nav.SETTINGS));
        Shortcuts.resolve(context.settings().getShortcuts()).forEach((action, key) ->
                scene.getAccelerators().put(KeyCombination.valueOf(key), actions.get(action)));
    }

    // ------------------------------------------------------------------
    // Window persistence & close behaviour
    // ------------------------------------------------------------------

    private void installWindowPersistence() {
        // Native state and geometry notifications need not arrive in the same
        // order. Save after they settle, never during maximize/minimize/fullscreen.
        javafx.animation.PauseTransition saveSize = new javafx.animation.PauseTransition(Duration.millis(500));
        saveSize.setOnFinished(e -> {
            if (stage.isShowing() && !stage.isMaximized() && !stage.isIconified()
                    && !stage.isFullScreen()) {
                context.settings().setWindowWidth(stage.getScene().getWidth());
                context.settings().setWindowHeight(stage.getScene().getHeight());
                context.saveSettings();
            }
        });
        stage.widthProperty().addListener((obs, old, val) -> saveSize.playFromStart());
        stage.heightProperty().addListener((obs, old, val) -> saveSize.playFromStart());
        stage.setOnHidden(e -> {
            saveSize.stop();
            context.saveSettings();
        });
    }

    /**
     * Clamps a stored window width into a usable range.
     *
     * <p>Stored values can be stale (a monitor that no longer exists) or absurd,
     * so they are validated before being trusted.
     */
    private static double sanitizeWidth(double value) {
        javafx.geometry.Rectangle2D visual =
                javafx.stage.Screen.getPrimary().getVisualBounds();
        if (!Double.isFinite(value) || value < 880) value = 1120;
        return Math.min(value, visual.getWidth());
    }

    private static double sanitizeHeight(double value) {
        javafx.geometry.Rectangle2D visual =
                javafx.stage.Screen.getPrimary().getVisualBounds();
        if (!Double.isFinite(value) || value < 600) value = 760;
        return Math.min(value, visual.getHeight());
    }

    private void applyStoredPosition() {
        stage.centerOnScreen();
        // Only size is persisted. Position is centered anew at each startup.
    }

    private void installCloseBehaviour() {
        stage.setOnCloseRequest(event -> {
            if (forceQuit) {
                return;
            }
            // Closing the window while recording would kill the video, so stop first.
            if (controller.isRecording()) {
                event.consume();
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.setTitle(I18n.t("dialog.recording.title"));
                alert.setHeaderText(I18n.t("dialog.recording.header"));
                alert.setContentText(I18n.t("dialog.recording.body"));
                alert.initOwner(stage);
                alert.showAndWait().ifPresent(button -> {
                    if (button == javafx.scene.control.ButtonType.OK) {
                        controller.stopRecording();
                        forceQuit = true;
                        stage.close();
                    }
                });
                return;
            }
            context.saveSettings();
        });
    }

    /** Called by the app when the whole application is shutting down. */
    public void dispose() {
        forceQuit = true;
        stopRecordingTicker();
        if (trayManager != null) {
            trayManager.dispose();
        }
    }
}

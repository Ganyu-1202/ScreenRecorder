package org.firefly.screenrec.ui;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.core.recorder.RecordingSession;
import org.firefly.screenrec.ui.components.Sidebar;
import org.firefly.screenrec.ui.components.Toast;
import org.firefly.screenrec.ui.components.TooltipLayer;
import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.Icons;
import org.firefly.screenrec.ui.theme.ThemeManager;
import org.firefly.screenrec.ui.tray.TrayManager;
import org.firefly.screenrec.ui.views.EditorView;
import org.firefly.screenrec.ui.views.LibraryView;
import org.firefly.screenrec.ui.views.RecordView;
import org.firefly.screenrec.ui.views.SettingsView;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The main window: a custom title bar, a navigation sidebar, a swappable
 * content area and a toast layer.
 */
public final class MainWindow {

    private final AppContext context;
    private final RecordingController controller;
    private final ThemeManager themeManager;
    private final TrayManager trayManager;

    // GTK transparent top-level windows have unreliable resize/WM behaviour.
    // Keep the custom title bar, but use a normal undecorated Linux window.
    private final Stage stage = new Stage(PlatformUtils.isLinux()
            ? StageStyle.UNDECORATED : StageStyle.TRANSPARENT);
    private final StackPane rootStack = new StackPane();
    private final javafx.scene.shape.Rectangle windowClip = new javafx.scene.shape.Rectangle();
    private final BorderPane shell = new BorderPane();
    private final StackPane contentHost = new StackPane();

    private Sidebar sidebar = new Sidebar();

    private final Map<Sidebar.Nav, Node> viewCache = new EnumMap<>(Sidebar.Nav.class);

    private Toast toast;
    private TooltipLayer tooltips;
    private Label recordingIndicator;
    private Timeline recordingTicker;
    private Button maximizeButton;
    private Label windowTitleLabel;

    private RecordView recordView;
    private LibraryView libraryView;
    private SettingsView settingsView;
    private EditorView editorView;
    private Sidebar.Nav currentNav;

    private double dragOffsetX;
    private double dragOffsetY;
    private boolean forceQuit;

    /**
     * Whether the window is currently in our own "maximised" state.
     *
     * <p>We cannot use {@code Stage.isMaximized()} for this: an UNDECORATED
     * stage is maximised by JavaFX straight to the monitor's full bounds,
     * ignoring the Windows work area, which hides the taskbar. Instead we
     * emulate maximise by resizing to the screen's visual bounds and track the
     * state here.
     */
    private boolean maximized;

    /** Geometry captured before maximising, restored when un-maximising. */
    private double restoreX;
    private double restoreY;
    private double restoreWidth;
    private double restoreHeight;

    /** Thickness of the invisible grab strips along each window edge. */
    private static final double RESIZE_EDGE = 8;
    /** Size of the square grab areas in each corner. */
    private static final double RESIZE_CORNER = 14;

    /**
     * Active edge/corner being dragged, or {@code null} when not resizing.
     * The value encodes which sides move: for example {@code "NE"} moves the
     * north and east edges together.
     */
    private String resizeEdge;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartW;
    private double resizeStartH;
    private double resizeStartWindowX;
    private double resizeStartWindowY;

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
        shell.setTop(buildTitleBar());
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
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        windowClip.widthProperty().bind(rootStack.widthProperty());
        windowClip.heightProperty().bind(rootStack.heightProperty());
        rootStack.setClip(windowClip);
        updateWindowCorners();
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
        stage.setTitle("ScreenRecorder");
        stage.setMinWidth(880);
        stage.setMinHeight(600);
        stage.show();

        // Geometry must be applied after the first layout pass. Sizing a
        // freshly shown stage is otherwise overridden by the scene's preferred
        // size, which is why the window ignored the stored dimensions.
        javafx.application.Platform.runLater(() -> {
            applyStoredPosition();
            updateMaximizeIcon();
        });
        installResizeHandles();
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
    // Title bar
    // ------------------------------------------------------------------

    /**
     * Toggles between the restored size and a work-area-filling size.
     *
     * <p>Deliberately does not call {@code Stage.setMaximized(true)}: for an
     * UNDECORATED stage JavaFX sizes the window to the whole monitor,
     * covering the taskbar. Resizing to the screen's visual bounds (which
     * excludes the taskbar and other appbars) keeps the taskbar visible and
     * behaves like a native maximise.
     */
    private void toggleMaximize() {
        if (maximized) {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreWidth);
            stage.setHeight(restoreHeight);
            maximized = false;
        } else {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreWidth = stage.getWidth();
            restoreHeight = stage.getHeight();

            javafx.geometry.Rectangle2D workArea = workAreaFor(stage);
            stage.setX(workArea.getMinX());
            stage.setY(workArea.getMinY());
            stage.setWidth(workArea.getWidth());
            stage.setHeight(workArea.getHeight());
            maximized = true;
        }
        updateMaximizeIcon();
        updateResizeHandlesEnabled();
    }

    /** The visual bounds of the screen the window currently occupies. */
    private static javafx.geometry.Rectangle2D workAreaFor(Stage stage) {
        double centerX = stage.getX() + stage.getWidth() / 2;
        double centerY = stage.getY() + stage.getHeight() / 2;
        for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
            javafx.geometry.Rectangle2D bounds = screen.getBounds();
            if (bounds.contains(centerX, centerY)) {
                return screen.getVisualBounds();
            }
        }
        return javafx.stage.Screen.getPrimary().getVisualBounds();
    }

    /** Swaps the maximise button glyph between "maximise" and "restore". */
    private void updateWindowCorners() {
        windowClip.setArcWidth(maximized || PlatformUtils.isLinux() ? 0 : 24);
        windowClip.setArcHeight(maximized || PlatformUtils.isLinux() ? 0 : 24);
    }

    private void updateMaximizeIcon() {
        updateWindowCorners();
        if (maximizeButton == null) {
            return;
        }
        String icon = maximized ? Icons.RESTORE : Icons.MAXIMIZE;
        maximizeButton.setGraphic(UiFactory.iconBox(icon, 18));
        setWindowControlTooltip(maximizeButton,
                I18n.t(maximized ? "win.restore" : "win.maximize"));
    }

    /**
     * Routes a window-control hint through the in-window tooltip layer.
     *
     * <p>These controls sit on the title bar of an undecorated stage, which is
     * exactly where floating tooltips flicker. The layer paints the hint inside
     * the window instead, so it appears once and stays put.
     */
    private void setWindowControlTooltip(Node node, String text) {
        if (node == null) {
            return;
        }
        if (tooltips != null) {
            tooltips.attach(node, text);
        } else {
            UiFactory.installTooltip(node, text);
        }
    }

    private Node buildTitleBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5, 10, 5, 14));
        bar.setPrefHeight(46);
        bar.setMinHeight(46);

        javafx.scene.image.ImageView logoDot = new javafx.scene.image.ImageView(
                org.firefly.screenrec.ui.theme.AppIcons.applicationPng(48));
        logoDot.setFitWidth(24);
        logoDot.setFitHeight(24);
        logoDot.setPreserveRatio(true);
        logoDot.setMouseTransparent(true);

        windowTitleLabel = new Label(I18n.t("app.name"));
        windowTitleLabel.getStyleClass().add("title-bar-title");

        recordingIndicator = new Label("● " + I18n.t("win.recBadge"));
        recordingIndicator.getStyleClass().add("recording-badge");
        recordingIndicator.setVisible(false);
        recordingIndicator.setManaged(false);

        Button minimize = UiFactory.iconButton(Icons.MINIMIZE, null, "window-control");
        setWindowControlTooltip(minimize, I18n.t("win.minimize"));
        minimize.setOnAction(e -> stage.setIconified(true));

        Button maximize = UiFactory.iconButton(Icons.MAXIMIZE, null, "window-control");
        maximizeButton = maximize;
        setWindowControlTooltip(maximize, I18n.t("win.maximize"));
        maximize.setOnAction(e -> toggleMaximize());

        Button close = UiFactory.iconButton(Icons.CLOSE, null,
                "window-control", "window-control-close");
        setWindowControlTooltip(close, I18n.t("win.close"));
        close.setOnAction(e -> stage.close());

        Region spacer = UiFactory.hSpacer();
        bar.setOnMousePressed(e -> {
            if (!isTitleDragTarget(e.getTarget(), bar) || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            if (!isTitleDragTarget(e.getTarget(), bar) || !e.isPrimaryButtonDown()) return;
            if (maximized) {
                // Native behaviour: dragging a maximised window restores it and
                // keeps it under the cursor, so the drag continues naturally.
                double ratio = restoreWidth > 0
                        ? (e.getScreenX() - stage.getX()) / Math.max(stage.getWidth(), 1)
                        : 0.5;
                maximized = false;
                stage.setWidth(restoreWidth);
                stage.setHeight(restoreHeight);
                stage.setX(e.getScreenX() - restoreWidth * ratio);
                stage.setY(Math.max(0, e.getScreenY() - 23));
                updateMaximizeIcon();
                updateResizeHandlesEnabled();
                dragOffsetX = restoreWidth * ratio;
                dragOffsetY = 23;
                return;
            }
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        bar.setOnMouseClicked(e -> {
            if (isTitleDragTarget(e.getTarget(), bar) && e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2) {
                toggleMaximize();
            }
        });
        spacer.setMinHeight(36);

        HBox leftGroup = new HBox(9, logoDot, windowTitleLabel, recordingIndicator);
        leftGroup.setAlignment(Pos.CENTER_LEFT);

        bar.getChildren().addAll(leftGroup, spacer, minimize, maximize, close);
        return bar;
    }

    private static boolean isTitleDragTarget(Object target, Node bar) {
        if (!(target instanceof Node node)) return false;
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof javafx.scene.control.ButtonBase) return false;
            if (current == bar) return true;
        }
        return false;
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
        if (recordingIndicator != null) {
            recordingIndicator.setText("● " + I18n.t("win.recBadge"));
        }
        if (windowTitleLabel != null) {
            windowTitleLabel.setText(I18n.t("app.name"));
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
    // Window resizing
    // ------------------------------------------------------------------

    /**
     * Adds draggable edges and corners to the window.
     *
     * <p>An {@code UNDECORATED} stage has no system frame, so Windows offers no
     * resize hit-testing and the window can only be moved. This installs a set
     * of thin transparent strips along the inside of each edge (plus the four
     * corners) that resize the stage while dragged, which is what a native
     * frame would do.
     */
    private void installResizeHandles() {
        // The handles live in the root stack so they sit above the whole UI,
        // including the title bar and content. Each entry is
        // [edge code, horizontal anchor, vertical anchor].
        String[][] specs = {
                {"N", "mid", "top"}, {"S", "mid", "bottom"},
                {"W", "left", "mid"}, {"E", "right", "mid"},
                {"NW", "left", "top"}, {"NE", "right", "top"},
                {"SW", "left", "bottom"}, {"SE", "right", "bottom"},
        };
        for (String[] spec : specs) {
            rootStack.getChildren().add(resizeHandle(spec[0], spec[1], spec[2]));
        }

        // The strips are only meaningful while the window is in its restored
        // (non-maximised) state, exactly like a native window.
        updateResizeHandlesEnabled();

        // Capture at scene level as well: skins/overlay nodes may intercept
        // transparent strips. Edge hit-testing must not depend on the picked node.
        Scene scene = stage.getScene();
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
            String edge = edgeAt(e.getSceneX(), e.getSceneY(), scene.getWidth(), scene.getHeight());
            scene.setCursor(maximized || edge == null ? javafx.scene.Cursor.DEFAULT : resizeCursor(edge));
        });
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (!maximized && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                String edge = edgeAt(e.getSceneX(), e.getSceneY(), scene.getWidth(), scene.getHeight());
                if (edge != null) beginResize(e, edge);
            }
        });
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (resizeEdge != null) continueResize(e);
        });
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (resizeEdge != null) {
                resizeEdge = null;
                e.consume();
            }
        });
    }

    static String edgeAt(double x, double y, double width, double height) {
        if (x < 0 || y < 0 || x > width || y > height) return null;
        if ((x <= RESIZE_CORNER || x >= width - RESIZE_CORNER)
                && (y <= RESIZE_CORNER || y >= height - RESIZE_CORNER)) {
            return (y <= RESIZE_CORNER ? "N" : "S") + (x <= RESIZE_CORNER ? "W" : "E");
        }
        String vertical = y <= RESIZE_EDGE ? "N" : y >= height - RESIZE_EDGE ? "S" : "";
        String horizontal = x <= RESIZE_EDGE ? "W" : x >= width - RESIZE_EDGE ? "E" : "";
        if (!vertical.isEmpty() && (x <= RESIZE_CORNER || x >= width - RESIZE_CORNER))
            horizontal = x <= RESIZE_CORNER ? "W" : "E";
        if (!horizontal.isEmpty() && (y <= RESIZE_CORNER || y >= height - RESIZE_CORNER))
            vertical = y <= RESIZE_CORNER ? "N" : "S";
        String edge = vertical + horizontal;
        return edge.isEmpty() ? null : edge;
    }

    /**
     * Builds one resize strip and anchors it inside the window.
     *
     * @param edge      the edge code passed to {@link #beginResize}
     * @param horizontal {@code left}, {@code mid} or {@code right}
     * @param vertical   {@code top}, {@code mid} or {@code bottom}
     */
    private Region resizeHandle(String edge, String horizontal, String vertical) {
        Region r = new Region();
        r.getStyleClass().addAll("resize-handle", "resize-" + edge.toLowerCase(Locale.ROOT));
        r.setCursor(resizeCursor(edge));
        // Transparent Region fills do not reliably provide a pick target.
        // Hit-test the entire strip, including its unpainted rectangle.
        r.setPickOnBounds(true);
        r.setMouseTransparent(false);

        boolean spansWidth = "mid".equals(horizontal);   // top/bottom strip
        boolean spansHeight = "mid".equals(vertical);    // left/right strip

        if (spansWidth) {
            r.setMinHeight(RESIZE_EDGE);
            r.setPrefHeight(RESIZE_EDGE);
            r.setMaxHeight(RESIZE_EDGE);
        } else if (spansHeight) {
            r.setMinWidth(RESIZE_EDGE);
            r.setPrefWidth(RESIZE_EDGE);
            r.setMaxWidth(RESIZE_EDGE);
        } else {
            // Corner grab: fixed in both directions.
            r.setMinSize(RESIZE_CORNER, RESIZE_CORNER);
            r.setPrefSize(RESIZE_CORNER, RESIZE_CORNER);
            r.setMaxSize(RESIZE_CORNER, RESIZE_CORNER);
        }

        Pos pos = switch (horizontal + "/" + vertical) {
            case "mid/top" -> Pos.TOP_LEFT;
            case "mid/bottom" -> Pos.BOTTOM_LEFT;
            case "left/mid" -> Pos.TOP_LEFT;
            case "right/mid" -> Pos.TOP_RIGHT;
            case "left/top" -> Pos.TOP_LEFT;
            case "right/top" -> Pos.TOP_RIGHT;
            case "left/bottom" -> Pos.BOTTOM_LEFT;
            default -> Pos.BOTTOM_RIGHT;
        };
        StackPane.setAlignment(r, pos);

        // Keep edge strips clear of the corner grabs so their cursors and drag
        // behaviour never overlap.
        if (spansWidth) {
            StackPane.setMargin(r, new Insets(0, RESIZE_CORNER, 0, RESIZE_CORNER));
        } else if (spansHeight) {
            StackPane.setMargin(r, new Insets(RESIZE_CORNER, 0, RESIZE_CORNER, 0));
        }

        r.setOnMousePressed(e -> beginResize(e, edge));
        r.setOnMouseDragged(this::continueResize);
        r.setOnMouseReleased(e -> {
            resizeEdge = null;
            e.consume();
        });
        return r;
    }

    private static javafx.scene.Cursor resizeCursor(String edge) {
        return switch (edge) {
            case "N" -> javafx.scene.Cursor.N_RESIZE;
            case "S" -> javafx.scene.Cursor.S_RESIZE;
            case "W" -> javafx.scene.Cursor.W_RESIZE;
            case "E" -> javafx.scene.Cursor.E_RESIZE;
            case "NW" -> javafx.scene.Cursor.NW_RESIZE;
            case "NE" -> javafx.scene.Cursor.NE_RESIZE;
            case "SW" -> javafx.scene.Cursor.SW_RESIZE;
            case "SE" -> javafx.scene.Cursor.SE_RESIZE;
            default -> javafx.scene.Cursor.DEFAULT;
        };
    }

    private void beginResize(javafx.scene.input.MouseEvent e, String edge) {
        if (maximized) {
            return;
        }
        resizeEdge = edge;
        resizeStartX = e.getScreenX();
        resizeStartY = e.getScreenY();
        resizeStartW = stage.getWidth();
        resizeStartH = stage.getHeight();
        resizeStartWindowX = stage.getX();
        resizeStartWindowY = stage.getY();
        e.consume();
    }

    private void continueResize(javafx.scene.input.MouseEvent e) {
        if (resizeEdge == null || maximized) {
            return;
        }
        double dx = e.getScreenX() - resizeStartX;
        double dy = e.getScreenY() - resizeStartY;

        double minW = stage.getMinWidth() > 0 ? stage.getMinWidth() : 320;
        double minH = stage.getMinHeight() > 0 ? stage.getMinHeight() : 240;

        double x = resizeStartWindowX;
        double y = resizeStartWindowY;
        double w = resizeStartW;
        double h = resizeStartH;

        if (resizeEdge.contains("E")) {
            w = Math.max(minW, resizeStartW + dx);
        }
        if (resizeEdge.contains("S")) {
            h = Math.max(minH, resizeStartH + dy);
        }
        if (resizeEdge.contains("W")) {
            // Moving the left edge keeps the right edge pinned.
            double right = resizeStartWindowX + resizeStartW;
            w = Math.max(minW, resizeStartW - dx);
            x = right - w;
        }
        if (resizeEdge.contains("N")) {
            double bottom = resizeStartWindowY + resizeStartH;
            h = Math.max(minH, resizeStartH - dy);
            y = bottom - h;
        }

        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
        e.consume();
    }

    /** Shows or hides every resize strip according to the maximise state. */
    private void updateResizeHandlesEnabled() {
        boolean enabled = !maximized;
        for (Node n : rootStack.getChildren()) {
            if (n.getStyleClass().contains("resize-handle")) {
                n.setVisible(enabled);
                n.setMouseTransparent(!enabled);
            }
        }
    }

    // ------------------------------------------------------------------
    // Window persistence & close behaviour
    // ------------------------------------------------------------------

    private void installWindowPersistence() {
        javafx.animation.PauseTransition saveSize = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        saveSize.setOnFinished(e -> context.saveSettings());
        stage.widthProperty().addListener((obs, old, val) -> {
            if (!maximized && val.doubleValue() > 200) {
                context.settings().setWindowWidth(val.doubleValue());
                saveSize.playFromStart();
            }
        });
        stage.heightProperty().addListener((obs, old, val) -> {
            if (!maximized && val.doubleValue() > 200) {
                context.settings().setWindowHeight(val.doubleValue());
                saveSize.playFromStart();
            }
        });
        stage.setOnHidden(e -> context.saveSettings());
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
        restoreWidth = stage.getWidth();
        restoreHeight = stage.getHeight();
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

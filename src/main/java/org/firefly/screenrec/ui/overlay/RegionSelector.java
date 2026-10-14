package org.firefly.screenrec.ui.overlay;

import org.firefly.screenrec.core.screen.ScreenInfo;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.ui.i18n.I18n;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * A full-screen overlay that lets the user drag out a capture region.
 *
 * <p>Features: dimmed surround with a clear "hole" over the selection, live size
 * readout, a magnifier loupe for pixel-accurate edges, arrow-key nudging, and
 * resize handles on an existing selection.
 */
public final class RegionSelector {

    private static final double HANDLE = 8;
    private static final double MIN_SIZE = 16;
    private static final double LOUPE_SIZE = 120;
    private static final int LOUPE_ZOOM = 6;

    private final ScreenInfo screen;
    private final Image screenImage;
    private final Consumer<Rectangle2D> onSelected;
    private final Runnable onCancelled;

    private javafx.stage.Window stage;
    private Pane root;
    private Canvas dimCanvas;
    private Rectangle selection;
    private Label sizeBadge;
    private Label hint;
    private Circle loupeRing;
    private ImageView loupeView;

    private double startX;
    private double startY;
    private boolean dragging;
    /** Which handle is being dragged: 0=none, 1=nw, 2=ne, 3=sw, 4=se, 5=move. */
    private int activeHandle;
    private double moveOffsetX;
    private double moveOffsetY;
    private Rectangle2D current;

    private RegionSelector(ScreenInfo screen, Image screenImage,
                           Consumer<Rectangle2D> onSelected, Runnable onCancelled) {
        this.screen = screen;
        this.screenImage = screenImage;
        this.onSelected = onSelected;
        this.onCancelled = onCancelled;
    }

    /**
     * Opens the selector over a display.
     *
     * @param screen      the display to select within
     * @param screenshot  a screenshot of that display, used for magnifying
     * @param onSelected  invoked with the chosen region in screen coordinates
     * @param onCancelled invoked when the user aborts (Esc / right-click)
     */
    public static RegionSelector open(ScreenInfo screen, Image screenshot,
                                      Consumer<Rectangle2D> onSelected,
                                      Runnable onCancelled) {
        RegionSelector selector = new RegionSelector(screen, screenshot, onSelected, onCancelled);
        selector.start();
        return selector;
    }

    private void start() {
        root = new Pane();
        root.getStyleClass().add("region-overlay");
        root.setStyle("-fx-background-color: transparent;");
        root.setOnMouseEntered(e -> { if (stage != null) stage.requestFocus(); });

        Rectangle2D bounds = screen.visualBounds();

        // Background screenshot, so the dimmed area still shows content.
        if (screenImage != null) {
            ImageView backdrop = new ImageView(screenImage);
            backdrop.setFitWidth(bounds.getWidth());
            backdrop.setFitHeight(bounds.getHeight());
            backdrop.setPreserveRatio(false);
            backdrop.setOpacity(0.85);
            root.getChildren().add(backdrop);
        }

        // Dimming layer drawn with holes punched out.
        dimCanvas = new Canvas(bounds.getWidth(), bounds.getHeight());
        dimCanvas.setMouseTransparent(true);
        root.getChildren().add(dimCanvas);
        drawDim(null);

        // Selection rectangle.
        selection = new Rectangle(0, 0, 0, 0);
        selection.getStyleClass().add("region-selection");
        selection.setFill(Color.TRANSPARENT);
        selection.setStroke(Color.web("#ff4d5e"));
        selection.setStrokeWidth(2);
        selection.setVisible(false);
        selection.setMouseTransparent(false);
        root.getChildren().add(selection);

        // Size badge.
        sizeBadge = new Label("");
        sizeBadge.getStyleClass().add("region-size-badge");
        sizeBadge.setVisible(false);
        sizeBadge.setMouseTransparent(true);
        root.getChildren().add(sizeBadge);

        // Loupe.
        buildLoupe();
        root.getChildren().addAll(loupeRing, loupeView);
        setLoupeVisible(false);

        // Hint text.
        hint = new Label(I18n.t("overlay.region.hint"));
        hint.getStyleClass().add("region-hint");
        hint.setMouseTransparent(true);

        VBox hintBox = new VBox(hint);
        hintBox.setAlignment(Pos.CENTER);
        StackPane hintLayer = new StackPane(hintBox);
        hintLayer.setAlignment(Pos.BOTTOM_CENTER);
        hintLayer.setMouseTransparent(true);
        hintLayer.setPrefHeight(bounds.getHeight());
        hintLayer.setPrefWidth(bounds.getWidth());
        VBox.setMargin(hint, new javafx.geometry.Insets(0, 0, 60, 0));
        root.getChildren().add(hintLayer);

        installMouseHandlers();
        installKeyboardHandlers();

        Scene scene;
        if (org.firefly.screenrec.util.PlatformUtils.isLinux()) {
            // GTK popup windows use override-redirect: Mutter must not relocate,
            // maximize or clip one display's selection layer onto another display.
            var owner = javafx.stage.Window.getWindows().stream()
                    .filter(w -> w instanceof Stage && w.isShowing()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Region selection requires an application window"));
            var popup = new javafx.stage.Popup();
            popup.setAutoFix(false);
            popup.setAutoHide(false);
            popup.setHideOnEscape(false);
            root.setPrefSize(bounds.getWidth(), bounds.getHeight());
            popup.getContent().add(root);
            scene = popup.getScene();
            scene.setFill(Color.TRANSPARENT);
            OverlayWindow.applyStylesheets(scene);
            stage = popup;
            popup.show(owner, bounds.getMinX(), bounds.getMinY());
        } else {
            scene = new Scene(root, bounds.getWidth(), bounds.getHeight());
            scene.setFill(Color.TRANSPARENT);
            OverlayWindow.applyStylesheets(scene);
            var window = new Stage(StageStyle.TRANSPARENT);
            window.setScene(scene);
            window.setX(bounds.getMinX());
            window.setY(bounds.getMinY());
            window.setWidth(bounds.getWidth());
            window.setHeight(bounds.getHeight());
            window.setAlwaysOnTop(true);
            window.setTitle(I18n.t("overlay.region.title"));
            OverlayWindow.makeFloating(window);
            stage = window;
            window.show();
            window.toFront();
        }
        root.requestFocus();

        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancel();
            } else if (e.getCode() == KeyCode.ENTER) {
                confirm();
            }
        });
    }

    private void buildLoupe() {
        loupeRing = new Circle(LOUPE_SIZE / 2);
        loupeRing.getStyleClass().add("loupe");
        loupeRing.setFill(Color.TRANSPARENT);
        loupeRing.setStroke(Color.web("#ff4d5e"));
        loupeRing.setStrokeWidth(2);
        loupeRing.setMouseTransparent(true);
        loupeRing.setVisible(false);

        loupeView = new ImageView();
        loupeView.setFitWidth(LOUPE_SIZE);
        loupeView.setFitHeight(LOUPE_SIZE);
        loupeView.setPreserveRatio(false);
        loupeView.setMouseTransparent(true);
        loupeView.setVisible(false);

        // Clip the magnified image to the circular ring.
        Rectangle clip = new Rectangle(LOUPE_SIZE, LOUPE_SIZE);
        clip.setArcWidth(LOUPE_SIZE);
        clip.setArcHeight(LOUPE_SIZE);
        loupeView.setClip(clip);
    }

    private void setLoupeVisible(boolean visible) {
        loupeRing.setVisible(visible);
        loupeView.setVisible(visible);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Dims everything except the given selection (null dims everything). */
    private void drawDim(Rectangle2D sel) {
        GraphicsContext gc = dimCanvas.getGraphicsContext2D();
        double w = dimCanvas.getWidth();
        double h = dimCanvas.getHeight();
        gc.clearRect(0, 0, w, h);
        gc.setFill(Color.rgb(4, 6, 10, 0.45));

        if (sel == null || sel.getWidth() <= 0 || sel.getHeight() <= 0) {
            gc.fillRect(0, 0, w, h);
            return;
        }

        // Four rectangles around the hole.
        double x = sel.getMinX();
        double y = sel.getMinY();
        double sw = sel.getWidth();
        double sh = sel.getHeight();

        gc.fillRect(0, 0, w, y);                          // top
        gc.fillRect(0, y + sh, w, h - (y + sh));          // bottom
        gc.fillRect(0, y, x, sh);                         // left
        gc.fillRect(x + sw, y, w - (x + sw), sh);         // right
    }

    private void updateBadge(Rectangle2D sel) {
        if (sel == null) {
            sizeBadge.setVisible(false);
            return;
        }
        sizeBadge.setVisible(true);
        sizeBadge.setText(String.format("%d × %d", (int) sel.getWidth(), (int) sel.getHeight()));

        double bx = sel.getMinX();
        double by = sel.getMinY() - 30;
        if (by < 6) {
            // Flip below the selection when there's no room above.
            by = sel.getMinY() + sel.getHeight() + 8;
        }
        double bw = sizeBadge.prefWidth(-1);
        sizeBadge.relocate(Math.max(6, bx), Math.max(6, by));
        sizeBadge.resize(bw, sizeBadge.prefHeight(bw));
    }

    // ------------------------------------------------------------------
    // Mouse handling
    // ------------------------------------------------------------------

    private void installMouseHandlers() {
        root.setOnMousePressed(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                cancel();
                return;
            }
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                return;
            }

            double x = e.getX();
            double y = e.getY();

            int handle = handleAt(x, y);
            if (handle > 0) {
                activeHandle = handle;
                if (handle == 5) {
                    moveOffsetX = x - current.getMinX();
                    moveOffsetY = y - current.getMinY();
                }
            } else {
                // Start a brand-new selection.
                activeHandle = 4; // treat as dragging the SE corner
                dragging = true;
                startX = x;
                startY = y;
                selection.setVisible(true);
            }
            updateLoupe(x, y);
            e.consume();
        });

        root.setOnMouseDragged(e -> {
            double x = clamp(e.getX(), 0, root.getWidth());
            double y = clamp(e.getY(), 0, root.getHeight());

            if (dragging) {
                current = normalize(startX, startY, x, y);
            } else if (activeHandle == 5 && current != null) {
                current = moveSelection(x - moveOffsetX, y - moveOffsetY);
            } else if (activeHandle > 0 && current != null) {
                current = resizeSelection(activeHandle, x, y);
            }

            if (current != null) {
                applySelection(current);
            }
            updateLoupe(x, y);
            e.consume();
        });

        root.setOnMouseReleased(e -> {
            dragging = false;
            activeHandle = 0;
            if (current != null && (current.getWidth() < MIN_SIZE || current.getHeight() < MIN_SIZE)) {
                // Too small to be intentional — reset.
                current = null;
                selection.setVisible(false);
                sizeBadge.setVisible(false);
                drawDim(null);
            }
            e.consume();
        });

        root.setOnMouseMoved(e -> {
            updateLoupe(e.getX(), e.getY());
            int handle = handleAt(e.getX(), e.getY());
            root.setCursor(switch (handle) {
                case 1 -> javafx.scene.Cursor.NW_RESIZE;
                case 2 -> javafx.scene.Cursor.NE_RESIZE;
                case 3 -> javafx.scene.Cursor.SW_RESIZE;
                case 4 -> javafx.scene.Cursor.SE_RESIZE;
                case 5 -> javafx.scene.Cursor.MOVE;
                default -> javafx.scene.Cursor.CROSSHAIR;
            });
        });
    }

    private void installKeyboardHandlers() {
        // Arrow keys nudge the selection by 1px (10px with Shift).
        root.setFocusTraversable(true);
        root.setOnKeyPressed(e -> {
            if (current == null) {
                return;
            }
            double step = e.isShiftDown() ? 10 : 1;
            Rectangle2D next = switch (e.getCode()) {
                case LEFT -> offset(current, -step, 0);
                case RIGHT -> offset(current, step, 0);
                case UP -> offset(current, 0, -step);
                case DOWN -> offset(current, 0, step);
                default -> null;
            };
            if (next != null) {
                current = next;
                applySelection(current);
                e.consume();
            }
        });
    }

    private void updateLoupe(double x, double y) {
        if (screenImage == null) {
            return;
        }
        boolean nearEdge = dragging || activeHandle > 0;
        if (!nearEdge) {
            setLoupeVisible(false);
            return;
        }
        setLoupeVisible(true);

        double srcW = LOUPE_SIZE / LOUPE_ZOOM;
        double srcX = x - srcW / 2;
        double srcY = y - srcW / 2;

        double imgW = screenImage.getWidth();
        double imgH = screenImage.getHeight();

        // Clamp the source window to the image, adjusting the viewport so the
        // loupe never shows empty space at the borders.
        double clampedX = clamp(srcX, 0, Math.max(0, imgW - srcW));
        double clampedY = clamp(srcY, 0, Math.max(0, imgH - srcW));

        loupeView.setViewport(new Rectangle2D(clampedX, clampedY, srcW, srcW));
        loupeView.setImage(screenImage);

        // Place the loupe near the cursor, flipping when close to an edge.
        double lx = x + 24;
        double ly = y + 24;
        if (lx + LOUPE_SIZE > root.getWidth()) {
            lx = x - LOUPE_SIZE - 24;
        }
        if (ly + LOUPE_SIZE > root.getHeight()) {
            ly = y - LOUPE_SIZE - 24;
        }
        loupeRing.setCenterX(lx + LOUPE_SIZE / 2);
        loupeRing.setCenterY(ly + LOUPE_SIZE / 2);
        loupeView.relocate(lx, ly);
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private Rectangle2D normalize(double x1, double y1, double x2, double y2) {
        double minX = Math.min(x1, x2);
        double minY = Math.min(y1, y2);
        double w = Math.abs(x2 - x1);
        double h = Math.abs(y2 - y1);
        return new Rectangle2D(minX, minY, w, h);
    }

    private Rectangle2D resizeSelection(int handle, double x, double y) {
        if (current == null) {
            return null;
        }
        double x1 = current.getMinX();
        double y1 = current.getMinY();
        double x2 = current.getMaxX();
        double y2 = current.getMaxY();

        switch (handle) {
            case 1 -> { // NW
                x1 = x;
                y1 = y;
            }
            case 2 -> { // NE
                x2 = x;
                y1 = y;
            }
            case 3 -> { // SW
                x1 = x;
                y2 = y;
            }
            case 4 -> { // SE
                x2 = x;
                y2 = y;
            }
            default -> {
                return current;
            }
        }
        return normalize(x1, y1, x2, y2);
    }

    private Rectangle2D moveSelection(double x, double y) {
        double w = current.getWidth();
        double h = current.getHeight();
        double nx = clamp(x, 0, root.getWidth() - w);
        double ny = clamp(y, 0, root.getHeight() - h);
        return new Rectangle2D(nx, ny, w, h);
    }

    private static Rectangle2D offset(Rectangle2D r, double dx, double dy) {
        return new Rectangle2D(r.getMinX() + dx, r.getMinY() + dy, r.getWidth(), r.getHeight());
    }

    /** Returns the handle under a point: 1=NW 2=NE 3=SW 4=SE 5=move 0=none. */
    private int handleAt(double x, double y) {
        if (current == null || !selection.isVisible()) {
            return 0;
        }
        double tol = HANDLE + 3;
        boolean left = Math.abs(x - current.getMinX()) <= tol;
        boolean right = Math.abs(x - current.getMaxX()) <= tol;
        boolean top = Math.abs(y - current.getMinY()) <= tol;
        boolean bottom = Math.abs(y - current.getMaxY()) <= tol;

        if (left && top) {
            return 1;
        }
        if (right && top) {
            return 2;
        }
        if (left && bottom) {
            return 3;
        }
        if (right && bottom) {
            return 4;
        }
        if (x >= current.getMinX() && x <= current.getMaxX()
                && y >= current.getMinY() && y <= current.getMaxY()) {
            return 5;
        }
        return 0;
    }

    private void applySelection(Rectangle2D sel) {
        selection.setX(sel.getMinX());
        selection.setY(sel.getMinY());
        selection.setWidth(sel.getWidth());
        selection.setHeight(sel.getHeight());
        selection.setVisible(true);
        drawDim(sel);
        updateBadge(sel);

        // Hide the hint once a selection exists.
        hint.setOpacity(0.0);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------
    // Completion
    // ------------------------------------------------------------------

    private void confirm() {
        if (current == null || current.getWidth() < MIN_SIZE || current.getHeight() < MIN_SIZE) {
            return;
        }
        // The overlay works in JavaFX logical coordinates, but FFmpeg captures in
        // physical pixels. Scale the selection and offset it by the display
        // origin so the result is an absolute physical-pixel rectangle.
        double sx = screen.scaleX() <= 0 ? 1 : screen.scaleX();
        double sy = screen.scaleY() <= 0 ? 1 : screen.scaleY();

        int x = screen.x() + (int) Math.round(current.getMinX() * sx);
        int y = screen.y() + (int) Math.round(current.getMinY() * sy);
        int w = even((int) Math.round(current.getWidth() * sx));
        int h = even((int) Math.round(current.getHeight() * sy));

        // Clamp to the physical display so we never request pixels that don't exist.
        w = Math.max((int) MIN_SIZE, Math.min(w, screen.width()));
        h = Math.max((int) MIN_SIZE, Math.min(h, screen.height()));
        x = Math.min(x, screen.x() + screen.width() - w);
        y = Math.min(y, screen.y() + screen.height() - h);

        Rectangle2D result = new Rectangle2D(x, y, w, h);
        Log.info("Region selected: " + w + "x" + h + " at " + x + "," + y);
        close(() -> {
            if (onSelected != null) {
                onSelected.accept(result);
            }
        });
    }

    /** Rounds down to the nearest even number (encoder requirement). */
    private static int even(int value) {
        return value % 2 == 0 ? value : value - 1;
    }

    private void cancel() {
        close(() -> {
            if (onCancelled != null) {
                onCancelled.run();
            }
        });
    }

    private void close(Runnable after) {
        if (stage == null) {
            after.run();
            return;
        }
        javafx.stage.Window toClose = stage;
        stage = null;
        Pane r = root;
        FadeTransition fade = new FadeTransition(Duration.millis(120), r);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            toClose.hide();
            after.run();
        });
        fade.play();
    }

    /** Programmatically closes the selector without invoking callbacks. */
    public void forceClose() {
        if (stage != null) {
            stage.hide();
            stage = null;
        }
    }
}

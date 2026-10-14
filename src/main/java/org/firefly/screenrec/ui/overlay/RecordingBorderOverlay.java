package org.firefly.screenrec.ui.overlay;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import java.util.ArrayList;
import java.util.List;

/** Four thin windows outside the capture bounds; no full-size mouse-blocking overlay. */
public final class RecordingBorderOverlay implements AutoCloseable {
    private final List<Stage> edges = new ArrayList<>();
    public static final double THICKNESS = 2;

    public static List<Rectangle2D> edgeBounds(Rectangle2D r) {
        double t = THICKNESS, x = r.getMinX(), y = r.getMinY(), w = r.getWidth(), h = r.getHeight();
        return List.of(new Rectangle2D(x-t,y-t,w+2*t,t),
                new Rectangle2D(x-t,y+h,w+2*t,t),
                new Rectangle2D(x-t,y,t,h), new Rectangle2D(x+w,y,t,h));
    }

    public static RecordingBorderOverlay show(Rectangle2D bounds, String accent) {
        RecordingBorderOverlay overlay = new RecordingBorderOverlay();
        Window owner = Window.getWindows().stream().filter(Window::isShowing).findFirst().orElse(null);
        Window focused = Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
        String color = accent != null && accent.matches("#[0-9a-fA-F]{6}") ? accent : "#ff4d5e";
        try {
            for (Rectangle2D b : edgeBounds(bounds)) {
                Pane root = new Pane();
                root.setStyle("-fx-background-color: " + color + ";");
                root.setMouseTransparent(true);
                Scene scene = new Scene(root, b.getWidth(), b.getHeight(), Color.TRANSPARENT);
                Stage stage = new Stage(StageStyle.TRANSPARENT);
                // Unowned: remains above other applications even when main UI is minimized.
                stage.setScene(scene);
                stage.setAlwaysOnTop(true);
                stage.setResizable(false);
                stage.setX(b.getMinX()); stage.setY(b.getMinY());
                stage.setWidth(b.getWidth()); stage.setHeight(b.getHeight());
                overlay.edges.add(stage);
                stage.show();
                stage.toFront();
                stage.setX(b.getMinX()); stage.setY(b.getMinY());
                stage.setWidth(b.getWidth()); stage.setHeight(b.getHeight());
            }
            if (focused != null) focused.requestFocus();
            return overlay;
        } catch (RuntimeException e) { overlay.close(); throw e; }
    }

    @Override public void close() {
        edges.forEach(Stage::close);
        edges.clear();
    }
}

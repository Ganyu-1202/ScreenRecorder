package org.firefly.screenrec.ui;

import javafx.animation.PauseTransition;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.firefly.screenrec.util.PlatformUtils;

/** Native decorations own all interactions; compensate for GTK's stale restore bounds only. */
final class NativeWindowState {
    private record Bounds(double x, double y, double width, double height) {
        static Bounds of(Stage stage) {
            return new Bounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        }
        boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && width > 0 && height > 0;
        }
    }

    private Bounds normal;
    private Bounds maximized;
    private boolean restoring;
    private final Stage stage;
    private final PauseTransition remember = new PauseTransition(Duration.millis(200));
    private final PauseTransition repair = new PauseTransition(Duration.millis(150));

    static void install(Stage stage) {
        if (PlatformUtils.isLinux()) new NativeWindowState(stage);
    }

    private NativeWindowState(Stage stage) {
        this.stage = stage;
        remember.setOnFinished(e -> {
            if (normalState() && !restoring) {
                Bounds bounds = Bounds.of(stage);
                if (bounds.valid()) normal = bounds;
            }
        });
        repair.setOnFinished(e -> repairStaleRestore());
        javafx.beans.InvalidationListener geometry = observable -> {
            if (stage.isMaximized()) maximized = Bounds.of(stage);
            else if (normalState() && !restoring) remember.playFromStart();
        };
        stage.xProperty().addListener(geometry);
        stage.yProperty().addListener(geometry);
        stage.widthProperty().addListener(geometry);
        stage.heightProperty().addListener(geometry);
        stage.showingProperty().addListener((o, old, showing) -> {
            if (showing) remember.playFromStart();
            else { remember.stop(); repair.stop(); }
        });
        stage.maximizedProperty().addListener((o, old, value) -> {
            remember.stop();
            repair.stop();
            if (value) {
                maximized = Bounds.of(stage);
            } else if (old && normal != null) {
                // Wait for WM/GTK configure events before deciding anything.
                restoring = true;
                repair.playFromStart();
            }
        });
    }

    private boolean normalState() {
        return stage.isShowing() && !stage.isMaximized() && !stage.isIconified() && !stage.isFullScreen();
    }

    private void repairStaleRestore() {
        if (normalState() && normal != null && maximized != null) {
            Bounds current = Bounds.of(stage);
            // Native restore/drag/snap already changed the dimensions: leave it alone.
            boolean stale = Math.abs(current.width - maximized.width) < 16
                    && Math.abs(current.height - maximized.height) < 40
                    && (normal.width < current.width - 40 || normal.height < current.height - 40);
            if (stale) {
                stage.setWidth(normal.width);
                stage.setHeight(normal.height);
                stage.setX(normal.x);
                stage.setY(normal.y);
            }
        }
        restoring = false;
        if (normalState()) remember.playFromStart();
    }
}

package org.firefly.screenrec;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.scene.input.MouseButton;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.firefly.screenrec.ui.MainWindow;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
class MainWindowInteractionTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException e) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void nativeWindowOwnsStateMoveAndResize() throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            Stage window = new MainWindow(null, null, null, null).stage();
            assertEquals(StageStyle.DECORATED, window.getStyle());
            window.setTitle("ScreenRecorder native window regression");
            window.setScene(new Scene(new StackPane(), 950, 620));
            window.setMinWidth(880); window.setMinHeight(600);
            window.setX(100); window.setY(100); window.show();
            var robot = new javafx.scene.robot.Robot();
            var original = robot.getMousePosition();
            double[] geometry = new double[4];
            var steps = new java.util.ArrayDeque<Runnable>();
            java.util.function.Consumer<Runnable> step = steps::add;
            step.accept(() -> {
                geometry[0] = window.getX(); geometry[1] = window.getY();
                geometry[2] = window.getWidth(); geometry[3] = window.getHeight();
                window.setMaximized(true);
            });
            step.accept(() -> {
                assertTrue(window.isMaximized());
                var screens = javafx.stage.Screen.getScreensForRectangle(window.getX(), window.getY(), window.getWidth(), window.getHeight());
                var area = screens.getFirst().getVisualBounds();
                // Windows keeps an invisible resize border outside the work area.
                assertTrue(window.getY() + window.getHeight() <= area.getMaxY() + 12, "Maximize must not cover taskbar/dock");
                window.setIconified(true);
            });
            step.accept(() -> { assertTrue(window.isIconified()); window.setIconified(false); });
            step.accept(() -> {
                assertTrue(window.isMaximized(), "Minimize must preserve maximize state");
                window.setMaximized(false);
            });
            step.accept(() -> {
                assertEquals(geometry[0], window.getX(), 3); assertEquals(geometry[1], window.getY(), 3);
                assertEquals(geometry[2], window.getWidth(), 3); assertEquals(geometry[3], window.getHeight(), 3);
                robot.mouseMove(window.getX() + 150, window.getY() + 18);
            });
            step.accept(() -> robot.mousePress(MouseButton.PRIMARY));
            step.accept(() -> robot.mouseMove(geometry[0] + 160, geometry[1] + 28));
            step.accept(() -> robot.mouseMove(geometry[0] + 230, geometry[1] + 78));
            step.accept(() -> robot.mouseRelease(MouseButton.PRIMARY));
            step.accept(() -> {
                assertEquals(geometry[0] + 80, window.getX(), 4); assertEquals(geometry[1] + 60, window.getY(), 4);
                geometry[2] = window.getWidth(); geometry[3] = window.getX() + window.getWidth() - 2;
                robot.mouseMove(geometry[3], window.getY() + 200);
            });
            step.accept(() -> robot.mousePress(MouseButton.PRIMARY));
            step.accept(() -> robot.mouseMove(geometry[3] + 10, window.getY() + 200));
            step.accept(() -> robot.mouseMove(geometry[3] + 80, window.getY() + 200));
            step.accept(() -> robot.mouseRelease(MouseButton.PRIMARY));
            step.accept(() -> {
                assertEquals(geometry[2] + 80, window.getWidth(), 4);
                window.hide(); robot.mouseMove(original); done.complete(null);
            });
            runNext(steps, done, window, robot, original);

        });
        done.get(30, TimeUnit.SECONDS);
    }
    private static void runNext(java.util.ArrayDeque<Runnable> steps, CompletableFuture<Void> done,
                                Stage window, javafx.scene.robot.Robot robot, javafx.geometry.Point2D original) {
        if (done.isDone() || steps.isEmpty()) return;
        var pause = new PauseTransition(Duration.millis(600));
        pause.setOnFinished(e -> {
            try { steps.remove().run(); runNext(steps, done, window, robot, original); }
            catch (Throwable t) { robot.mouseRelease(MouseButton.PRIMARY); window.hide(); robot.mouseMove(original); done.completeExceptionally(t); }
        });
        pause.play();
    }
}

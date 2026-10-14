package org.firefly.screenrec;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import org.firefly.screenrec.core.screen.MonitorService;
import org.firefly.screenrec.ui.overlay.RegionSelector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RegionSelectorTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException e) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }
    @Test void eachMonitorHasItsOwnSelectableOverlay() throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            var owner = new javafx.stage.Stage();
            owner.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 100, 100));
            owner.show();
            var screens = MonitorService.monitors();
            var selectors = new ArrayList<RegionSelector>();
            for (var screen : screens) selectors.add(RegionSelector.open(screen, null, r -> {}, () -> {}));
            var wait = new PauseTransition(Duration.millis(700));
            wait.setOnFinished(e -> {
                try {
                    var field = RegionSelector.class.getDeclaredField("stage"); field.setAccessible(true);
                    for (int i = 0; i < screens.size(); i++) {
                        var stage = (javafx.stage.Window) field.get(selectors.get(i));
                        var b = screens.get(i).visualBounds();
                        System.out.println("Selector " + screens.get(i).id() + " expected=" + b + " actual=" + stage.getX()+","+stage.getY()+","+stage.getWidth()+","+stage.getHeight());
                        assertEquals(b.getMinX(), stage.getX(), 2);
                        assertEquals(b.getMinY(), stage.getY(), 2);
                        assertEquals(b.getWidth(), stage.getWidth(), 2);
                        assertEquals(b.getHeight(), stage.getHeight(), 2);
                    }
                    result.complete(null);
                } catch (Throwable t) { result.completeExceptionally(t); }
                finally { selectors.forEach(RegionSelector::forceClose); owner.close(); }
            }); wait.play();
        }); result.get(15, TimeUnit.SECONDS);
    }
    @Test void mouseCanSelectAndConfirmOnEveryMonitor() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.firefly.screenrec.util.PlatformUtils.isLinux());
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            var owner = new javafx.stage.Stage();
            owner.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 100, 100));
            owner.show();
            var screens = MonitorService.monitors();
            var selectors = new ArrayList<RegionSelector>();
            var robot = new javafx.scene.robot.Robot();
            var original = robot.getMousePosition();
            var selected = new java.util.HashMap<Integer, javafx.geometry.Rectangle2D>();
            for (int i = 0; i < screens.size(); i++) {
                int index = i;
                selectors.add(RegionSelector.open(screens.get(i), null, r -> selected.put(index, r), () -> {}));
            }
            var sequence = new javafx.animation.SequentialTransition();
            for (int i = 0; i < screens.size(); i++) {
                var screen = screens.get(i);
                var b = screen.visualBounds();
                sequence.getChildren().add(pause(() -> robot.mouseMove(b.getMinX() + 100, b.getMinY() + 100)));
                sequence.getChildren().add(pause(() -> robot.mousePress(javafx.scene.input.MouseButton.PRIMARY)));
                sequence.getChildren().add(pause(() -> robot.mouseMove(b.getMinX() + 300, b.getMinY() + 250)));
                sequence.getChildren().add(pause(() -> robot.mouseRelease(javafx.scene.input.MouseButton.PRIMARY)));
                sequence.getChildren().add(pause(() -> { robot.keyPress(javafx.scene.input.KeyCode.ENTER); robot.keyRelease(javafx.scene.input.KeyCode.ENTER); }));
            }
            sequence.getChildren().add(pause(() -> {
                try {
                    assertEquals(screens.size(), selected.size(), "Every monitor must accept mouse selection and Enter");
                    for (int i = 0; i < screens.size(); i++) {
                        var screen = screens.get(i); var region = selected.get(i);
                        assertEquals(screen.x() + Math.round(100 * screen.scaleX()), region.getMinX(), 2);
                        assertEquals(screen.y() + Math.round(100 * screen.scaleY()), region.getMinY(), 2);
                        assertEquals(Math.round(200 * screen.scaleX()), region.getWidth(), 2);
                        assertEquals(Math.round(150 * screen.scaleY()), region.getHeight(), 2);
                    }
                    result.complete(null);
                } catch (Throwable t) { result.completeExceptionally(t); }
                finally { selectors.forEach(RegionSelector::forceClose); owner.close(); robot.mouseMove(original); }
            }));
            sequence.play();
        });
        result.get(20, TimeUnit.SECONDS);
    }
    private static PauseTransition pause(Runnable action) {
        var pause = new PauseTransition(Duration.millis(300));
        pause.setOnFinished(e -> action.run()); return pause;
    }

}

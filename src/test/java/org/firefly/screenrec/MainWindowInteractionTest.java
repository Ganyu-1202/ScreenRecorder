package org.firefly.screenrec;

import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.util.Duration;
import javafx.scene.input.MouseButton;
import org.firefly.screenrec.ui.MainWindow;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MainWindowInteractionTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException e) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }
    private static Object call(Object o, String name) throws Exception {
        var method = o.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(o);
    }
    private static Object field(Object o, String name) throws Exception {
        var field = o.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(o);
    }
    @Test void restoredWindowCanMoveAndResizeWithMouse() throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            javafx.stage.Stage stage = null;
            try {
                var main = new MainWindow(null, null, null, null);
                stage = main.stage();
                var root = (javafx.scene.layout.StackPane) field(main, "rootStack");
                var shell = (javafx.scene.layout.BorderPane) field(main, "shell");
                shell.setTop((javafx.scene.Node) call(main, "buildTitleBar")); root.getChildren().add(shell);
                var scene = new javafx.scene.Scene(root, 1200, 700);
                scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                org.firefly.screenrec.ui.overlay.OverlayWindow.applyStylesheets(scene);
                stage.setScene(scene); stage.setMinWidth(880); stage.setMinHeight(600); stage.setX(200); stage.setY(150); stage.show();
                call(main, "installResizeHandles");
                var window = stage;
                var robot = new javafx.scene.robot.Robot(); var original = robot.getMousePosition();
                double[] geometry = new double[4];
                var sequence = new SequentialTransition();
                sequence.getChildren().add(pause(() -> {
                    geometry[0] = window.getX(); geometry[1] = window.getY();
                    robot.mouseMove(window.getX()+70,window.getY()+24);
                }));
                sequence.getChildren().add(pause(() -> robot.mousePress(MouseButton.PRIMARY)));
                sequence.getChildren().add(pause(() -> robot.mouseMove(geometry[0]+150,geometry[1]+84)));
                sequence.getChildren().add(pause(() -> robot.mouseRelease(MouseButton.PRIMARY)));
                sequence.getChildren().add(pause(() -> {
                    try {
                        System.out.println("Moved " + geometry[0]+","+geometry[1]+" -> "+window.getX()+","+window.getY());
                        assertEquals(geometry[0]+80,window.getX(),3); assertEquals(geometry[1]+60,window.getY(),3);
                        geometry[2]=window.getWidth(); geometry[3]=window.getX()+window.getWidth()-3;
                        robot.mouseMove(geometry[3],window.getY()+200);
                    } catch(Throwable t) { done.completeExceptionally(t); }
                }));
                sequence.getChildren().add(pause(() -> robot.mousePress(MouseButton.PRIMARY)));
                sequence.getChildren().add(pause(() -> robot.mouseMove(geometry[3]+80,window.getY()+200)));
                sequence.getChildren().add(pause(() -> robot.mouseRelease(MouseButton.PRIMARY)));
                sequence.getChildren().add(pause(() -> {
                    try { System.out.println("Resized "+geometry[2]+" -> "+window.getWidth()); assertEquals(geometry[2]+80,window.getWidth(),3); done.complete(null); }
                    catch(Throwable t) { done.completeExceptionally(t); }
                    finally { window.hide(); robot.mouseMove(original); }
                })); sequence.play();
            } catch(Throwable t) { if(stage != null) stage.hide(); done.completeExceptionally(t); }
        }); done.get(15,TimeUnit.SECONDS);
    }
    private static PauseTransition pause(Runnable action) {
        var p=new PauseTransition(Duration.millis(300)); p.setOnFinished(e -> action.run()); return p;
    }
}

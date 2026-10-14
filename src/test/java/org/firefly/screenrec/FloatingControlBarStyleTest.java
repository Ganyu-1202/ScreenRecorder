package org.firefly.screenrec;

import org.firefly.screenrec.ui.overlay.FloatingControlBar;
import org.firefly.screenrec.ui.overlay.OverlayWindow;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FloatingControlBarStyleTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException alreadyStarted) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void capsuleHasTransparentCornersAndBorderlessButtonStates() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            var constructor = FloatingControlBar.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object bar = constructor.newInstance(null, null, null);
            var build = FloatingControlBar.class.getDeclaredMethod("buildRoot");
            build.setAccessible(true);
            HBox capsule = (HBox) build.invoke(bar);
            capsule.getStyleClass().add("control-bar");
            capsule.setOpacity(0.5);
            StackPane wrapper = new StackPane(capsule);
            wrapper.setPadding(new Insets(2));
            wrapper.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
            Scene scene = new Scene(wrapper);
            scene.setFill(Color.TRANSPARENT);
            OverlayWindow.applyStylesheets(scene);
            wrapper.applyCss();
            wrapper.resize(wrapper.prefWidth(-1), wrapper.prefHeight(-1));
            wrapper.layout();
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            WritableImage image = wrapper.snapshot(parameters, null);
            var pixels = image.getPixelReader();
            assertEquals(0, pixels.getColor(0, 0).getOpacity(), 0.001);
            assertEquals(0, pixels.getColor((int) image.getWidth() - 1, (int) image.getHeight() - 1).getOpacity(), 0.001);
            assertEquals(0, pixels.getColor(3, 3).getOpacity(), 0.001, "rounded capsule corner must be transparent");
            assertTrue(image.getWidth() < 350, "bar should remain compact");
            assertTrue(image.getHeight() < 60, "no large shadow margin");
            assertNull(capsule.getEffect());
            double maxAlpha = 0;
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++)
                    maxAlpha = Math.max(maxAlpha, pixels.getColor(x, y).getOpacity());
            assertEquals(0.5, maxAlpha, 0.01, "entire painted capsule is half transparent");
            Button stop = (Button) capsule.lookup(".control-bar-stop");
            assertNotNull(stop);
            assertTrue(stop.getText() == null || stop.getText().isEmpty());
            assertNotNull(stop.getAccessibleText());
            assertNotNull(stop.getGraphic());
            for (String state : new String[]{"hover", "pressed", "focused"}) {
                PseudoClass pseudo = PseudoClass.getPseudoClass(state);
                stop.pseudoClassStateChanged(pseudo, true);
                wrapper.applyCss();
                stop.getBorder().getStrokes().forEach(stroke -> {
                    assertEquals(Color.TRANSPARENT, stroke.getTopStroke(), state);
                    assertEquals(Color.TRANSPARENT, stroke.getBottomStroke(), state);
                });
                stop.pseudoClassStateChanged(pseudo, false);
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void checkboxSelectionDoesNotChangeLayoutSize() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            for (String theme : new String[]{"dark", "light"}) {
                javafx.scene.control.CheckBox checkbox = new javafx.scene.control.CheckBox("录制时显示悬浮栏");
                StackPane root = new StackPane(checkbox);
                Scene scene = new Scene(root);
                OverlayWindow.applyStylesheets(scene, theme);
                root.applyCss();
                double width = checkbox.prefWidth(-1);
                double height = checkbox.prefHeight(-1);
                javafx.scene.layout.Region box = (javafx.scene.layout.Region) checkbox.lookup(".box");
                javafx.scene.layout.Region mark = (javafx.scene.layout.Region) checkbox.lookup(".mark");
                double boxWidth = box.prefWidth(-1);
                double markWidth = mark.prefWidth(-1);
                for (int i = 0; i < 4; i++) {
                    checkbox.setSelected(i % 2 == 0);
                    root.applyCss();
                    assertEquals(width, checkbox.prefWidth(-1), 0.01, theme);
                    assertEquals(height, checkbox.prefHeight(-1), 0.01, theme);
                    assertEquals(boxWidth, box.prefWidth(-1), 0.01, theme);
                    assertEquals(markWidth, mark.prefWidth(-1), 0.01, theme);
                }
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void confirmationDialogUsesConsistentTheme() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            for (String theme : new String[]{"dark", "light"}) {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.setHeaderText("确定要删除录制文件吗？");
                alert.setContentText("这将从磁盘中永久删除该文件，且无法恢复。");
                alert.setGraphic(null);
                var pane = alert.getDialogPane();
                OverlayWindow.applyStylesheets(pane.getScene(), theme);
                pane.applyCss();
                pane.resize(pane.prefWidth(-1), pane.prefHeight(-1));
                pane.layout();
                var header = (javafx.scene.layout.Region) pane.lookup(".header-panel");
                var headerLabel = (javafx.scene.control.Label) header.lookup(".label");
                var contentLabel = (javafx.scene.control.Label) pane.lookup(".content");
                Color headerBackground = (Color) header.getBackground().getFills().get(0).getFill();
                Color headerText = (Color) headerLabel.getTextFill();
                Color contentText = (Color) contentLabel.getTextFill();
                if (theme.equals("dark")) {
                    assertTrue(headerBackground.getBrightness() < 0.2);
                    assertTrue(headerText.getBrightness() > 0.8);
                    assertTrue(contentText.getBrightness() > 0.5);
                } else {
                    assertTrue(headerBackground.getBrightness() > 0.9);
                    assertTrue(headerText.getBrightness() < 0.2);
                    assertTrue(contentText.getBrightness() < 0.5);
                }
                assertNull(alert.getGraphic());
                alert.close();
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void customAccentAndSvgAssetsRender() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                org.firefly.screenrec.ui.theme.ThemeManager.setAccentColor("#3b82f6");
                javafx.scene.layout.Region swatch = new javafx.scene.layout.Region();
                swatch.setStyle("-fx-background-color: -accent;");
                StackPane root = new StackPane(swatch);
                Scene scene = new Scene(root);
                OverlayWindow.applyStylesheets(scene, "dark");
                root.applyCss();
                assertEquals(Color.web("#3b82f6"), swatch.getBackground().getFills().get(0).getFill());
                var image = org.firefly.screenrec.ui.theme.AppIcons.applicationImage(32);
                assertEquals(32, image.getWidth());
                assertEquals(0, image.getPixelReader().getColor(0, 0).getOpacity(), 0.01);
                int painted = 0;
                for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
                    if (image.getPixelReader().getColor(x, y).getOpacity() > 0.5) painted++;
                assertTrue(painted > 100, "SVG icon must contain visible camera/play paths");
                var navIcon = org.firefly.screenrec.ui.theme.AppIcons.vector("screen_record", 19, true);
                navIcon.resize(19, 19);
                navIcon.layout();
                var bounds = navIcon.getChildrenUnmodifiable().get(0).getBoundsInParent();
                assertTrue(bounds.getMinX() >= -0.1 && bounds.getMaxX() <= 19.1, bounds.toString());
                assertTrue(bounds.getMinY() >= -0.1 && bounds.getMaxY() <= 19.1, bounds.toString());
                for (String theme : new String[]{"dark", "light"}) {
                    var button = new javafx.scene.control.Button("Start");
                    button.getStyleClass().add("button-primary");
                    var nav = new javafx.scene.layout.HBox();
                    nav.getStyleClass().addAll("nav-item", "nav-item-active");
                    StackPane themedRoot = new StackPane(button, nav);
                    Scene themedScene = new Scene(themedRoot);
                    OverlayWindow.applyStylesheets(themedScene, theme);
                    themedRoot.applyCss();
                    Color navColor = (Color) nav.getBackground().getFills().get(0).getFill();
                    assertEquals(Color.web("#3b82f6").getBlue(), navColor.getBlue(), 0.01);
                    assertEquals(0.13, navColor.getOpacity(), 0.01);
                    var shadow = (javafx.scene.effect.DropShadow) button.getEffect();
                    assertEquals(Color.web("#3b82f6").getBlue(), shadow.getColor().getBlue(), 0.01);
                    assertEquals(Color.web("#3b82f6").getRed(), shadow.getColor().getRed(), 0.01);
                }
            } finally {
                org.firefly.screenrec.ui.theme.ThemeManager.setAccentColor("#ff4d5e");
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void toggleThumbIsVisibleInBothThemesAndStates() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            for (String theme : new String[]{"dark", "light"}) {
                var toggle = new org.firefly.screenrec.ui.components.ToggleSwitch(false);
                StackPane root = new StackPane(toggle);
                Scene scene = new Scene(root);
                OverlayWindow.applyStylesheets(scene, theme);
                for (boolean selected : new boolean[]{false, true, false}) {
                    toggle.setSelected(selected);
                    root.applyCss();
                    var thumb = (javafx.scene.shape.Circle) toggle.lookup(".toggle-thumb");
                    assertEquals(theme.equals("dark") ? Color.BLACK : Color.WHITE,
                            thumb.getFill(), theme + " selected=" + selected);
                    assertNotNull(thumb.getEffect());
                }
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void tooltipInstallationReusesMouseTransparentLayer() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            StackPane root = new StackPane();
            var layer = new org.firefly.screenrec.ui.components.TooltipLayer(root);
            var button = new javafx.scene.control.Button("Hover");
            org.firefly.screenrec.ui.components.UiFactory.installTooltip(button, "First hint");
            root.getChildren().add(button);
            Scene scene = new Scene(root);
            assertSame(layer, org.firefly.screenrec.ui.components.TooltipLayer.forScene(scene));
            org.firefly.screenrec.ui.components.UiFactory.installTooltip(button, "Updated hint");
            assertNull(button.getTooltip());
            var bubbles = root.getChildren().stream()
                    .filter(n -> n.getStyleClass().contains("tip-bubble")).toList();
            assertEquals(1, bubbles.size());
            assertTrue(bubbles.get(0).isMouseTransparent());
            assertFalse(bubbles.get(0).isManaged());
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void windowButtonsAndRgbSlidersHaveDistinctStyles() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            for (String theme : new String[]{"dark", "light"}) {
                var button = new javafx.scene.control.Button("Close");
                button.getStyleClass().addAll("window-control", "window-control-close");
                String[] channels = {"r", "g", "b"};
                String[] colors = {"#ef4444", "#22c55e", "#3b82f6"};
                var titleBar = new javafx.scene.layout.HBox(button);
                titleBar.getStyleClass().add("title-bar");
                var container = new javafx.scene.layout.VBox(titleBar);
                for (String channel : channels) {
                    var slider = new javafx.scene.control.Slider();
                    slider.getStyleClass().add("rgb-" + channel);
                    container.getChildren().add(slider);
                }
                Scene scene = new Scene(new StackPane(container));
                OverlayWindow.applyStylesheets(scene, theme);
                scene.getRoot().applyCss();
                assertEquals(10, titleBar.getPadding().getRight(), 0.01);
                assertEquals(5, titleBar.getPadding().getTop(), 0.01);
                assertEquals(5, titleBar.getPadding().getBottom(), 0.01);
                assertEquals(8, button.getBackground().getFills().get(0).getRadii().getTopLeftHorizontalRadius(), 0.01);
                for (int i = 0; i < 3; i++) {
                    var slider = container.getChildren().get(i + 1);
                    var thumb = (javafx.scene.layout.Region) slider.lookup(".thumb");
                    assertEquals(Color.web(colors[i]), thumb.getBackground().getFills().get(0).getFill());
                }
            }
            return null;
        });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void linuxFloatingBarRemainsVisibleWhenExclusionIsRequested() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.firefly.screenrec.util.PlatformUtils.isLinux());
        FutureTask<Void> task = new FutureTask<>(() -> {
            var profile = org.firefly.screenrec.core.recorder.RecordingProfile.builder()
                    .source(org.firefly.screenrec.core.recorder.CaptureSource.region(0, 0, 640, 360))
                    .outputFile(java.nio.file.Path.of("unused.mp4")).build();
            var session = new org.firefly.screenrec.core.recorder.RecordingSession(profile, java.nio.file.Path.of("ffmpeg"), null);
            var bar = FloatingControlBar.show(session, null, null, null, null, 0.5, true);
            try {
                assertFalse(FloatingControlBar.requiresCaptureExclusion(true));
                assertTrue(bar.isShowing(), "Windows-only exclusion must not hide Linux controls");
                var window = javafx.stage.Window.getWindows().stream().filter(w -> w.isShowing()
                        && w.getScene().getRoot().lookup(".control-bar") != null).findFirst().orElseThrow();
                assertTrue(window.getWidth() > 150 && window.getWidth() < 350);
                assertTrue(window.getHeight() > 25 && window.getHeight() < 70);
                var visual = javafx.stage.Screen.getScreensForRectangle(window.getX(), window.getY(), 1, 1).get(0).getVisualBounds();
                assertTrue(window.getX() >= visual.getMinX());
                assertTrue(window.getY() >= visual.getMinY());
                assertTrue(window.getX() + window.getWidth() <= visual.getMaxX() + 1);
            } finally { bar.close(); }
            return null;
        });
        Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void countdownNativeWindowUsesTargetCenter() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.firefly.screenrec.util.PlatformUtils.isLinux());
        var ready = new java.util.concurrent.CompletableFuture<Void>();
        Platform.runLater(() -> {
            var screens = javafx.stage.Screen.getScreens();
            var target = screens.get(screens.size() - 1).getBounds();
            var expected = org.firefly.screenrec.ui.overlay.CountdownOverlay.windowBounds(target, true);
            org.firefly.screenrec.ui.overlay.CountdownOverlay.show(3, target, () -> {}, () -> {});
            var wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            wait.setOnFinished(e -> {
                javafx.stage.Stage countdown = null;
                try {
                    countdown = (javafx.stage.Stage) javafx.stage.Window.getWindows().stream()
                            .filter(w -> w instanceof javafx.stage.Stage stage && "ScreenRecorder Countdown".equals(stage.getTitle()))
                            .findFirst().orElseThrow();
                    assertEquals(expected.getMinX(), countdown.getX(), 3);
                    assertEquals(expected.getMinY(), countdown.getY(), 3);
                    assertEquals(expected.getWidth(), countdown.getWidth(), 3);
                    assertEquals(expected.getHeight(), countdown.getHeight(), 3);
                    ready.complete(null);
                } catch (Throwable error) { ready.completeExceptionally(error); }
                finally { if (countdown != null) countdown.fireEvent(new javafx.stage.WindowEvent(countdown, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)); }
            });
            wait.play();
        });
        ready.get(15, TimeUnit.SECONDS);
    }

    @Test
    void countdownCardIsCenteredOnSelectedTargetNotPrimaryScreen() {
        var target = new javafx.geometry.Rectangle2D(3408, 100, 3840, 2160);
        var card = org.firefly.screenrec.ui.overlay.CountdownOverlay.windowBounds(target, true);
        assertEquals(440, card.getWidth()); assertEquals(320, card.getHeight());
        assertEquals(target.getMinX() + target.getWidth()/2, card.getMinX() + card.getWidth()/2);
        assertEquals(target.getMinY() + target.getHeight()/2, card.getMinY() + card.getHeight()/2);
        assertEquals(target, org.firefly.screenrec.ui.overlay.CountdownOverlay.windowBounds(target, false));
        var small = org.firefly.screenrec.ui.overlay.CountdownOverlay.windowBounds(
                new javafx.geometry.Rectangle2D(-800, 200, 200, 150), true);
        assertEquals(-800, small.getMinX()); assertEquals(200, small.getMinY());
    }

    @Test
    void timerFormatHandlesLongRecordings() {
        assertEquals("00:00", FloatingControlBar.formatDuration(-1));
        assertEquals("01:05", FloatingControlBar.formatDuration(65_000));
        assertEquals("1:01:05", FloatingControlBar.formatDuration(3_665_000));
    }
}

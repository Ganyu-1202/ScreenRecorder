package org.firefly.screenrec.ui.tray;

import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.firefly.screenrec.core.i18n.Lang;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.SystemFont;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsTrayMenuTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException e) { Platform.runLater(ready::countDown); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }
    private static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    @Test void chineseMenuUsesApplicationFontThemeAndState() throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            Lang old = I18n.language();
            var clicks = new AtomicInteger();
            var tray = new WindowsTrayMenu(clicks::incrementAndGet, () -> {}, () -> {}, () -> {}, () -> {});
            try {
                I18n.setLanguage("zh-CN");
                tray.show(new Point2D(500, 400), "dark", false);
                ContextMenu menu = (ContextMenu) field(tray, "menu");
                Stage owner = (Stage) field(tray, "owner");
                menu.getScene().getRoot().applyCss();
                assertTrue(menu.isShowing());
                assertEquals("打开 ScreenRecorder", menu.getItems().get(0).getText());
                assertFalse(menu.getItems().get(2).isDisable());
                assertTrue(menu.getItems().get(3).isDisable());
                assertTrue(menu.getScene().getStylesheets().stream().anyMatch(s -> s.endsWith("dark.css")));
                Label label = (Label) menu.getScene().getRoot().lookup(".menu-item .label");
                assertNotNull(label);
                assertEquals(SystemFont.family(), label.getFont().getFamily());
                assertTrue(new java.awt.Font(SystemFont.family(), java.awt.Font.PLAIN, 13).canDisplayUpTo("打开录制显示器停止录制文件夹退出") < 0);
                tray.refresh(true);
                assertTrue(menu.getItems().get(2).isDisable());
                assertFalse(menu.getItems().get(3).isDisable());
                menu.getItems().get(0).fire();
                assertEquals(1, clicks.get());
                tray.show(new Point2D(500, 400), "light", true);
                assertTrue(menu.getScene().getStylesheets().stream().anyMatch(s -> s.endsWith("light.css")));
                tray.hide();
                assertFalse(menu.isShowing());
                assertFalse(owner.isShowing());
                done.complete(null);
            } catch (Throwable t) { done.completeExceptionally(t); }
            finally { tray.dispose(); I18n.setLanguage(old.id()); }
        });
        done.get(15, TimeUnit.SECONDS);
    }
}

package org.firefly.screenrec.ui.tray;

import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.ThemeManager;

/** FX-thread only. An invisible tool owner keeps the menu independent of a minimized main window. */
final class WindowsTrayMenu {
    private final Stage owner = new Stage(StageStyle.UTILITY);
    private final ContextMenu menu = new ContextMenu();
    private final MenuItem open = new MenuItem();
    private final MenuItem record = new MenuItem();
    private final MenuItem stop = new MenuItem();
    private final MenuItem folder = new MenuItem();
    private final MenuItem quit = new MenuItem();

    WindowsTrayMenu(Runnable onOpen, Runnable onRecord, Runnable onStop,
                    Runnable onFolder, Runnable onQuit) {
        owner.setTitle("ScreenRecorder tray menu");
        owner.setOpacity(0);
        owner.setResizable(false);
        owner.setScene(new Scene(new StackPane(), 1, 1));
        owner.setWidth(1);
        owner.setHeight(1);
        menu.getItems().setAll(open, new SeparatorMenuItem(), record, stop,
                new SeparatorMenuItem(), folder, new SeparatorMenuItem(), quit);
        open.setOnAction(e -> onOpen.run());
        record.setOnAction(e -> onRecord.run());
        stop.setOnAction(e -> onStop.run());
        folder.setOnAction(e -> onFolder.run());
        quit.setOnAction(e -> onQuit.run());
        menu.setAutoHide(true);
        menu.setHideOnEscape(true);
        menu.setAutoFix(true);
        menu.setOnHidden(e -> javafx.application.Platform.runLater(() -> {
            if (!menu.isShowing()) owner.hide();
        }));
    }

    void refresh(boolean recording) {
        open.setText(I18n.t("tray.open"));
        record.setText(I18n.t("tray.record"));
        stop.setText(I18n.t("tray.stop"));
        folder.setText(I18n.t("tray.folder"));
        quit.setText(I18n.t("tray.quit"));
        record.setDisable(recording);
        stop.setDisable(!recording);
    }

    void show(Point2D point, String theme, boolean recording) {
        hide();
        refresh(recording);
        ThemeManager themes = new ThemeManager(theme);
        themes.applyCurrent(owner.getScene());
        // Popup scenes do not reliably inherit the application's font rules.
        // Apply the same full theme, including SystemFont's CJK font, explicitly.
        themes.applyCurrent(menu.getScene());
        owner.setX(point.getX());
        owner.setY(point.getY());
        owner.show();
        owner.requestFocus();
        menu.show(owner, point.getX(), point.getY());
        menu.requestFocus();
    }

    void hide() {
        menu.hide();
        owner.hide();
    }

    void dispose() {
        hide();
        owner.close();
    }
}

package org.firefly.screenrec.ui.views;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.core.media.MediaInfo;
import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.Icons;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Browse, preview, play and manage past recordings.
 *
 * <p>Thumbnails are generated with FFmpeg on a background thread and cached in
 * memory, so scrolling stays smooth even with a large library.
 */
public final class LibraryView extends ScrollPane {

    private static final double CARD_WIDTH = 268;
    private static final double THUMB_HEIGHT = 150;

    private final AppContext context;
    private final Consumer<String> onNotify;

    private final FlowPane grid = new FlowPane(18, 18);
    private final VBox emptyState = new VBox(12);
    private final TextField searchField = new TextField();
    private final Label countLabel = UiFactory.caption("");

    private final List<MediaInfo> allItems = new ArrayList<>();
    private String filterText = "";

    public LibraryView(AppContext context, Consumer<String> onNotify) {
        this.context = context;
        this.onNotify = onNotify;

        getStyleClass().add("app-content");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        VBox root = new VBox(20);
        root.setPadding(new Insets(28, 32, 32, 32));
        root.getChildren().addAll(buildHeader(), buildGridArea());

        setContent(root);
        refresh();
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    private Node buildHeader() {
        VBox header = new VBox(14);

        HBox titleRow = new HBox(12);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label title = UiFactory.title(I18n.t("library.title"));
        countLabel.setOpacity(0.9);

        Button refreshButton = UiFactory.iconButton(Icons.REFRESH, I18n.t("library.refresh"));
        refreshButton.getStyleClass().add("button-subtle");
        refreshButton.setOnAction(e -> refresh());

        Button folderButton = UiFactory.button(I18n.t("library.openFolder"), Icons.FOLDER,
                "button-subtle", "button-sm");
        folderButton.setOnAction(e -> openOutputFolder());

        titleRow.getChildren().addAll(title, countLabel, UiFactory.hSpacer(),
                refreshButton, folderButton);

        HBox controls = new HBox(12);
        controls.setAlignment(Pos.CENTER_LEFT);

        searchField.setPromptText(I18n.t("library.search"));
        searchField.setPrefWidth(300);
        searchField.textProperty().addListener((obs, old, val) -> {
            filterText = val == null ? "" : val.trim().toLowerCase(Locale.ROOT);
            renderGrid();
        });

        controls.getChildren().addAll(searchField, UiFactory.hSpacer());

        header.getChildren().addAll(titleRow, controls);
        return header;
    }

    private Node buildGridArea() {
        StackPane area = new StackPane();
        grid.setAlignment(Pos.TOP_LEFT);

        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(60, 0, 60, 0));
        Region icon = UiFactory.iconBox(Icons.LIBRARY, 54);
        icon.getStyleClass().add("empty-state-icon");
        Label emptyTitle = new Label(I18n.t("library.empty"));
        emptyTitle.getStyleClass().add("empty-state-title");
        Label emptyText = new Label(I18n.t("library.empty.hint"));
        emptyText.getStyleClass().add("empty-state-text");
        emptyState.getChildren().addAll(icon, emptyTitle, emptyText);

        area.getChildren().addAll(grid, emptyState);
        return area;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Rescans the output directory and rebuilds the grid. */
    public void refresh() {
        FxScheduler.background(() -> {
            List<MediaInfo> items = new ArrayList<>(context.listRecordings());
            items.sort(Comparator.comparing(MediaInfo::lastModified,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            FxScheduler.run(() -> {
                allItems.clear();
                allItems.addAll(items);
                renderGrid();
            });
        });
    }

    private void renderGrid() {
        List<MediaInfo> visible = allItems.stream()
                .filter(this::matchesFilter)
                .toList();

        grid.getChildren().clear();
        for (MediaInfo item : visible) {
            grid.getChildren().add(buildCard(item));
        }

        boolean empty = visible.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        grid.setVisible(!empty);
        grid.setManaged(!empty);

        countLabel.setText(allItems.isEmpty() ? ""
                : visible.size() == allItems.size()
                        ? I18n.t("library.count", allItems.size())
                        : I18n.t("library.countFiltered", visible.size(), allItems.size()));

        if (!empty) {
            fadeIn(grid);
        }
    }

    private boolean matchesFilter(MediaInfo info) {
        if (filterText.isBlank()) {
            return true;
        }
        String name = info.fileName() == null ? "" : info.fileName().toLowerCase(Locale.ROOT);
        return name.contains(filterText);
    }

    private void fadeIn(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(220), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    private Node buildCard(MediaInfo info) {
        VBox card = new VBox();
        card.getStyleClass().add("clip-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxWidth(CARD_WIDTH);

        StackPane thumbHolder = new StackPane();
        thumbHolder.getStyleClass().add("clip-thumb");
        thumbHolder.setMinHeight(THUMB_HEIGHT);
        thumbHolder.setPrefHeight(THUMB_HEIGHT);
        thumbHolder.setMaxHeight(THUMB_HEIGHT);

        Label placeholder = UiFactory.caption("…");
        thumbHolder.getChildren().add(placeholder);

        // Duration badge in the corner.
        if (info.durationMillis() > 0) {
            Label duration = new Label(formatDuration(info.durationMillis()));
            duration.getStyleClass().add("clip-duration");
            StackPane.setAlignment(duration, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(duration, new Insets(8));
            thumbHolder.getChildren().add(duration);
        }

        // Generate the thumbnail lazily, off the FX thread.
        loadThumbnail(info, thumbHolder, placeholder);

        VBox body = new VBox(5);
        body.setPadding(new Insets(12, 14, 14, 14));

        Label name = new Label(info.fileName());
        name.getStyleClass().add("clip-title");
        name.setWrapText(false);
        name.setMaxWidth(CARD_WIDTH - 28);
        UiFactory.installTooltip(name, info.fileName());

        Label meta = new Label(buildMeta(info));
        meta.getStyleClass().add("clip-meta");

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(8, 0, 0, 0));

        Button play = UiFactory.iconButton(Icons.PLAY, I18n.t("library.play"));
        play.setOnAction(e -> play(info.path()));

        Button openFolder = UiFactory.iconButton(Icons.FOLDER, I18n.t("library.reveal"));
        openFolder.setOnAction(e -> revealInFolder(info.path()));

        Button copyPath = UiFactory.iconButton(Icons.COPY, I18n.t("library.copyPath"));
        copyPath.setOnAction(e -> copyPath(info.path()));

        Button delete = UiFactory.iconButton(Icons.TRASH, I18n.t("library.delete"));
        delete.setOnAction(e -> confirmDelete(info));

        actions.getChildren().addAll(play, openFolder, copyPath, UiFactory.hSpacer(), delete);

        body.getChildren().addAll(name, meta, actions);
        card.getChildren().addAll(thumbHolder, body);

        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                play(info.path());
            }
        });
        return card;
    }

    private void loadThumbnail(MediaInfo info, StackPane holder, Label placeholder) {
        Path thumbPath = info.thumbnailPath();
        if (thumbPath != null && Files.isRegularFile(thumbPath)) {
            applyThumbnail(holder, placeholder, thumbPath);
            return;
        }

        FxScheduler.background(() -> {
            Path generated = context.mediaInfo().generateThumbnail(info, 480, 270);
            if (generated != null) {
                FxScheduler.run(() -> applyThumbnail(holder, placeholder, generated));
            } else {
                FxScheduler.run(() -> placeholder.setText(I18n.t("library.noPreview")));
            }
        });
    }

    private void applyThumbnail(StackPane holder, Label placeholder, Path thumbPath) {
        try {
            Image image = new Image(thumbPath.toUri().toString(),
                    CARD_WIDTH, THUMB_HEIGHT, false, true, true);
            if (image.isError()) {
                placeholder.setText(I18n.t("library.noPreview"));
                return;
            }
            ImageView view = new ImageView(image);
            view.setFitWidth(CARD_WIDTH);
            view.setFitHeight(THUMB_HEIGHT);
            view.setPreserveRatio(false);
            view.setSmooth(true);
            holder.getChildren().setAll(view);
            holder.getChildren().add(buildClipBadge(thumbPath));
        } catch (Exception e) {
            Log.debug("Thumbnail load failed: " + e.getMessage());
            placeholder.setText(I18n.t("library.noPreview"));
        }
    }

    private Node buildClipBadge(Path thumbPath) {
        Region spacer = new Region();
        spacer.setMouseTransparent(true);
        return spacer;
    }

    private String buildMeta(MediaInfo info) {
        StringBuilder sb = new StringBuilder();
        if (info.width() > 0 && info.height() > 0) {
            sb.append(info.width()).append('×').append(info.height());
        }
        if (info.sizeBytes() > 0) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(PlatformUtils.formatBytes(info.sizeBytes()));
        }
        if (info.lastModified() != null) {
            if (sb.length() > 0) {
                sb.append("  ·  ");
            }
            sb.append(PlatformUtils.formatDateTime(info.lastModified()));
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void play(Path path) {
        FxScheduler.background(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(path.toFile());
                } else {
                    FxScheduler.run(() -> notify(I18n.t("library.openManual")));
                }
            } catch (Exception e) {
                Log.warn("Could not open " + path + ": " + e.getMessage());
                FxScheduler.run(() -> notify(I18n.t("library.openFailed")));
            }
        });
    }

    private void revealInFolder(Path path) {
        FxScheduler.background(() -> PlatformUtils.revealInFileManager(path));
    }

    private void copyPath(Path path) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(path.toString());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        notify(I18n.t("library.copied"));
    }

    private void confirmDelete(MediaInfo info) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("library.deleteTitle"));
        alert.setHeaderText(I18n.t("library.deleteHeader", info.fileName()));
        alert.setContentText(I18n.t("library.deleteBody"));
        alert.setGraphic(null);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        new org.firefly.screenrec.ui.theme.ThemeManager(context.settings().getTheme())
                .applyCurrent(alert.getDialogPane().getScene());

        alert.showAndWait().ifPresent(button -> {
            if (button == javafx.scene.control.ButtonType.OK) {
                deleteFile(info);
            }
        });
    }

    private void deleteFile(MediaInfo info) {
        FxScheduler.background(() -> {
            boolean ok = false;
            try {
                Files.deleteIfExists(info.path());
                Path thumb = info.thumbnailPath();
                if (thumb != null) {
                    Files.deleteIfExists(thumb);
                }
                ok = true;
            } catch (Exception e) {
                Log.warn("Delete failed: " + e.getMessage());
            }
            boolean success = ok;
            FxScheduler.run(() -> {
                if (success) {
                    allItems.removeIf(i -> i.path().equals(info.path()));
                    renderGrid();
                    notify(I18n.t("library.deleted"));
                } else {
                    notify(I18n.t("library.deleteFailed"));
                }
            });
        });
    }

    private void openOutputFolder() {
        FxScheduler.background(() ->
                PlatformUtils.revealInFileManager(context.outputDirectory()));
    }

    private void notify(String message) {
        if (onNotify != null) {
            onNotify.accept(message);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Formats milliseconds as {@code M:SS} or {@code H:MM:SS}. */
    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }
}

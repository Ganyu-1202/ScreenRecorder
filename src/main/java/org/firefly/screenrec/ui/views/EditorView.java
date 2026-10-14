package org.firefly.screenrec.ui.views;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.core.media.MediaInfo;
import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.Icons;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.firefly.screenrec.core.media.FfmpegPreviewPlayer;
import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelFormat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lightweight clip editor.
 *
 * <p>Provides in-app playback plus lossless-fast trimming, audio extraction and
 * format conversion — all driven by FFmpeg so quality is preserved and no
 * re-encode is needed for a plain trim.
 */
public final class EditorView extends ScrollPane {

    private VBox contentRoot;
    private Node transport;
    private javafx.stage.Stage fullscreenStage;
    private Button playPauseButton;
    private Button fullscreenButton;
    private boolean displayedPlaying;

    private final AppContext context;
    private final Consumer<String> onNotify;

    private final ComboBox<MediaInfo> clipCombo = new ComboBox<>();
    private final StackPane previewHolder = new StackPane();
    private final Label previewPlaceholder = UiFactory.caption(I18n.t("editor.pick"));

    private FfmpegPreviewPlayer player;
    private AnimationTimer previewTimer;
    private MediaInfo currentClip;
    private double clipDurationMs;
    private long selectionVersion;
    private boolean exportBusy;

    private final Slider positionSlider = new Slider(0, 1, 0);
    private final Label positionLabel = UiFactory.caption("0:00 / 0:00");
    private final Slider startSlider = new Slider(0, 1, 0);
    private final Slider endSlider = new Slider(0, 1, 1);
    private final Label rangeLabel = UiFactory.caption("—");
    private final CheckBox losslessCheck = new CheckBox(I18n.t("editor.lossless"));
    private final ProgressBar exportProgress = new ProgressBar(0);

    public EditorView(AppContext context, Consumer<String> onNotify) {
        this.context = context;
        this.onNotify = onNotify;

        getStyleClass().add("app-content");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        VBox root = new VBox(20);
        contentRoot = root;
        transport = buildTransport();
        root.setPadding(new Insets(28, 32, 32, 32));
        root.getChildren().addAll(
                buildHeader(),
                buildClipPicker(),
                buildPreview(),
                transport,
                buildTrimSection(),
                buildExportSection());

        setContent(root);
        loadClips();
    }

    private Node buildHeader() {
        VBox header = new VBox(5);
        header.getChildren().addAll(
                UiFactory.title(I18n.t("editor.title")),
                UiFactory.secondary(I18n.t("editor.subtitle")));
        return header;
    }

    private Node buildClipPicker() {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);

        HBox.setHgrow(clipCombo, Priority.ALWAYS);
        clipCombo.setMaxWidth(Double.MAX_VALUE);
        clipCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(MediaInfo info) {
                if (info == null) {
                    return "";
                }
                return info.fileName() + "   (" + info.durationLabel() + ", "
                        + info.resolutionLabel() + ")";
            }

            @Override
            public MediaInfo fromString(String s) {
                return null;
            }
        });
        clipCombo.valueProperty().addListener((obs, old, info) -> selectClip(info));

        Button rescan = UiFactory.iconButton(Icons.REFRESH, I18n.t("editor.rescan"));
        rescan.getStyleClass().add("button-subtle");
        rescan.setOnAction(e -> loadClips());

        row.getChildren().addAll(clipCombo, rescan);
        return row;
    }

    private Node buildPreview() {
        previewHolder.getStyleClass().add("editor-preview");
        previewHolder.setMinHeight(300);
        previewHolder.setPrefHeight(320);
        previewHolder.setAlignment(Pos.CENTER);
        previewHolder.getChildren().add(previewPlaceholder);
        return previewHolder;
    }

    private Node buildTransport() {
        VBox box = new VBox(8);

        positionSlider.setDisable(true);
        // Seek once on release, not on every dragged pixel (which would spawn decoders repeatedly).
        positionSlider.setOnMouseReleased(e -> {
            if (player != null) player.seek(positionSlider.getValue() * clipDurationMs);
        });
        positionSlider.setOnKeyReleased(e -> {
            if (player != null) player.seek(positionSlider.getValue() * clipDurationMs);
        });

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Button back = UiFactory.button(I18n.t("editor.back5"), Icons.SEEK_BACK, "button-subtle", "button-sm");
        Button playPause = UiFactory.iconButton(Icons.PLAY, I18n.t("editor.play"));
        playPause.getStyleClass().addAll("button-primary", "editor-play-button");
        playPauseButton = playPause;
        Button forward = UiFactory.button(I18n.t("editor.fwd5"), Icons.SEEK_FORWARD, "button-subtle", "button-sm");
        fullscreenButton = UiFactory.iconButton(Icons.EXPAND, I18n.t("editor.fullscreen"));
        fullscreenButton.setOnAction(e -> toggleFullscreen());

        back.setOnAction(e -> seekBy(-5));
        forward.setOnAction(e -> seekBy(5));
        playPause.setOnAction(e -> togglePlayback());

        row.getChildren().addAll(back, playPause, forward, positionSlider,
                UiFactory.hSpacer(), positionLabel, fullscreenButton);
        HBox.setHgrow(positionSlider, Priority.ALWAYS);

        box.getChildren().add(row);
        return box;
    }

    private Node buildTrimSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));

        card.getChildren().addAll(
                UiFactory.subheading(I18n.t("editor.trim")),
                UiFactory.secondary(I18n.t("editor.trim.desc")));

        startSlider.setDisable(true);
        endSlider.setDisable(true);
        startSlider.valueProperty().addListener((obs, old, val) -> {
            if (val.doubleValue() > endSlider.getValue()) {
                endSlider.setValue(val.doubleValue());
            }
            updateRangeLabel();
        });
        endSlider.valueProperty().addListener((obs, old, val) -> {
            if (val.doubleValue() < startSlider.getValue()) {
                startSlider.setValue(val.doubleValue());
            }
            updateRangeLabel();
        });

        card.getChildren().addAll(
                labeledSlider(I18n.t("editor.start"), startSlider),
                labeledSlider(I18n.t("editor.end"), endSlider),
                rangeLabel,
                losslessCheck);
        return card;
    }

    private Node labeledSlider(String label, Slider slider) {
        VBox box = new VBox(4);
        box.getChildren().addAll(UiFactory.caption(label), slider);
        return box;
    }

    private Node buildExportSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));

        card.getChildren().add(UiFactory.subheading(I18n.t("editor.export")));

        exportProgress.setMaxWidth(Double.MAX_VALUE);
        exportProgress.setVisible(false);
        exportProgress.setManaged(false);

        Button trimButton = UiFactory.button(I18n.t("editor.saveTrim"), Icons.EDIT,
                "button-primary", "button-sm");
        trimButton.setOnAction(e -> exportTrim());

        Button extractAudio = UiFactory.button(I18n.t("editor.extractAudio"), Icons.SPEAKER,
                "button-subtle", "button-sm");
        extractAudio.setOnAction(e -> exportAudio());

        Button exportGif = UiFactory.button(I18n.t("editor.exportGif"), Icons.SPARKLE,
                "button-subtle", "button-sm");
        exportGif.setOnAction(e -> exportGif());

        Button revealButton = UiFactory.button(I18n.t("editor.showFile"), Icons.FOLDER,
                "button-subtle", "button-sm");
        revealButton.setOnAction(e -> FxScheduler.background(() ->
                PlatformUtils.openFolder(context.outputDirectory().toAbsolutePath().normalize())));

        HBox actions = new HBox(10, trimButton, extractAudio, exportGif, revealButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(actions, exportProgress);
        return card;
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private void loadClips() {
        FxScheduler.background(() -> {
            List<MediaInfo> clips = new ArrayList<>(context.mediaInfo().listRecordings(context.outputDirectory(), true));
            FxScheduler.run(() -> {
                MediaInfo previous = clipCombo.getValue();
                clipCombo.getItems().setAll(clips);
                if (previous != null) {
                    clips.stream()
                            .filter(c -> c.path().equals(previous.path()))
                            .findFirst()
                            .ifPresentOrElse(clipCombo::setValue,
                                    () -> selectFirstIfAny(clips));
                } else {
                    selectFirstIfAny(clips);
                }
            });
        });
    }

    private void selectFirstIfAny(List<MediaInfo> clips) {
        if (!clips.isEmpty()) {
            clipCombo.getSelectionModel().selectFirst();
        }
    }

    private void selectClip(MediaInfo info) {
        disposePlayer();
        selectionVersion++;
        currentClip = info;
        clipDurationMs = info == null ? 0 : Math.max(0, info.durationMs());

        if (info == null) {
            previewHolder.getChildren().setAll(previewPlaceholder);
            setTransportEnabled(false);
            return;
        }

        startSlider.setValue(0);
        endSlider.setValue(1);
        startSlider.setDisable(clipDurationMs <= 0);
        endSlider.setDisable(clipDurationMs <= 0);
        positionSlider.setDisable(true);
        updateRangeLabel();
        loadPlayer(info.path(), selectionVersion);
    }

    private void loadPlayer(Path path, long version) {
        if (context.ffmpegPath().isEmpty()) {
            previewHolder.getChildren().setAll(UiFactory.caption(I18n.t("editor.noFfmpeg")));
            return;
        }
        WritableImage image = new WritableImage(FfmpegPreviewPlayer.WIDTH, FfmpegPreviewPlayer.HEIGHT);
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(previewHolder.widthProperty().subtract(24));
        view.fitHeightProperty().bind(previewHolder.heightProperty().subtract(24));
        previewHolder.getChildren().setAll(view);
        FfmpegPreviewPlayer candidate = new FfmpegPreviewPlayer(context.ffmpegPath().orElseThrow(), currentClip,
                message -> {
                    Log.warn("FFmpeg preview: " + message);
                    FxScheduler.run(() -> {
                        if (version == selectionVersion) notify(I18n.t("editor.playError") + " " + message);
                    });
                });
        player = candidate;
        previewTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (player != candidate || version != selectionVersion) return;
                var frame = candidate.pollFrame();
                if (frame != null) {
                    WritableImage target = (WritableImage) view.getImage();
                    if (target.getWidth() != frame.width() || target.getHeight() != frame.height()) {
                        target = new WritableImage(frame.width(), frame.height());
                        view.setImage(target);
                    }
                    target.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                            PixelFormat.getByteBgraInstance(), frame.bgra(), 0, frame.width() * 4);
                }
                updatePlaybackButton(candidate.isPlaying());
                double time = candidate.positionMillis();
                if (!positionSlider.isValueChanging() && !positionSlider.isPressed() && clipDurationMs > 0)
                    positionSlider.setValue(time / clipDurationMs);
                updatePositionLabel(time, clipDurationMs);
            }
        };
        previewTimer.start();
        positionSlider.setDisable(clipDurationMs <= 0);
        // Load a still first frame without starting audio or continuous playback.
        candidate.seek(0);
    }

    private void updatePlaybackButton(boolean playing) {
        if (playPauseButton == null || displayedPlaying == playing) return;
        displayedPlaying = playing;
        playPauseButton.setGraphic(UiFactory.icon(playing ? Icons.PAUSE : Icons.PLAY, 18));
        playPauseButton.setAccessibleText(I18n.t(playing ? "editor.pause" : "editor.play"));
        UiFactory.installTooltip(playPauseButton, I18n.t(playing ? "editor.pause" : "editor.play"));
    }

    private void toggleFullscreen() {
        if (fullscreenStage != null) { fullscreenStage.close(); return; }
        if (player == null || getScene() == null) return;
        javafx.stage.Stage stage = new javafx.stage.Stage(javafx.stage.StageStyle.UNDECORATED);
        stage.initOwner(getScene().getWindow());
        org.firefly.screenrec.ui.theme.AppIcons.applyWindowIcons(stage);
        VBox fullscreenRoot = new VBox(12);
        fullscreenRoot.setPadding(new Insets(12));
        fullscreenRoot.getStyleClass().add("app-root");
        contentRoot.getChildren().removeAll(previewHolder, transport);
        fullscreenRoot.getChildren().addAll(previewHolder, transport);
        VBox.setVgrow(previewHolder, Priority.ALWAYS);
        Label hint = new Label(I18n.t("editor.fullscreenHint"));
        hint.setMouseTransparent(true);
        hint.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 10;");
        StackPane screenRoot = new StackPane(fullscreenRoot, hint);
        StackPane.setAlignment(hint, Pos.TOP_CENTER);
        StackPane.setMargin(hint, new Insets(24, 0, 0, 0));
        javafx.animation.PauseTransition hintTimeout = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
        hintTimeout.setOnFinished(e -> screenRoot.getChildren().remove(hint));
        javafx.scene.Scene scene = new javafx.scene.Scene(screenRoot, 960, 600);
        scene.getStylesheets().setAll(getScene().getStylesheets());
        stage.setScene(scene);
        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.ESCAPE));
        stage.setOnHidden(e -> {
            hintTimeout.stop();
            if (player != null) player.setHighResolution(false);
            fullscreenRoot.getChildren().removeAll(previewHolder, transport);
            VBox.setVgrow(previewHolder, Priority.NEVER);
            contentRoot.getChildren().addAll(2, List.of(previewHolder, transport));
            fullscreenStage = null;
            fullscreenButton.setGraphic(UiFactory.icon(Icons.EXPAND, 18));
            UiFactory.installTooltip(fullscreenButton, I18n.t("editor.fullscreen"));
        });
        stage.fullScreenProperty().addListener((obs, old, full) -> {
            if (!full && fullscreenStage == stage) stage.close();
        });
        fullscreenStage = stage;
        fullscreenButton.setGraphic(UiFactory.icon(Icons.RESTORE, 18));
        UiFactory.installTooltip(fullscreenButton, I18n.t("editor.exitFullscreen"));
        stage.show();
        stage.setFullScreen(true);
        player.setHighResolution(true);
        hintTimeout.playFromStart();
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
        updatePlaybackButton(player.isPlaying());
    }

    private void seekBy(double seconds) {
        if (player != null) player.seek(player.positionMillis() + seconds * 1000);
    }

    private void setTransportEnabled(boolean enabled) {
        positionSlider.setDisable(!enabled);
        startSlider.setDisable(!enabled);
        endSlider.setDisable(!enabled);
    }

    private void updatePositionLabel(double currentMs, double totalMs) {
        positionLabel.setText(formatTime(currentMs) + " / " + formatTime(totalMs));
    }

    private void updateRangeLabel() {
        if (clipDurationMs <= 0) {
            rangeLabel.setText("—");
            return;
        }
        double total = clipDurationMs;
        double start = startSlider.getValue() * total;
        double end = endSlider.getValue() * total;
        rangeLabel.setText(I18n.t("editor.keeping", formatTime(start),
                formatTime(end), formatTime(end - start)));
    }

    private void disposePlayer() {
        if (fullscreenStage != null) fullscreenStage.close();
        updatePlaybackButton(false);
        if (previewTimer != null) { previewTimer.stop(); previewTimer = null; }
        if (player != null) { player.close(); player = null; }
    }

    /** Stops playback; call this when navigating away. */
    public void shutdown() {
        selectionVersion++;
        disposePlayer();
    }

    // ------------------------------------------------------------------
    // Export operations
    // ------------------------------------------------------------------

    private void exportTrim() {
        if (currentClip == null || clipDurationMs <= 0) {
            notify(I18n.t("editor.pickFirst"));
            return;
        }
        double total = clipDurationMs;
        double startSec = startSlider.getValue() * total / 1000.0;
        double endSec = endSlider.getValue() * total / 1000.0;
        if (endSec - startSec < 0.2) {
            notify(I18n.t("editor.rangeTooShort"));
            return;
        }

        Path source = currentClip.path();
        Path output = losslessCheck.isSelected() ? sibling(source, "_trimmed", ".mkv") : trimmedOutputPath(source);
        boolean lossless = losslessCheck.isSelected();

        runExport(I18n.t("editor.trimming"), () -> {
            List<String> cmd = new ArrayList<>(List.of(
                    context.ffmpegPath().orElseThrow().toString(),
                    "-hide_banner", "-loglevel", "error",
                    "-ss", String.format(java.util.Locale.ROOT, "%.3f", startSec),
                    "-i", source.toString(),
                    "-t", String.format(java.util.Locale.ROOT, "%.3f", endSec - startSec)));

            if (lossless) {
                cmd.addAll(List.of("-c", "copy", "-avoid_negative_ts", "make_zero"));
            } else {
                cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                        "-c:a", "aac", "-b:a", "192k"));
            }
            cmd.addAll(List.of("-y", output.toString()));

            return runFfmpeg(cmd, output);
        });
    }

    private void exportAudio() {
        if (currentClip == null) {
            notify(I18n.t("editor.pickFirst"));
            return;
        }
        if (!currentClip.hasAudio()) {
            notify(I18n.t("editor.noAudioTrack"));
            return;
        }
        Path source = currentClip.path();
        Path output = sibling(source, "_audio", ".m4a");

        runExport(I18n.t("editor.extracting"), () -> {
            List<String> cmd = List.of(
                    context.ffmpegPath().orElseThrow().toString(),
                    "-hide_banner", "-loglevel", "error",
                    "-i", source.toString(),
                    "-vn", "-c:a", "aac", "-b:a", "192k",
                    "-y", output.toString());
            return runFfmpeg(cmd, output);
        });
    }

    private void exportGif() {
        if (currentClip == null || clipDurationMs <= 0) {
            notify(I18n.t("editor.pickFirst"));
            return;
        }
        double total = clipDurationMs;
        double startSec = startSlider.getValue() * total / 1000.0;
        double endSec = endSlider.getValue() * total / 1000.0;
        double durationSec = endSec - startSec;

        if (durationSec > 20) {
            notify(I18n.t("editor.gifLimit"));
            return;
        }
        if (durationSec < 0.2) {
            notify(I18n.t("editor.rangeTooShort"));
            return;
        }

        Path source = currentClip.path();
        Path output = sibling(source, "_clip", ".gif");

        runExport(I18n.t("editor.buildingGif"), () -> {
            // Two-pass palette gives far better colours than a plain scale.
            String filter = "fps=12,scale=800:-1:flags=lanczos,split[a][b];"
                    + "[a]palettegen[p];[b][p]paletteuse";
            List<String> cmd = List.of(
                    context.ffmpegPath().orElseThrow().toString(),
                    "-hide_banner", "-loglevel", "error",
                    "-ss", String.format(java.util.Locale.ROOT, "%.3f", startSec),
                    "-t", String.format(java.util.Locale.ROOT, "%.3f", durationSec),
                    "-i", source.toString(),
                    "-vf", filter,
                    "-loop", "0",
                    "-y", output.toString());
            return runFfmpeg(cmd, output);
        });
    }

    private interface ExportTask {
        boolean run();
    }

    private void runExport(String label, ExportTask task) {
        if (exportBusy) return;
        if (context.ffmpegPath().isEmpty()) {
            notify(I18n.t("editor.noFfmpeg"));
            return;
        }
        exportBusy = true;
        exportProgress.setVisible(true);
        exportProgress.setManaged(true);
        exportProgress.setProgress(-1);
        notify(label);

        FxScheduler.background(() -> {
            boolean ok = false;
            try {
                ok = task.run();
            } catch (Exception e) {
                Log.warn("Export failed: " + e.getMessage());
            }
            boolean success = ok;
            FxScheduler.run(() -> {
                exportBusy = false;
                exportProgress.setVisible(false);
                exportProgress.setManaged(false);
                notify(success ? I18n.t("editor.exportDone") : I18n.t("editor.exportFailed"));
                if (success) {
                    loadClips();
                }
            });
        });
    }

    private boolean runFfmpeg(List<String> cmd, Path output) {
        try {
            org.firefly.screenrec.util.ProcessRunner.Result result =
                    org.firefly.screenrec.util.ProcessRunner.run(cmd, 15 * 60_000);
            if (!result.ok()) {
                Log.warn("FFmpeg export failed: " + result.stderr());
                return false;
            }
            return Files.exists(output);
        } catch (Exception e) {
            Log.warn("FFmpeg export error: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    private Path trimmedOutputPath(Path source) {
        return sibling(source, "_trimmed", ".mp4");
    }

    private Path sibling(Path source, String suffix, String extension) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        Path parent = context.outputDirectory().toAbsolutePath().normalize();
        Path candidate = parent.resolve(name + suffix + extension);
        int counter = 2;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(name + suffix + "_" + counter + extension);
            counter++;
        }
        return candidate;
    }

    private static String formatTime(double millis) {
        long totalSeconds = Math.max(0, (long) (millis / 1000));
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private void notify(String message) {
        if (onNotify != null) {
            onNotify.accept(message);
        }
    }
}

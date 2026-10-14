package org.firefly.screenrec.ui.views;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.core.audio.AudioDevice;
import org.firefly.screenrec.core.recorder.CaptureSource;
import org.firefly.screenrec.core.recorder.EncoderBackend;
import org.firefly.screenrec.core.recorder.OutputFormat;
import org.firefly.screenrec.core.recorder.VideoQuality;
import org.firefly.screenrec.core.screen.MonitorService;
import org.firefly.screenrec.core.screen.ScreenInfo;
import org.firefly.screenrec.ui.RecordingController;
import org.firefly.screenrec.ui.components.SegmentedControl;
import org.firefly.screenrec.ui.components.ToggleSwitch;
import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.Icons;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The main recording screen: pick what to capture, choose audio, tune quality,
 * and hit record.
 */
public final class RecordView extends ScrollPane {

    private final AppContext context;
    private final RecordingController controller;
    private final Consumer<String> onNotify;

    private SegmentedControl<CaptureSource.Kind> sourceSegments;
    private VBox sourceCards;
    private ComboBox<ScreenInfo> monitorCombo;
    private ToggleSwitch micToggle, sysToggle;
    private Label audioStatus;
    private ComboBox<AudioDevice> micCombo;
    private ComboBox<AudioDevice> systemCombo;
    private ComboBox<String> qualityCombo;
    private ComboBox<org.firefly.screenrec.core.screen.ApplicationWindowService.Window> windowCombo;
    private final org.firefly.screenrec.core.screen.ApplicationWindowService.Window manualWindowEntry =
            new org.firefly.screenrec.core.screen.ApplicationWindowService.Window("", "", 0, 0, 0, 0);
    private ComboBox<String> resolutionCombo;
    private ComboBox<Integer> fpsCombo;
    private ComboBox<OutputFormat> formatCombo;
    private ComboBox<EncoderBackend> encoderCombo;
    private Button recordButton;
    private Label outputPathLabel;

    private ScreenInfo selectedScreen;
    private final List<Label> selectionListeners = new ArrayList<>();

    public RecordView(AppContext context, RecordingController controller,
                      Consumer<String> onNotify) {
        this.context = context;
        this.controller = controller;
        this.onNotify = onNotify;

        getStyleClass().add("app-content");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        VBox root = new VBox(22);
        root.setPadding(new Insets(28, 32, 32, 32));

        root.getChildren().addAll(
                buildHeader(),
                buildSourceSection(),
                buildTwoColumnRow(buildAudioSection(), buildQualitySection()),
                buildOutputSection(),
                buildRecordBar());

        setContent(root);
        refreshDevices();
        refreshEncoders();
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    private Node buildHeader() {
        VBox header = new VBox(5);
        Label title = UiFactory.title(I18n.t("record.title"));
        Label subtitle = UiFactory.secondary(I18n.t("record.subtitle"));
        header.getChildren().addAll(title, subtitle);
        return header;
    }

    private Node buildSourceSection() {
        VBox section = new VBox(12);

        HBox labelRow = new HBox(10);
        labelRow.setAlignment(Pos.CENTER_LEFT);
        labelRow.getChildren().add(UiFactory.sectionLabel(I18n.t("record.captureSource")));

        sourceSegments = new SegmentedControl<CaptureSource.Kind>()
                .add(CaptureSource.Kind.FULL_SCREEN, I18n.t("record.fullScreen"))
                .add(CaptureSource.Kind.MONITOR, I18n.t("record.display"))
                .add(CaptureSource.Kind.REGION, I18n.t("record.region"))
                .add(CaptureSource.Kind.WINDOW, I18n.t("record.application"));
        sourceSegments.setValue(CaptureSource.Kind.MONITOR);
        labelRow.getChildren().addAll(UiFactory.hSpacer(), sourceSegments);

        // Monitor picker shown only in Display mode.
        monitorCombo = new ComboBox<>();
        monitorCombo.setPrefWidth(300);
        monitorCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ScreenInfo s) {
                return s == null ? "" : s.name() + "  ·  " + s.resolutionLabel()
                        + (s.scaleX() > 1.01 ? "  (" + (int) (s.scaleX() * 100) + "%)" : "");
            }

            @Override
            public ScreenInfo fromString(String s) {
                return null;
            }
        });
        monitorCombo.getItems().setAll(MonitorService.monitors());
        ScreenInfo primary = org.firefly.screenrec.core.screen.Screens.primary(monitorCombo.getItems());
        if (primary != null) {
            monitorCombo.getSelectionModel().select(primary);
            selectedScreen = primary;
        }
        monitorCombo.valueProperty().addListener((obs, old, val) -> selectedScreen = val);

        HBox monitorRow = new HBox(12);
        monitorRow.setAlignment(Pos.CENTER_LEFT);
        monitorRow.getChildren().addAll(UiFactory.caption(I18n.t("record.display")), monitorCombo);

        sourceCards = new VBox(12);
        windowCombo = new ComboBox<>();
        windowCombo.setPrefWidth(460);
        windowCombo.setConverter(new javafx.util.StringConverter<>() {
            public String toString(org.firefly.screenrec.core.screen.ApplicationWindowService.Window w) {
                return w == manualWindowEntry ? I18n.t("record.manualWindow") : w == null ? "" : w.toString();
            }
            public org.firefly.screenrec.core.screen.ApplicationWindowService.Window fromString(String s) { return null; }
        });
        windowCombo.valueProperty().addListener((obs, old, value) -> {
            if (value == manualWindowEntry) Platform.runLater(() -> {
                windowCombo.setValue(old == manualWindowEntry ? null : old);
                chooseWindowManually();
            });
        });
        windowCombo.setPromptText(I18n.t("record.chooseApplication"));
        Button refreshWindows = UiFactory.button(I18n.t("record.refreshApplications"), null, "button-subtle");
        refreshWindows.setOnAction(e -> refreshApplications());
        HBox windowPicker = new HBox(12, windowCombo, refreshWindows);
        windowPicker.setAlignment(Pos.CENTER_LEFT);
        VBox windowRow = new VBox(8, windowPicker, UiFactory.caption(I18n.t("record.applicationHint")));
        windowRow.setVisible(false);
        windowRow.setManaged(false);
        sourceCards.getChildren().addAll(monitorRow, windowRow);

        sourceSegments.valueProperty().addListener((obs, old, kind) -> {
            boolean windowMode = kind == CaptureSource.Kind.WINDOW && !org.firefly.screenrec.util.PlatformUtils.isWayland();
            windowRow.setVisible(windowMode);
            windowRow.setManaged(windowMode);
            if (windowMode) refreshApplications();
            boolean monitorMode = kind == CaptureSource.Kind.MONITOR;
            monitorRow.setVisible(monitorMode);
            monitorRow.setManaged(monitorMode);
        });

        section.getChildren().addAll(labelRow, sourceCards);
        return section;
    }

    private Node buildAudioSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));

        Label title = UiFactory.subheading(I18n.t("record.audio"));
        card.getChildren().add(title);

        // Microphone.
        micToggle = new ToggleSwitch(context.settings().isMicrophoneEnabled());
        micToggle.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setCaptureMicrophone(on);
            context.saveSettings();
            if (micCombo != null) {
                micCombo.setDisable(!on);
            }
        });

        micCombo = new ComboBox<>();
        micCombo.setPrefWidth(250);
        micCombo.setConverter(deviceConverter());
        micCombo.setDisable(!context.settings().isMicrophoneEnabled());
        micCombo.valueProperty().addListener((obs, old, dev) -> {
            if (dev != null) {
                context.settings().setMicrophoneId(dev.id());
                context.saveSettings();
            }
        });

        VBox micBlock = new VBox(8);
        micBlock.getChildren().addAll(
                ToggleSwitch.labeledRow(I18n.t("record.microphone"),
                        I18n.t("record.micDesc"), micToggle),
                micCombo);

        // System audio.
        boolean systemSupported = context.supportsSystemAudio();
        sysToggle = new ToggleSwitch(
                systemSupported && context.settings().isSystemAudioEnabled());
        sysToggle.setDisable(!systemSupported);
        sysToggle.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setCaptureSystemAudio(on);
            context.saveSettings();
            if (systemCombo != null) {
                systemCombo.setDisable(!on);
            }
        });

        systemCombo = new ComboBox<>();
        systemCombo.setPrefWidth(250);
        systemCombo.setConverter(deviceConverter());
        systemCombo.setDisable(!sysToggle.isSelected());
        systemCombo.valueProperty().addListener((obs, old, dev) -> {
            if (dev != null) {
                context.settings().setSystemAudioId(dev.id());
                context.saveSettings();
            }
        });

        VBox sysBlock = new VBox(8);
        sysBlock.getChildren().addAll(
                ToggleSwitch.labeledRow(I18n.t("record.systemAudio"),
                        I18n.t("record.systemAudio.desc"), sysToggle),
                systemCombo);

        audioStatus = UiFactory.caption("");
        audioStatus.setWrapText(true);
        Button refresh = new Button(I18n.t("record.audio.refresh"));
        refresh.setOnAction(event -> {
            if (context.activeSession() != null && context.activeSession().isActive()) return;
            refresh.setDisable(true);
            Thread worker = new Thread(() -> {
                try { context.refreshAudioDevices(); }
                finally { Platform.runLater(() -> { refreshDevices(); refresh.setDisable(false); }); }
            }, "audio-device-refresh");
            worker.setDaemon(true);
            worker.start();
        });
        card.getChildren().addAll(micBlock, sysBlock, audioStatus, refresh);
        return card;
    }

    private javafx.util.StringConverter<AudioDevice> deviceConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AudioDevice d) {
                return d == null ? "" : d.name();
            }

            @Override
            public AudioDevice fromString(String s) {
                return null;
            }
        };
    }

    private Node buildQualitySection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));

        card.getChildren().add(UiFactory.subheading(I18n.t("record.quality")));

        qualityCombo = new ComboBox<>();
        qualityCombo.getItems().setAll(
                I18n.t("record.quality.low"), I18n.t("record.quality.medium"),
                I18n.t("record.quality.high"), I18n.t("record.quality.ultra"),
                I18n.t("record.quality.nearLossless"));
        qualityCombo.setValue(qualityLabel(context.settings().getQuality()));
        qualityCombo.setMaxWidth(Double.MAX_VALUE);
        qualityCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setQuality(qualityId(val));
                context.saveSettings();
            }
        });

        ComboBox<Integer> bitrateCombo = new ComboBox<>();
        bitrateCombo.getItems().setAll(0, 1, 2, 4, 6, 8, 10, 15, 20, 30, 50, 100, 150, 200, -1);
        bitrateCombo.setConverter(new javafx.util.StringConverter<>() {
            public String toString(Integer v) { return v == null || v == 0 ? I18n.t("record.bitrateAuto") : v == -1 ? I18n.t("record.bitrateCustom") : v + " Mbps"; }
            public Integer fromString(String v) { return 0; }
        });
        int savedBitrate = context.settings().getVideoBitrateMbps();
        if (!bitrateCombo.getItems().contains(savedBitrate)) bitrateCombo.getItems().add(savedBitrate);
        bitrateCombo.setValue(savedBitrate);
        bitrateCombo.setMaxWidth(Double.MAX_VALUE);
        bitrateCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null && val == -1) {
                Platform.runLater(() -> {
                    bitrateCombo.setValue(old);
                    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(
                            String.valueOf(Math.max(1, context.settings().getVideoBitrateMbps())));
                    dialog.setTitle(I18n.t("record.bitrateCustom"));
                    dialog.setHeaderText(I18n.t("record.bitrateInput"));
                    dialog.initOwner(getScene().getWindow());
                    new org.firefly.screenrec.ui.theme.ThemeManager(context.settings().getTheme()).applyCurrent(dialog.getDialogPane().getScene());
                    var ok = (Button) dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
                    Runnable validate = () -> { try { int n = Integer.parseInt(dialog.getEditor().getText().trim()); ok.setDisable(n < 1 || n > 200); }
                        catch (NumberFormatException e) { ok.setDisable(true); } };
                    dialog.getEditor().textProperty().addListener((o,a,b) -> validate.run()); validate.run();
                    dialog.showAndWait().ifPresent(text -> {
                        int n = Integer.parseInt(text.trim());
                        if (!bitrateCombo.getItems().contains(n)) bitrateCombo.getItems().add(bitrateCombo.getItems().size()-1,n);
                        bitrateCombo.setValue(n);
                    });
                });
            } else if (val != null) { context.settings().setVideoBitrateMbps(val); context.saveSettings(); }
        });

        resolutionCombo = new ComboBox<>();
        resolutionCombo.getItems().setAll(I18n.t("record.resolution.source"),
                "2160p", "1440p", "1080p", "720p", "480p");
        resolutionCombo.setValue(resolutionLabelFor(context.settings().getResolutionCap()));
        resolutionCombo.setMaxWidth(Double.MAX_VALUE);
        resolutionCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setResolutionCap(
                        I18n.t("record.resolution.source").equals(val) ? "source" : val);
                context.saveSettings();
            }
        });

        fpsCombo = new ComboBox<>();
        fpsCombo.getItems().setAll(15, 24, 30, 60, 120, 144);
        fpsCombo.setValue(context.settings().resolveFrameRate());
        fpsCombo.setMaxWidth(Double.MAX_VALUE);
        fpsCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setFps(val);
                context.saveSettings();
            }
        });

        card.getChildren().addAll(
                labeledField(I18n.t("record.quality"), qualityCombo),
                labeledField(I18n.t("record.bitrate"), bitrateCombo),
                labeledField(I18n.t("record.resolution"), resolutionCombo),
                labeledField(I18n.t("record.frameRate"), fpsCombo));
        return card;
    }

    private Node buildOutputSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().add(UiFactory.subheading(I18n.t("record.output")));

        Button browseButton = UiFactory.button(I18n.t("record.change"), Icons.FOLDER, "button-subtle", "button-sm");
        browseButton.setOnAction(e -> chooseOutputDirectory());
        header.getChildren().addAll(UiFactory.hSpacer(), browseButton);

        outputPathLabel = UiFactory.secondary(context.outputDirectory().toString());
        outputPathLabel.setWrapText(true);
        UiFactory.installTooltip(outputPathLabel, context.outputDirectory().toString());

        formatCombo = new ComboBox<>();
        formatCombo.getItems().setAll(OutputFormat.values());
        formatCombo.setValue(context.settings().resolveFormat());
        formatCombo.setMaxWidth(Double.MAX_VALUE);
        formatCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setFormat(val.name());
                context.saveSettings();
            }
        });

        encoderCombo = new ComboBox<>();
        encoderCombo.setMaxWidth(Double.MAX_VALUE);
        encoderCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setEncoder(val.name());
                context.saveSettings();
            }
        });

        card.getChildren().addAll(header, outputPathLabel,
                labeledField(I18n.t("record.format"), formatCombo),
                labeledField(I18n.t("record.encoder"), encoderCombo));
        return card;
    }

    private Node buildRecordBar() {
        HBox bar = new HBox(16);
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(8, 0, 0, 0));

        recordButton = new Button(I18n.t("record.start"));
        recordButton.getStyleClass().add("record-button");
        recordButton.setGraphic(buildRecordDot());
        recordButton.setOnAction(e -> startRecording());

        Label hint = UiFactory.caption(I18n.t("record.shortcuts"));
        hint.setOpacity(0.8);

        VBox box = new VBox(8, recordButton, hint);
        box.setAlignment(Pos.CENTER);

        bar.getChildren().add(box);
        return bar;
    }

    private Node buildRecordDot() {
        Region dot = new Region();
        dot.setMinSize(12, 12);
        dot.setPrefSize(12, 12);
        dot.setMaxSize(12, 12);
        dot.setStyle("-fx-background-color: white; -fx-background-radius: 999px;");
        return dot;
    }

    private Node labeledField(String label, Node field) {
        VBox box = new VBox(6);
        box.getChildren().addAll(UiFactory.caption(label), field);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * Lays out two cards as equal, fixed-fraction columns.
     *
     * <p>Deliberately a {@link GridPane} with percentage column constraints
     * rather than an {@code HBox} with {@code hgrow=ALWAYS}: the columns inside
     * the quality card contain {@link ComboBox}es whose preferred width grows
     * to fit the widest item in their popup list. With a flexible HBox that
     * growth fed straight back into the column widths, so opening any dropdown
     * visibly widened the quality column and squeezed the audio column.
     *
     * <p>GridPane resolves each column to a percentage of the grid width, so
     * the column size is decoupled from the children's preferred widths and the
     * popup list can no longer influence the layout.
     */
    private Node buildTwoColumnRow(Node left, Node right) {
        for (Node node : new Node[]{left, right}) {
            if (node instanceof Region region) {
                // Let the column shrink below the content's preferred width and
                // grow to fill the cell; the cell, not the content, decides.
                region.setMinWidth(0);
                region.setMaxWidth(Double.MAX_VALUE);
            }
        }

        ColumnConstraints leftColumn = new ColumnConstraints();
        leftColumn.setPercentWidth(50);
        leftColumn.setHgrow(Priority.ALWAYS);
        leftColumn.setFillWidth(true);

        ColumnConstraints rightColumn = new ColumnConstraints();
        rightColumn.setPercentWidth(50);
        rightColumn.setHgrow(Priority.ALWAYS);
        rightColumn.setFillWidth(true);

        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setAlignment(Pos.TOP_LEFT);
        grid.getColumnConstraints().addAll(leftColumn, rightColumn);
        grid.add(left, 0, 0);
        grid.add(right, 1, 0);
        return grid;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void startRecording() {
        CaptureSource.Kind kind = sourceSegments.getValue();
        AudioDevice mic = micCombo.isDisabled() ? null : micCombo.getValue();
        AudioDevice sys = systemCombo.isDisabled() ? null : systemCombo.getValue();

        if (org.firefly.screenrec.util.PlatformUtils.isWayland()) {
            if (kind == CaptureSource.Kind.REGION) { startPortalRegion(mic, sys); return; }
            // The compositor, not XWayland, is authoritative for windows and displays.
            controller.startRecording(kind, null, mic, sys);
            return;
        }
        if (kind == CaptureSource.Kind.WINDOW) {
            var selected = windowCombo.getValue();
            if (selected == null) { notify(I18n.t("record.chooseApplication")); return; }
            FxScheduler.background(() -> {
                try {
                    var current = org.firefly.screenrec.core.screen.ApplicationWindowService.list().stream()
                            .filter(w -> w.title().equals(selected.title()) && w.process().equals(selected.process())).findFirst();
                    FxScheduler.run(() -> {
                        if (current.isEmpty()) notify(I18n.t("record.applicationMissing"));
                        else controller.startWindowRecording(current.get().source(), mic, sys);
                    });
                } catch (Exception e) { FxScheduler.run(() -> notify(I18n.t("record.applicationMissing"))); }
            });
        } else if (kind == CaptureSource.Kind.REGION) {
            controller.startAllScreensRegionRecording(mic, sys);
        } else if (kind == CaptureSource.Kind.MONITOR) {
            if (selectedScreen == null) { notify(I18n.t("record.noDisplay")); return; }
            controller.startMonitorRecording(selectedScreen, mic, sys);
        } else {
            controller.startRecording(kind, null, mic, sys);
        }
    }

    private void startPortalRegion(AudioDevice mic, AudioDevice sys) {
        javafx.scene.control.Dialog<javafx.geometry.Rectangle2D> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Wayland / Portal");
        dialog.setHeaderText(org.firefly.screenrec.ui.i18n.I18n.t("record.portalRegionHint"));
        javafx.scene.control.TextField x = new javafx.scene.control.TextField("0");
        javafx.scene.control.TextField y = new javafx.scene.control.TextField("0");
        javafx.scene.control.TextField w = new javafx.scene.control.TextField("640");
        javafx.scene.control.TextField h = new javafx.scene.control.TextField("360");
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(12); grid.setVgap(8);
        grid.addRow(0, new Label("X"), x); grid.addRow(1, new Label("Y"), y);
        grid.addRow(2, new Label("Width"), w); grid.addRow(3, new Label("Height"), h);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        var ok = dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try { if (Integer.parseInt(x.getText()) < 0 || Integer.parseInt(y.getText()) < 0
                    || Integer.parseInt(w.getText()) < 2 || Integer.parseInt(h.getText()) < 2) e.consume(); }
            catch (NumberFormatException ex) { e.consume(); }
        });
        dialog.setResultConverter(b -> b == javafx.scene.control.ButtonType.OK ?
                new javafx.geometry.Rectangle2D(Integer.parseInt(x.getText()), Integer.parseInt(y.getText()),
                        Integer.parseInt(w.getText()), Integer.parseInt(h.getText())) : null);
        dialog.showAndWait().ifPresent(r -> controller.startRecording(CaptureSource.Kind.REGION, r, mic, sys));
    }

    private void chooseWindowManually() {
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(I18n.t("record.manualWindow"));
        dialog.setHeaderText(I18n.t("record.windowTitle"));
        dialog.initOwner(getScene().getWindow());
        ComboBox<String> picker = new ComboBox<>(); picker.setEditable(true); picker.setPrefWidth(440);
        windowCombo.getItems().stream().filter(w -> w != manualWindowEntry)
                .map(org.firefly.screenrec.core.screen.ApplicationWindowService.Window::title).distinct().forEach(picker.getItems()::add);
        dialog.getDialogPane().setContent(picker);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        new org.firefly.screenrec.ui.theme.ThemeManager(context.settings().getTheme()).applyCurrent(dialog.getDialogPane().getScene());
        dialog.setResultConverter(b -> b == javafx.scene.control.ButtonType.OK ? picker.getEditor().getText().trim() : null);
        dialog.showAndWait().filter(t -> !t.isBlank()).ifPresent(title -> {
            FxScheduler.background(() -> {
                try {
                    var matches = org.firefly.screenrec.core.screen.ApplicationWindowService.list().stream().filter(w -> w.title().equals(title)).toList();
                    FxScheduler.run(() -> {
                        if (matches.size() != 1) { notify(I18n.t("record.applicationMissing")); return; }
                        var w = matches.get(0);
                        if (!windowCombo.getItems().contains(w)) windowCombo.getItems().add(0,w);
                        windowCombo.setValue(w);
                    });
                } catch (Exception e) { FxScheduler.run(() -> notify(I18n.t("record.applicationMissing"))); }
            });
        });
    }

    private void refreshApplications() {
        var previous = windowCombo.getValue();
        FxScheduler.background(() -> {
            try {
                var windows = org.firefly.screenrec.core.screen.ApplicationWindowService.list();
                FxScheduler.run(() -> {
                    windowCombo.getItems().setAll(windows);
                    windowCombo.getItems().add(manualWindowEntry);
                    if (previous != null) windows.stream().filter(w -> w.title().equals(previous.title())
                            && w.process().equals(previous.process())).findFirst().ifPresent(windowCombo::setValue);
                });
            } catch (Exception e) { FxScheduler.run(() -> notify(I18n.t("record.applicationMissing"))); }
        });
    }

    private void chooseOutputDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("record.chooseFolder"));
        File current = context.outputDirectory().toFile();
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) {
            context.settings().setOutputDirectory(chosen.getAbsolutePath());
            context.saveSettings();
            outputPathLabel.setText(chosen.getAbsolutePath());
            UiFactory.installTooltip(outputPathLabel, chosen.getAbsolutePath());
        }
    }

    private void notify(String message) {
        if (onNotify != null) {
            onNotify.accept(message);
        }
    }

    // ------------------------------------------------------------------
    // Refreshing
    // ------------------------------------------------------------------

    /** Re-populates audio device lists after FFmpeg capabilities change. */
    public void refreshDevices() {
        List<AudioDevice> mics = context.microphones();
        List<AudioDevice> sys = context.systemAudioDevices();

        String micId = context.settings().getMicrophoneId();
        String sysId = context.settings().getSystemAudioId();
        micToggle.setDisable(mics.isEmpty());
        sysToggle.setDisable(sys.isEmpty());
        micCombo.setDisable(mics.isEmpty() || !micToggle.isSelected());
        systemCombo.setDisable(sys.isEmpty() || !sysToggle.isSelected());
        audioStatus.setText(context.audioDevices().isEmpty() ? I18n.t("record.audio.empty")
                : context.systemAudioDevices().isEmpty() ? I18n.t("record.notAvailable") : "");
        micCombo.getItems().setAll(mics);
        selectById(micCombo, mics, micId);
        systemCombo.getItems().setAll(sys);
        selectById(systemCombo, sys, sysId);
    }

    /** Re-populates the encoder list with encoders that work on this machine. */
    public void refreshEncoders() {
        List<EncoderBackend> encoders = new ArrayList<>();
        encoders.add(EncoderBackend.AUTO);
        encoders.addAll(context.usableEncoders().stream()
                .filter(e -> e != EncoderBackend.AUTO)
                .distinct()
                .toList());

        encoderCombo.getItems().setAll(encoders);
        EncoderBackend current = context.settings().resolveEncoder();
        encoderCombo.setValue(encoders.contains(current) ? current : EncoderBackend.AUTO);
    }

    private static <T> void selectById(ComboBox<T> combo, List<T> items, String id) {
        if (id == null || id.isBlank() || items.isEmpty()) {
            if (!items.isEmpty()) {
                combo.getSelectionModel().selectFirst();
            }
            return;
        }
        for (T item : items) {
            String itemId = item instanceof AudioDevice d ? d.id() : String.valueOf(item);
            if (id.equals(itemId)) {
                combo.getSelectionModel().select(item);
                return;
            }
        }
        combo.getSelectionModel().selectFirst();
    }

    /** Maps a localised quality label back to its settings id. */
    private static String qualityId(String label) {
        if (I18n.t("record.quality.low").equals(label)) {
            return "LOW";
        }
        if (I18n.t("record.quality.medium").equals(label)) {
            return "MEDIUM";
        }
        if (I18n.t("record.quality.high").equals(label)) {
            return "HIGH";
        }
        if (I18n.t("record.quality.ultra").equals(label)) {
            return "ULTRA";
        }
        if (I18n.t("record.quality.nearLossless").equals(label)) {
            return "LOSSLESS";
        }
        // Fall back to the English id, which is what the stored value looks like.
        return label == null ? "HIGH"
                : label.toUpperCase(java.util.Locale.ROOT)
                        .replace("-", "_").replace(" ", "_")
                        .replace("NEAR_LOSSLESS", "LOSSLESS");
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String lower = s.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Renders the stored quality id (e.g. {@code LOSSLESS}) as its localised
     * label so the combo box shows the right text after a language switch.
     */
    private static String qualityLabel(String quality) {
        if (quality == null) {
            return I18n.t("record.quality.high");
        }
        return switch (quality.toUpperCase(java.util.Locale.ROOT)
                .replace("NEAR_LOSSLESS", "LOSSLESS")) {
            case "LOW" -> I18n.t("record.quality.low");
            case "MEDIUM" -> I18n.t("record.quality.medium");
            case "HIGH" -> I18n.t("record.quality.high");
            case "ULTRA" -> I18n.t("record.quality.ultra");
            case "LOSSLESS" -> I18n.t("record.quality.nearLossless");
            default -> I18n.t("record.quality.high");
        };
    }

    private static String resolutionLabelFor(String cap) {
        if (cap == null || cap.isBlank() || cap.equalsIgnoreCase("source")) {
            return I18n.t("record.resolution.source");
        }
        return cap;
    }

    /** Enables or disables the record button (used while recording). */
    public void setRecordingActive(boolean active) {
        recordButton.setDisable(active);
        recordButton.setText(active ? I18n.t("record.recording") : I18n.t("record.start"));
    }
}

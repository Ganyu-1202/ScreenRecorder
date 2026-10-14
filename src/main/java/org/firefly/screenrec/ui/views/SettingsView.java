package org.firefly.screenrec.ui.views;

import org.firefly.screenrec.core.AppContext;
import org.firefly.screenrec.core.i18n.Lang;
import org.firefly.screenrec.core.recorder.CaptureBackend;
import org.firefly.screenrec.core.recorder.EncoderBackend;
import org.firefly.screenrec.core.recorder.FfmpegDownloader;
import org.firefly.screenrec.ui.components.SegmentedControl;
import org.firefly.screenrec.ui.components.ToggleSwitch;
import org.firefly.screenrec.ui.components.UiFactory;
import org.firefly.screenrec.ui.i18n.I18n;
import org.firefly.screenrec.ui.theme.Icons;
import org.firefly.screenrec.ui.theme.ThemeManager;
import org.firefly.screenrec.util.FxScheduler;
import org.firefly.screenrec.util.Log;
import org.firefly.screenrec.util.PlatformUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Preferences: appearance, output naming, capture behaviour, audio levels and
 * the FFmpeg engine (detection, download, manual override).
 */
public final class SettingsView extends ScrollPane {

    private final AppContext context;
    private final Consumer<String> onNotify;
    private final ThemeManager themeManager;
    private final Runnable onLanguageChanged;

    private Runnable onShortcutsChanged;
    public void setOnShortcutsChanged(Runnable callback) { onShortcutsChanged = callback; }
    private VBox content;

    private Label ffmpegStatusLabel;
    private Label ffmpegDetailLabel;
    private ProgressBar downloadProgress;
    private Button downloadButton;
    private Button detectButton;

    public SettingsView(AppContext context, ThemeManager themeManager,
                        Consumer<String> onNotify) {
        this(context, themeManager, onNotify, null);
    }

    public SettingsView(AppContext context, ThemeManager themeManager,
                        Consumer<String> onNotify, Runnable onLanguageChanged) {
        this.context = context;
        this.themeManager = themeManager;
        this.onNotify = onNotify;
        this.onLanguageChanged = onLanguageChanged;

        getStyleClass().add("app-content");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        content = new VBox(20);
        content.setPadding(new Insets(28, 32, 40, 32));
        content.getChildren().addAll(
                buildHeader(),
                buildAppearanceSection(),
                buildShortcutsSection(),
                buildBehaviorSection(),
                buildAudioSection(),
                buildNamingSection(),
                buildEngineSection(),
                buildAboutSection());

        setContent(content);
        refreshFfmpegStatus();
    }

    private Node buildHeader() {
        VBox header = new VBox(5);
        header.getChildren().addAll(
                UiFactory.title(I18n.t("settings.title")),
                UiFactory.secondary(I18n.t("settings.subtitle")));
        return header;
    }

    // ------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------

    private Node buildAppearanceSection() {
        VBox card = sectionCard(I18n.t("settings.appearance"),
                I18n.t("settings.appearance.desc"));

        SegmentedControl<String> themeSwitch = new SegmentedControl<String>()
                .add("dark", I18n.t("settings.theme.dark"))
                .add("light", I18n.t("settings.theme.light"));
        themeSwitch.setValue(normalizeTheme(context.settings().getTheme()));
        themeSwitch.valueProperty().addListener((obs, old, value) -> {
            if (value == null || value.equals(old)) {
                return;
            }
            context.settings().setTheme(value);
            context.saveSettings();
            if (getScene() != null) {
                themeManager.apply(getScene(), ThemeManager.Theme.fromId(value));
            }
        });

        VBox themeRow = new VBox(8);
        themeRow.getChildren().addAll(UiFactory.caption(I18n.t("settings.theme")), themeSwitch);

        // Language picker — switching rebuilds the view tree, which is cheap
        // and avoids tracking every label individually.
        SegmentedControl<String> languageSwitch = new SegmentedControl<String>();
        for (Lang lang : Lang.values()) {
            languageSwitch.add(lang.id(), lang.displayName());
        }
        languageSwitch.setValue(Lang.fromId(context.settings().getLanguage()).id());
        languageSwitch.valueProperty().addListener((obs, old, value) -> {
            if (value == null || value.equals(old)) {
                return;
            }
            Lang lang = Lang.fromId(value);
            I18n.setLanguage(lang);
            context.settings().setLanguage(lang.id());
            context.saveSettings();
            if (onLanguageChanged != null) {
                onLanguageChanged.run();
            }
        });

        VBox languageRow = new VBox(8);
        languageRow.getChildren().addAll(
                UiFactory.caption(I18n.t("settings.language")), languageSwitch,
                UiFactory.caption(I18n.t("settings.language.desc")));

        card.getChildren().addAll(themeRow, buildAccentPicker(), languageRow);
        return card;
    }

    private Node buildAccentPicker() {
        VBox panel = new VBox(12);
        panel.getChildren().addAll(UiFactory.caption(I18n.t("settings.accent")),
                UiFactory.secondary(I18n.t("settings.accent.desc")));
        javafx.scene.layout.FlowPane swatches = new javafx.scene.layout.FlowPane(10, 10);
        String[] colors = {"#ff4d5e", "#f97316", "#eab308", "#22c55e", "#06b6d4", "#3b82f6", "#8b5cf6", "#ec4899"};
        Slider[] rgb = new Slider[3];
        Label hex = new Label();
        hex.getStyleClass().add("text-mono");
        Button preview = new Button(I18n.t("settings.accent.preview"));
        preview.getStyleClass().add("button-primary");
        preview.setMouseTransparent(true);
        HBox previewRow = new HBox(16, preview, hex);
        previewRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.paint.Color initial = javafx.scene.paint.Color.web(context.settings().getAccentColor());
        double[] channels = {initial.getRed()*255, initial.getGreen()*255, initial.getBlue()*255};
        String[] names = {"R", "G", "B"};
        Runnable update = () -> {
            String value = String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                    Math.round(rgb[0].getValue()), Math.round(rgb[1].getValue()), Math.round(rgb[2].getValue()));
            hex.setText(value.toUpperCase(java.util.Locale.ROOT));
            context.settings().setAccentColor(value);
            ThemeManager.setAccentColor(value);
            if (getScene() != null) themeManager.applyCurrent(getScene());
            for (Node node : swatches.getChildren()) {
                Button button = (Button) node;
                button.setText(value.equals(button.getUserData()) ? "✓" : "");
            }
        };
        VBox channelsBox = new VBox(8);
        for (int i = 0; i < 3; i++) {
            Slider slider = new Slider(0, 255, channels[i]);
            slider.getStyleClass().add("rgb-" + names[i].toLowerCase(java.util.Locale.ROOT));
            slider.setBlockIncrement(1);
            slider.setMaxWidth(Double.MAX_VALUE);
            rgb[i] = slider;
            javafx.scene.control.Spinner<Integer> number = new javafx.scene.control.Spinner<>(0, 255, (int)Math.round(channels[i]));
            number.setPrefWidth(90);
            // Non-editable spinner keeps entered values valid; arrows provide exact steps.
            number.valueProperty().addListener((o, old, value) -> {
                slider.setValue(value);
                if (!slider.isValueChanging()) context.saveSettings();
            });
            slider.valueProperty().addListener((o, old, value) -> {
                number.getValueFactory().setValue((int)Math.round(value.doubleValue()));
                if (rgb[2] != null) {
                    update.run();
                    if (!slider.isValueChanging()) context.saveSettings();
                }
            });
            slider.valueChangingProperty().addListener((o, old, dragging) -> {
                if (!dragging) context.saveSettings();
            });
            HBox row = new HBox(12, new Label(names[i]), slider, number);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(slider, Priority.ALWAYS);
            channelsBox.getChildren().add(row);
        }
        for (String color : colors) {
            Button swatch = new Button();
            swatch.setUserData(color);
            swatch.getStyleClass().add("accent-swatch");
            javafx.scene.paint.Color c = javafx.scene.paint.Color.web(color);
            String ink = c.getBrightness() > 0.8 ? "#101319" : "white";
            swatch.setStyle("-fx-background-color: " + color + "; -fx-text-fill: " + ink + ";");
            UiFactory.installTooltip(swatch, color.toUpperCase());
            swatch.setOnAction(e -> {
                rgb[0].setValue(c.getRed()*255);
                rgb[1].setValue(c.getGreen()*255);
                rgb[2].setValue(c.getBlue()*255);
                context.saveSettings();
            });
            swatches.getChildren().add(swatch);
        }
        update.run();
        panel.getChildren().addAll(swatches, channelsBox, previewRow);
        return panel;
    }

    private Node buildShortcutsSection() {
        VBox card = sectionCard(I18n.t("settings.shortcuts"), I18n.t("settings.shortcuts.desc"));
        java.util.Map<String, String> keys = new java.util.LinkedHashMap<>(
                org.firefly.screenrec.ui.Shortcuts.resolve(context.settings().getShortcuts()));
        java.util.Map<String, TextField> fields = new java.util.LinkedHashMap<>();
        for (String action : keys.keySet()) {
            TextField field = new TextField(javafx.scene.input.KeyCombination.valueOf(keys.get(action)).getDisplayText());
            field.setEditable(false);
            field.setPrefWidth(210);
            field.getStyleClass().add("shortcut-input");
            field.setPromptText(I18n.t("settings.shortcuts.capture"));
            field.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                e.consume();
                if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) { card.requestFocus(); return; }
                if (e.getCode().isModifierKey() || e.getCode() == javafx.scene.input.KeyCode.UNDEFINED) return;
                var combination = new javafx.scene.input.KeyCodeCombination(e.getCode(),
                        e.isShiftDown() ? javafx.scene.input.KeyCombination.ModifierValue.DOWN : javafx.scene.input.KeyCombination.ModifierValue.UP,
                        e.isControlDown() ? javafx.scene.input.KeyCombination.ModifierValue.DOWN : javafx.scene.input.KeyCombination.ModifierValue.UP,
                        e.isAltDown() ? javafx.scene.input.KeyCombination.ModifierValue.DOWN : javafx.scene.input.KeyCombination.ModifierValue.UP,
                        e.isMetaDown() ? javafx.scene.input.KeyCombination.ModifierValue.DOWN : javafx.scene.input.KeyCombination.ModifierValue.UP,
                        javafx.scene.input.KeyCombination.ModifierValue.UP);
                if (org.firefly.screenrec.ui.Shortcuts.conflicts(keys, action, combination)) {
                    notify(I18n.t("settings.shortcuts.conflict")); return;
                }
                keys.put(action, combination.getName());
                field.setText(combination.getDisplayText());
                context.settings().setShortcuts(keys);
                context.saveSettings();
                if (onShortcutsChanged != null) onShortcutsChanged.run();
                card.requestFocus();
            });
            fields.put(action, field);
            HBox row = new HBox(16, UiFactory.body(I18n.t("settings.shortcuts." + action)), UiFactory.hSpacer(), field);
            row.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(row);
        }
        Button reset = new Button(I18n.t("settings.shortcuts.reset"));
        reset.setOnAction(e -> {
            keys.clear(); keys.putAll(org.firefly.screenrec.ui.Shortcuts.defaults());
            fields.forEach((action, field) -> field.setText(javafx.scene.input.KeyCombination.valueOf(keys.get(action)).getDisplayText()));
            context.settings().setShortcuts(keys); context.saveSettings();
            if (onShortcutsChanged != null) onShortcutsChanged.run();
        });
        card.setFocusTraversable(true);
        card.getChildren().add(reset);
        return card;
    }

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    private Node buildBehaviorSection() {
        VBox card = sectionCard(I18n.t("settings.behavior"),
                I18n.t("settings.behavior.desc"));

        ToggleSwitch cursorToggle = new ToggleSwitch(context.settings().isCaptureCursor());
        cursorToggle.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setDrawMouseCursor(on);
            context.saveSettings();
        });

        ToggleSwitch clickToggle = new ToggleSwitch(context.settings().isHighlightClicks());
        clickToggle.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setHighlightClicks(on);
            context.saveSettings();
        });

        CheckBox barToggle = new CheckBox(I18n.t("settings.controlBar"));
        barToggle.setSelected(context.settings().isShowControlBar());
        barToggle.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setShowControlBar(on);
            context.saveSettings();
        });

        CheckBox excludeBar = new CheckBox(I18n.t("settings.controlBar.exclude"));
        excludeBar.setSelected(context.settings().isExcludeControlBarFromCapture());
        excludeBar.disableProperty().bind(barToggle.selectedProperty().not());
        excludeBar.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setExcludeControlBarFromCapture(on);
            context.saveSettings();
        });

        CheckBox minimizeToggle = new CheckBox(I18n.t("settings.minimizeOnRecording"));
        minimizeToggle.setSelected(context.settings().isMinimizeOnRecording());
        minimizeToggle.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setMinimizeOnRecording(on);
            context.saveSettings();
        });

        // UI expresses transparency (0 = opaque), persisted value is opacity.
        Slider transparency = new Slider(0, 90,
                Math.round((1 - context.settings().getControlBarOpacity()) * 100));
        transparency.setBlockIncrement(5);
        transparency.setMajorTickUnit(25);
        transparency.setShowTickMarks(true);
        transparency.setMaxWidth(Double.MAX_VALUE);
        Label percentage = new Label();
        percentage.textProperty().bind(transparency.valueProperty().asString("%.0f%%"));
        percentage.setMinWidth(45);
        HBox opacityRow = new HBox(12, transparency, percentage);
        opacityRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(transparency, Priority.ALWAYS);
        VBox opacityBox = new VBox(8, UiFactory.caption(I18n.t("settings.controlBar.transparency")),
                opacityRow, UiFactory.caption(I18n.t("settings.controlBar.transparency.desc")),
                excludeBar, UiFactory.caption(I18n.t("settings.controlBar.exclude.desc")));
        opacityBox.disableProperty().bind(barToggle.selectedProperty().not());
        transparency.valueProperty().addListener((obs, old, value) ->
                context.settings().setControlBarOpacity(1 - Math.round(value.doubleValue()) / 100.0));
        transparency.valueChangingProperty().addListener((obs, old, changing) -> {
            if (!changing) context.saveSettings();
        });
        transparency.valueProperty().addListener((obs, old, value) -> {
            if (!transparency.isValueChanging()) context.saveSettings();
        });

        ComboBox<Integer> countdownCombo = new ComboBox<>();
        countdownCombo.getItems().setAll(0, 3, 5, 10);
        countdownCombo.setValue(context.settings().getCountdownSeconds());
        countdownCombo.setPrefWidth(140);
        countdownCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setCountdownSeconds(val);
                context.saveSettings();
            }
        });

        VBox countdownBox = new VBox(8);
        countdownBox.getChildren().addAll(
                UiFactory.caption(I18n.t("settings.countdown")),
                countdownCombo);

        card.getChildren().addAll(
                ToggleSwitch.labeledRow(I18n.t("settings.cursor"),
                        I18n.t("settings.cursor.desc"), cursorToggle),
                ToggleSwitch.labeledRow(I18n.t("settings.clicks"),
                        I18n.t("settings.clicks.desc"), clickToggle),
                new VBox(5, barToggle, UiFactory.caption(I18n.t("settings.controlBar.desc"))),
                opacityBox,
                new VBox(5, minimizeToggle, UiFactory.caption(I18n.t("settings.minimizeOnRecording.desc"))),
                countdownBox);
        return card;
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    private Node buildAudioSection() {
        VBox card = sectionCard(I18n.t("settings.audio"),
                I18n.t("settings.audio.desc"));

        Slider micSlider = new Slider(0, 2, context.settings().getMicrophoneVolume());
        micSlider.setShowTickMarks(true);
        micSlider.setMajorTickUnit(0.5);
        micSlider.setBlockIncrement(0.1);
        micSlider.setMaxWidth(Double.MAX_VALUE);
        Label micValue = UiFactory.caption(gainLabel(context.settings().getMicrophoneVolume()));
        micSlider.valueProperty().addListener((obs, old, val) ->
                micValue.setText(gainLabel(val.doubleValue())));
        micSlider.setOnMouseReleased(e -> {
            context.settings().setMicGain(micSlider.getValue());
            context.saveSettings();
        });

        Slider sysSlider = new Slider(0, 2, context.settings().getSystemVolume());
        sysSlider.setShowTickMarks(true);
        sysSlider.setMajorTickUnit(0.5);
        sysSlider.setBlockIncrement(0.1);
        sysSlider.setMaxWidth(Double.MAX_VALUE);
        Label sysValue = UiFactory.caption(gainLabel(context.settings().getSystemVolume()));
        sysSlider.valueProperty().addListener((obs, old, val) ->
                sysValue.setText(gainLabel(val.doubleValue())));
        sysSlider.setOnMouseReleased(e -> {
            context.settings().setSystemGain(sysSlider.getValue());
            context.saveSettings();
        });

        ToggleSwitch monoToggle = new ToggleSwitch(context.settings().isMixToMono());
        monoToggle.selectedProperty().addListener((obs, old, on) -> {
            context.settings().setMixToMono(on);
            context.saveSettings();
        });

        card.getChildren().addAll(
                labeledSlider(I18n.t("settings.micVolume"), micSlider, micValue),
                labeledSlider(I18n.t("settings.sysVolume"), sysSlider, sysValue),
                ToggleSwitch.labeledRow(I18n.t("settings.mono"),
                        I18n.t("settings.mono.desc"),
                        monoToggle));
        return card;
    }

    private Node labeledSlider(String label, Slider slider, Label value) {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(UiFactory.caption(label), UiFactory.hSpacer(), value);
        value.setMinWidth(52);
        value.setAlignment(Pos.CENTER_RIGHT);
        slider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(slider, Priority.ALWAYS);
        VBox box = new VBox(6, header, slider);
        return box;
    }

    // ------------------------------------------------------------------
    // Naming & destination
    // ------------------------------------------------------------------

    private Node buildNamingSection() {
        VBox card = sectionCard(I18n.t("settings.saving"),
                I18n.t("settings.saving.desc"));

        Label dirLabel = UiFactory.secondary(context.outputDirectory().toString());
        dirLabel.setWrapText(true);
        UiFactory.installTooltip(dirLabel, context.outputDirectory().toString());

        Button changeDir = UiFactory.button(I18n.t("settings.chooseFolder"), Icons.FOLDER,
                "button-subtle", "button-sm");
        changeDir.setOnAction(e -> chooseDirectory(dirLabel));

        TextField prefixField = new TextField(context.settings().getFilenamePrefix());
        prefixField.setPromptText(I18n.t("record.title"));
        prefixField.setPrefWidth(240);
        prefixField.textProperty().addListener((obs, old, val) -> {
            context.settings().setFileNameTemplate(safePrefixStem(val) + "_%Y%m%d_%H%M%S");
            context.saveSettings();
        });
        Label preview = UiFactory.caption("");
        Runnable updatePreview = () -> preview.setText(
                I18n.t("settings.example") + ": "
                        + safePrefix(prefixField.getText()) + "_2026-10-11_18-25-00.mp4");
        updatePreview.run();
        prefixField.textProperty().addListener((obs, old, val) -> updatePreview.run());

        HBox dirRow = new HBox(10);
        dirRow.setAlignment(Pos.CENTER_LEFT);
        dirRow.getChildren().addAll(dirLabel, UiFactory.hSpacer(), changeDir);

        card.getChildren().addAll(
                new VBox(8, UiFactory.caption(I18n.t("settings.saveTo")), dirRow),
                new VBox(8, UiFactory.caption(I18n.t("settings.namePrefix")), prefixField, preview));
        return card;
    }

    private void chooseDirectory(Label label) {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(I18n.t("settings.chooseFolder.desc"));
        File current = context.outputDirectory().toFile();
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) {
            context.settings().setOutputDirectory(chosen.getAbsolutePath());
            context.saveSettings();
            label.setText(chosen.getAbsolutePath());
            UiFactory.installTooltip(label, chosen.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------

    private Node buildEngineSection() {
        VBox card = sectionCard(I18n.t("settings.engine"),
                I18n.t("settings.engine.desc"));

        ffmpegStatusLabel = new Label();
        ffmpegStatusLabel.getStyleClass().add("status-headline");
        ffmpegDetailLabel = UiFactory.caption("");
        ffmpegDetailLabel.setWrapText(true);

        downloadProgress = new ProgressBar(0);
        downloadProgress.setMaxWidth(Double.MAX_VALUE);
        downloadProgress.setVisible(false);
        downloadProgress.setManaged(false);

        downloadButton = UiFactory.button(I18n.t("settings.engine.download"), Icons.DOWNLOAD, "button-primary");
        downloadButton.setOnAction(e -> startDownload());

        detectButton = UiFactory.button(I18n.t("common.detect"), Icons.REFRESH, "button-subtle");
        detectButton.setOnAction(e -> redetect());

        Button browseButton = UiFactory.button(I18n.t("settings.engine.custom"), Icons.FOLDER, "button-subtle");
        browseButton.setOnAction(e -> chooseCustomFfmpeg());

        HBox actions = new HBox(10, downloadButton, detectButton, browseButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        // Hardware encoder preference.
        ComboBox<EncoderBackend> encoderCombo = new ComboBox<>();
        List<EncoderBackend> encoders = new java.util.ArrayList<>();
        encoders.add(EncoderBackend.AUTO);
        encoders.addAll(context.usableEncoders());
        encoderCombo.getItems().setAll(encoders);
        encoderCombo.setValue(context.settings().resolveEncoder());
        encoderCombo.setPrefWidth(280);
        encoderCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setEncoder(val.name());
                context.saveSettings();
            }
        });

        ComboBox<CaptureBackend> captureCombo = new ComboBox<>();
        List<CaptureBackend> backends = new java.util.ArrayList<>();
        backends.add(CaptureBackend.AUTO);
        backends.addAll(context.availableCaptureBackends());
        captureCombo.getItems().setAll(backends);
        captureCombo.setValue(context.settings().resolveCaptureBackend());
        captureCombo.setPrefWidth(280);
        captureCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                context.settings().setCaptureBackend(val.name());
                context.saveSettings();
            }
        });

        HBox combos = new HBox(18,
                new VBox(6, UiFactory.caption(I18n.t("settings.engine.encoder")), encoderCombo),
                new VBox(6, UiFactory.caption(I18n.t("settings.engine.captureMethod")), captureCombo));
        combos.setAlignment(Pos.TOP_LEFT);

        VBox statusBox = new VBox(6, ffmpegStatusLabel, ffmpegDetailLabel);
        card.getChildren().addAll(statusBox, downloadProgress, actions, UiFactory.divider(), combos);
        return card;
    }

    private Node buildAboutSection() {
        VBox card = sectionCard(I18n.t("common.about"), null);
        Label version = UiFactory.secondary("ScreenRecorder 1.0.0  ·  "
                + System.getProperty("os.name") + "  ·  Java "
                + System.getProperty("java.version"));
        Label paths = UiFactory.caption(
                I18n.t("settings.title") + ": " + PlatformUtils.settingsFile() + "\n"
                        + I18n.t("settings.engine.cache") + ": " + PlatformUtils.ffmpegCacheDir());
        paths.setWrapText(true);

        Button openSettings = UiFactory.button(I18n.t("settings.engine.openData"), Icons.FOLDER,
                "button-subtle", "button-sm");
        openSettings.setOnAction(e -> FxScheduler.background(() ->
                PlatformUtils.openFolder(PlatformUtils.appDataDir())));

        card.getChildren().addAll(version, paths, openSettings);
        return card;
    }

    // ------------------------------------------------------------------
    // FFmpeg management
    // ------------------------------------------------------------------

    /** Recomputes the engine status line from current capability probes. */
    public void refreshFfmpegStatus() {
        boolean available = context.hasFfmpeg();
        if (available) {
            ffmpegStatusLabel.setText(I18n.t("settings.engine.ready"));
            ffmpegStatusLabel.getStyleClass().removeAll("status-error", "status-warning");
            if (!ffmpegStatusLabel.getStyleClass().contains("status-ok")) {
                ffmpegStatusLabel.getStyleClass().add("status-ok");
            }
            downloadButton.setVisible(false);
            downloadButton.setManaged(false);

            String detail = context.ffmpegPath().map(Path::toString)
                    .orElse(I18n.t("common.unknown"));
            int encoderCount = context.usableEncoders().size();
            int deviceCount = context.audioDevices().size();
            ffmpegDetailLabel.setText(detail + "\n"
                    + encoderCount + I18n.t("settings.engine.encoders") + deviceCount
                    + I18n.t("settings.engine.devices"));
        } else {
            ffmpegStatusLabel.setText(I18n.t("settings.engine.notFound"));
            ffmpegStatusLabel.getStyleClass().removeAll("status-ok", "status-warning");
            if (!ffmpegStatusLabel.getStyleClass().contains("status-error")) {
                ffmpegStatusLabel.getStyleClass().add("status-error");
            }
            downloadButton.setVisible(true);
            downloadButton.setManaged(true);
            ffmpegDetailLabel.setText(
                    I18n.t("settings.engine.needFfmpeg")
                            + I18n.t("settings.engine.autoHint")
                            + I18n.t("settings.engine.autoHint2"));
        }
    }

    private void redetect() {
        detectButton.setDisable(true);
        ffmpegDetailLabel.setText(I18n.t("settings.engine.scanning"));
        FxScheduler.background(() -> {
            context.resolveFfmpeg();
            context.refreshAudioDevices();
            FxScheduler.run(() -> {
                detectButton.setDisable(false);
                refreshFfmpegStatus();
                notify(context.hasFfmpeg()
                        ? I18n.t("settings.engine.detected")
                        : I18n.t("settings.engine.stillNotFound"));
            });
        });
    }

    private void chooseCustomFfmpeg() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("settings.engine.pickTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                PlatformUtils.isWindows() ? "Executable (*.exe)" : I18n.t("settings.engine.exeFilter"),
                PlatformUtils.isWindows() ? "*.exe" : "*"));

        File chosen = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen == null) {
            return;
        }

        Path path = chosen.toPath();
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.startsWith("ffmpeg")) {
            notify(I18n.t("settings.engine.pickHint"));
            return;
        }
        Path parent = path.getParent();

        context.settings().setFfmpegPath(path.toString());
        if (parent != null) {
            Path probe = parent.resolve(PlatformUtils.isWindows() ? "ffprobe.exe" : "ffprobe");
            if (java.nio.file.Files.isRegularFile(probe)) {
                context.settings().setFfprobePath(probe.toString());
            }
        }
        context.saveSettings();
        redetect();
    }

    private void startDownload() {
        downloadButton.setDisable(true);
        downloadButton.setText(I18n.t("settings.engine.downloading"));
        downloadProgress.setVisible(true);
        downloadProgress.setManaged(true);
        downloadProgress.setProgress(-1);

        FfmpegDownloader.ProgressListener listener = (downloaded, total) ->
                FxScheduler.run(() -> {
                    if (total > 0) {
                        downloadProgress.setProgress((double) downloaded / total);
                        ffmpegDetailLabel.setText(I18n.t("settings.engine.downloading") + " "
                                + PlatformUtils.formatBytes(downloaded) + " / "
                                + PlatformUtils.formatBytes(total));
                    }
                });

        FxScheduler.background(() -> {
            boolean ok = false;
            String error = null;
            try {
                ok = context.downloadFfmpeg(listener);
            } catch (Exception e) {
                error = e.getMessage();
                Log.warn("FFmpeg download failed: " + e.getMessage());
            }
            boolean success = ok;
            String failureReason = error;
            FxScheduler.run(() -> {
                downloadButton.setDisable(false);
                downloadButton.setText("Download FFmpeg");
                downloadProgress.setVisible(false);
                downloadProgress.setManaged(false);
                refreshFfmpegStatus();
                if (success) {
                    notify(I18n.t("settings.engine.installed"));
                } else {
                    notify(failureReason == null
                            ? I18n.t("settings.engine.downloadFailed")
                            : I18n.t("settings.engine.downloadFailedDetail") + failureReason);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private VBox sectionCard(String title, String description) {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));

        VBox header = new VBox(3);
        header.getChildren().add(UiFactory.subheading(title));
        if (description != null) {
            Label desc = UiFactory.secondary(description);
            desc.setWrapText(true);
            header.getChildren().add(desc);
        }
        card.getChildren().add(header);
        return card;
    }

    private void notify(String message) {
        if (onNotify != null) {
            onNotify.accept(message);
        }
    }

    private static String percentLabel(int value) {
        return value + "%";
    }

    /** Formats a 0–2 gain multiplier as a human-readable percentage. */
    private static String gainLabel(double gain) {
        return Math.round(gain * 100) + "%";
    }

    private static String safePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Recording";
        }
        return raw.replaceAll("[\\\\/:*?\"<>|%]", "_").trim();
    }

    /** Sanitised prefix suitable for embedding in a strftime template stem. */
    private static String safePrefixStem(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Recording";
        }
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|%]", "").trim().replace(' ', '_');
        return cleaned.isBlank() ? "Recording" : cleaned;
    }

    private static String normalizeTheme(String theme) {
        return "light".equalsIgnoreCase(theme) ? "light" : "dark";
    }
}

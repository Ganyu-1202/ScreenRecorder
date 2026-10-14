package org.firefly.screenrec.ui.theme;

import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Local SVG assets: path-only files, read without resolving XML/DTD entities. */
public final class AppIcons {
    private AppIcons() {}
    public static javafx.scene.layout.Region vector(String name, double size, boolean themed) {
        Group group = new Group();
        try (var stream = AppIcons.class.getResourceAsStream("/org/firefly/screenrec/icons/" + name + ".svg")) {
            if (stream == null) throw new IllegalStateException("Missing icon: " + name);
            String svg = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var paths = Pattern.compile("<path\\b[^>]*>").matcher(svg);
            while (paths.find()) {
                String tag = paths.group();
                var d = Pattern.compile("\\bd=\"([^\"]+)\"").matcher(tag);
                if (!d.find()) continue;
                SVGPath path = new SVGPath();
                path.setContent(d.group(1));
                var fill = Pattern.compile("\\bfill=\"([^\"]+)\"").matcher(tag);
                path.setFill(fill.find() ? Color.web(fill.group(1)) : Color.BLACK);
                if (themed) path.getStyleClass().add("nav-icon");
                group.getChildren().add(path);
            }
            group.getTransforms().add(new Scale(size / 1024, size / 1024));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read icon: " + name, e);
        }
        // StackPane measures layoutBounds, which exclude a node's own transform.
        // An outer Group measures the transformed child, so layout sees pixels,
        // not the original 1024-unit SVG geometry.
        Group scaledBounds = new Group(group);
        StackPane holder = new StackPane(scaledBounds);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        return holder;
    }
    /** PNG assets for native window/taskbar icons; no FX snapshot needed. */
    public static javafx.scene.image.Image applicationPng(int size) {
        String resource = "/org/firefly/screenrec/icons/screen-recorder-" + size + ".png";
        try (var stream = AppIcons.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing application icon: " + resource);
            var image = new javafx.scene.image.Image(stream);
            if (image.isError()) throw new IllegalStateException("Cannot load application icon: " + resource, image.getException());
            return image;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read application icon: " + resource, e);
        }
    }

    public static void applyWindowIcons(javafx.stage.Stage stage) {
        stage.getIcons().setAll(java.util.stream.IntStream.of(16, 24, 32, 48, 64, 128, 256)
                .mapToObj(AppIcons::applicationPng).toList());
    }

    public static WritableImage applicationImage(int size) {
        var node = vector("icon", size, false);
        node.resize(size, size);
        node.layout();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return node.snapshot(parameters, new WritableImage(size, size));
    }
}

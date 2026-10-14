package org.firefly.screenrec;

/**
 * Plain launcher used by the shaded fat jar.
 *
 * <p>When a JavaFX application is the main class of a fat jar, the JVM refuses to
 * start with the classic "JavaFX runtime components are missing" error. Starting
 * from a non-{@code Application} class sidesteps that check: by the time
 * {@link javafx.application.Application#launch} runs, the JavaFX classes are
 * already on the classpath, so the runtime resolves them normally.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        ScreenRecorderApp.main(args);
    }
}

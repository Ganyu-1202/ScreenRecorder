package org.firefly.screenrec.util;

import javafx.application.Platform;

/**
 * Helpers to hop between background threads and the JavaFX application thread safely.
 */
public final class FxScheduler {

    private FxScheduler() {
    }

    /** True when the current thread is the JavaFX application thread. */
    public static boolean isFxThread() {
        try {
            return Platform.isFxApplicationThread();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Runs {@code action} on the JavaFX thread, immediately when already on it. */
    public static void run(Runnable action) {
        if (isFxThread()) {
            action.run();
            return;
        }
        try {
            Platform.runLater(action);
        } catch (IllegalStateException e) {
            Log.warn("JavaFX toolkit not started; dropping UI update");
        }
    }

    /**
     * Runs {@code action} on a background daemon thread.
     */
    public static void background(Runnable action) {
        Thread t = new Thread(action, "app-bg");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Runs {@code action} on a background daemon thread, then delivers the result
     * back on the JavaFX thread.
     */
    public static <T> void async(java.util.function.Supplier<T> work,
                                 java.util.function.Consumer<T> onSuccess,
                                 java.util.function.Consumer<Throwable> onFailure) {
        background(() -> {
            try {
                T value = work.get();
                if (onSuccess != null) {
                    run(() -> onSuccess.accept(value));
                }
            } catch (Throwable t) {
                Log.error("Background task failed", t);
                if (onFailure != null) {
                    run(() -> onFailure.accept(t));
                }
            }
        });
    }
}

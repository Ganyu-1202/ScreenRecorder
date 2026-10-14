package org.firefly.screenrec.core.recorder;

/**
 * Lifecycle of a recording session.
 *
 * <pre>
 *   IDLE ──start──▶ PREPARING ──▶ COUNTDOWN ──▶ RECORDING ⇄ PAUSED
 *                                                   │
 *                                                stop│
 *                                                   ▼
 *                                              STOPPING ──▶ FINISHED
 *                                                   │
 *                                        any error  ▼
 *                                                 ERROR
 * </pre>
 */
public enum RecordingState {

    IDLE("Ready"),
    PREPARING("Preparing…"),
    COUNTDOWN("Starting…"),
    RECORDING("Recording"),
    PAUSED("Paused"),
    STOPPING("Saving…"),
    FINISHED("Saved"),
    ERROR("Error");

    private final String label;

    RecordingState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True while the encoder process is expected to be running. */
    public boolean isActive() {
        return this == PREPARING || this == COUNTDOWN || this == RECORDING || this == PAUSED;
    }

    /** True when a new recording may be started from this state. */
    public boolean canStart() {
        return this == IDLE || this == FINISHED || this == ERROR;
    }

    public boolean isRecording() {
        return this == RECORDING;
    }
}

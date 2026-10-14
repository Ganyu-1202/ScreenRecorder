package org.firefly.screenrec.ui.theme;

/**
 * Inline SVG path definitions for the UI icon set.
 *
 * <p>Keeping icons as path data avoids shipping image assets, keeps everything
 * crisp at any DPI, and lets icons inherit the current text colour.
 * All paths are drawn on a 24×24 viewbox.
 */
public final class Icons {

    private Icons() {
    }

    /** Record: filled circle. */
    public static final String RECORD =
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z";

    /** Stop: rounded square. */
    public static final String STOP =
            "M7 7h10a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z";

    /** Pause: two bars. */
    public static final String PAUSE =
            "M8 5h3v14H8zM13 5h3v14h-3z";

    /** Play: triangle. */
    public static final String PLAY =
            "M8 5l11 7-11 7z";

    /** Double chevrons distinguish seeking from playback. */
    public static final String SEEK_BACK = "M11 5v14L2 12zM22 5v14l-9-7z";
    public static final String SEEK_FORWARD = "M2 5l9 7-9 7zM13 5l9 7-9 7z";

    /** Library: stacked boxes. */
    public static final String LIBRARY =
            "M4 5h6v14H4zM12 5h8v6h-8zM12 13h8v6h-8z";

    /** Settings: gear. */
    public static final String SETTINGS =
            "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm8.4 4a8.3 8.3 0 0 0-.1-1.3l2-1.6-2-3.4-2.4 1a8.3 8.3 0 "
                    + "0 0-2.2-1.3L15.2 2h-4l-.4 2.4c-.8.3-1.6.7-2.2 1.3l-2.4-1-2 3.4 2 1.6a8.3 8.3 0 0 0 0 2.6l-2 1.6 2 3.4 "
                    + "2.4-1c.7.6 1.4 1 2.2 1.3l.4 2.4h4l.4-2.4a8.3 8.3 0 0 0 2.2-1.3l2.4 1 2-3.4-2-1.6c.1-.4.1-.9.1-1.3z";

    /** Editor: scissors. */
    public static final String EDIT =
            "M9.6 7.5a3 3 0 1 0-1.2 1.6L11 12l-2.6 2.9A3 3 0 1 0 9.6 16.5L12.4 13.4l4.3 4.8 1.5-1.3-4.4-4.9 4.4-4.9-1.5-1.3-4.3 4.8zM6 5.5a1.5 "
                    + "1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm0 10a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z";

    /** Monitor / display. */
    public static final String MONITOR =
            "M3 4h18a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zm4 15h10v2H7z";

    /** Region crop. */
    public static final String CROP =
            "M7 2v5H2v2h5v6H4v2h3v5h2v-5h6v5h2v-5h5v-2h-5V9h5V7h-5V2h-2v5H9V2zm2 7h6v6H9z";

    /** Window. */
    public static final String WINDOW =
            "M3 4h18a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zm1 4v11h16V8z";

    /** Microphone. */
    public static final String MIC =
            "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zm7 9a7 7 0 0 1-6 6.9V22h-2v-3.1A7 7 0 0 1 5 12h2a5 5 0 0 0 10 0z";

    /** Speaker / system audio. */
    public static final String SPEAKER =
            "M4 9h4l5-4v14l-5-4H4zm12.5 3a4.5 4.5 0 0 0-1.6-3.4l1.2-1.6A6.5 6.5 0 0 1 18.5 12a6.5 6.5 0 0 1-2.4 "
                    + "5l-1.2-1.6A4.5 4.5 0 0 0 16.5 12z";

    /** Camera. */
    public static final String CAMERA =
            "M9 4a1 1 0 0 1 .9.6L10.5 6h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4.5zM12 "
                    + "9a4 4 0 1 0 0 8 4 4 0 0 0 0-8z";

    /** Folder. */
    public static final String FOLDER =
            "M3 5h6l2 2h10a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z";

    /** Trash. */
    public static final String TRASH =
            "M9 3h6l1 2h4v2H4V5h4zM6 8h12l-1 12a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1zm3 2v9h2v-9zm4 0v9h2v-9z";

    /** Close / X. */
    public static final String CLOSE =
            "M6 6l1.5-1.5L12 9l4.5-4.5L18 6l-4.5 4.5L18 15l-1.5 1.5L12 12l-4.5 4.5L6 15l4.5-4.5z";

    /** Minimize. */
    public static final String MINIMIZE =
            "M5 11h14v2H5z";

    /** Maximize. */
    public static final String MAXIMIZE =
            "M5 5h14v14H5zm2 2v10h10V7z";

    /** Restore. */
    public static final String RESTORE =
            "M8 3h13v13h-3v-2h1V5H9v1H7V3zm-5 5h13v13H3zm2 2v9h9V10z";

    /** Check. */
    public static final String CHECK =
            "M9.5 17.5l-5-5 1.5-1.5 3.5 3.5 9-9L20 7z";

    /** Info. */
    public static final String INFO =
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2zm0-8h-2V7h2z";

    /** Warning triangle. */
    public static final String WARNING =
            "M12 2l10 18H2zm1 12h-2v-5h2zm0 4h-2v-2h2z";

    /** Error circle. */
    public static final String ERROR =
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-2h2zm0-4h-2V7h2z";

    /** Expand / fullscreen. */
    public static final String EXPAND =
            "M4 4h7v2H6v5H4zm16 0v7h-2V6h-5V4zM4 13h2v5h5v2H4zm14 0h2v7h-7v-2h5z";

    /** Download. */
    public static final String DOWNLOAD =
            "M12 3v9l3.5-3.5 1.4 1.4L12 15.8l-4.9-4.9 1.4-1.4L12 12V3zM5 18h14v2H5z";

    /** Refresh. */
    public static final String REFRESH =
            "M12 4a8 8 0 0 1 7.4 5H22l-4 4-3.9-4h2.8A6 6 0 1 0 18 14h2a8 8 0 1 1-8-10z";

    /** Drag handle. */
    public static final String DRAG =
            "M9 5h2v2H9zM13 5h2v2h-2zM9 9h2v2H9zM13 9h2v2h-2zM9 13h2v2H9zM13 13h2v2h-2z";

    /** Copy. */
    public static final String COPY =
            "M8 2h11a1 1 0 0 1 1 1v13h-2V4H8zM4 6h11a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z";

    /** Pencil / rename. */
    public static final String RENAME =
            "M4 17.2L15.4 5.8l2.8 2.8L6.8 20H4zM17 4.2l1.8-1.8a1 1 0 0 1 1.4 0l1.4 1.4a1 1 0 0 1 0 1.4L19.8 7z";

    /** Eye. */
    public static final String EYE =
            "M12 5c5 0 9 4.5 10 7-1 2.5-5 7-10 7S3 14.5 2 12c1-2.5 5-7 10-7zm0 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z";

    /** Clock / timer. */
    public static final String CLOCK =
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 5v5.6l4 2.3-1 1.7-5-2.9V7z";

    /** Sparkle / auto. */
    public static final String SPARKLE =
            "M12 2l1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8zM19 15l.9 2.6L22.5 18l-2.6.9L19 21l-.9-2.1L15.5 18l2.6-.4z";
}

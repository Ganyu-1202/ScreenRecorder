package org.firefly.screenrec.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Tiny dependency-free logger with an in-memory ring buffer.
 *
 * <p>Two destinations:
 * <ul>
 *   <li>stdout, for development</li>
 *   <li>an optional in-memory buffer, so the UI can show a diagnostics log</li>
 * </ul>
 * Optionally mirrors everything to {@code ~/.screenrecorder/logs/session.log}.
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int BUFFER_LIMIT = 500;

    private static final Queue<String> BUFFER = new ConcurrentLinkedQueue<>();
    private static final Object FILE_LOCK = new Object();

    private static volatile boolean fileLogging = false;
    private static volatile Path logFile;
    private static volatile Consumer<String> listener;

    private Log() {
    }

    /**
     * Initialises logging for an application run: enables the session log file
     * and flushes a startup banner. Safe to call more than once.
     */
    public static void init() {
        if (!fileLogging) {
            enableFileLogging();
        }
        if (logFile != null) {
            info("Log file: " + logFile);
        }
    }

    /** The active log file, or null when file logging is disabled. */
    public static Path logFile() {
        return logFile;
    }

    /** Enables writing to {@code ~/.screenrecorder/logs/session.log}. */
    public static void enableFileLogging() {
        try {
            Path dir = PlatformUtils.appDataDir().resolve("logs");
            Files.createDirectories(dir);
            logFile = dir.resolve("session.log");
            fileLogging = true;
        } catch (IOException e) {
            System.err.println("[Log] Could not enable file logging: " + e.getMessage());
        }
    }

    /** Registers a listener invoked with every new line (used by the UI log view). */
    public static void setListener(Consumer<String> l) {
        listener = l;
    }

    public static void info(String message) {
        log("INFO", message, null);
    }

    public static void info(String format, Object... args) {
        log("INFO", format(format, args), null);
    }

    public static void warn(String message) {
        log("WARN", message, null);
    }

    public static void warn(String message, Throwable t) {
        log("WARN", message, t);
    }

    public static void error(String message) {
        log("ERROR", message, null);
    }

    public static void error(String message, Throwable t) {
        log("ERROR", message, t);
    }

    public static void debug(String message) {
        if (Boolean.getBoolean("screenrec.debug")) {
            log("DEBUG", message, null);
        }
    }

    /** Snapshot of the recent in-memory log lines. */
    public static String dump() {
        return String.join(System.lineSeparator(), BUFFER);
    }

    private static String format(String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            return template + " " + java.util.Arrays.toString(args);
        }
    }

    private static void log(String level, String message, Throwable t) {
        String line = "[" + LocalDateTime.now().format(TIME) + "] [" + level + "] " + message;
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            line = line + System.lineSeparator() + sw;
        }

        System.out.println(line);

        BUFFER.add(line);
        while (BUFFER.size() > BUFFER_LIMIT) {
            BUFFER.poll();
        }

        Consumer<String> l = listener;
        if (l != null) {
            try {
                l.accept(line);
            } catch (RuntimeException ignored) {
                // a broken UI listener must never break logging
            }
        }

        if (fileLogging && logFile != null) {
            synchronized (FILE_LOCK) {
                try {
                    Files.writeString(logFile, line + System.lineSeparator(),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // best effort only
                }
            }
        }
    }
}

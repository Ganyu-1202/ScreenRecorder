package org.firefly.screenrec.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thin wrapper around {@link ProcessBuilder} that:
 * <ul>
 *   <li>passes arguments as a list (no shell quoting / injection hazards),</li>
 *   <li>pumps stdout and stderr on daemon threads,</li>
 *   <li>supports non-blocking start with callbacks,</li>
 *   <li>supports a one-shot blocking run with a timeout.</li>
 * </ul>
 */
public final class ProcessRunner {

    private ProcessRunner() {
    }

    /** Result of a blocking one-shot execution. */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    /** Handle for a long-running background process. */
    public static final class Handle {

        private final Process process;
        private final String description;
        private final List<Thread> pumps = new ArrayList<>();
        private volatile boolean stopRequested;

        private Handle(Process process, String description) {
            this.process = process;
            this.description = description;
        }

        public Process process() {
            return process;
        }

        public long pid() {
            return process.pid();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public String description() {
            return description;
        }

        public OutputStream stdin() {
            return process.getOutputStream();
        }

        /** Writes a line to the process stdin, flushing immediately. Useful for FFmpeg commands. */
        public boolean writeLine(String line) {
            try {
                PrintWriter w = new PrintWriter(process.getOutputStream());
                w.println(line);
                w.flush();
                return !w.checkError();
            } catch (RuntimeException e) {
                return false;
            }
        }

        /**
         * Stops the process cleanly and waits up to {@code graceMillis} for it to
         * finalize its output. Falls back to a hard kill.
         *
         * <p>For FFmpeg the correct graceful stop is writing {@code q} to stdin:
         * FFmpeg then flushes the encoder, writes the container trailer (the MP4
         * {@code moov} atom) and exits with code 0. Merely closing stdin does
         * <em>not</em> work — FFmpeg keeps running and the output stays
         * unplayable, which is why this method writes the quit command first and
         * only then closes the stream.
         *
         * @return true when the process exited on its own within the grace period
         */
        public boolean stopGracefully(long graceMillis) {
            stopRequested = true;
            // 1. Ask the encoder to quit and finalize its container.
            try {
                OutputStream out = process.getOutputStream();
                out.write('q');
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                Log.debug("Could not send quit command to " + description + ": " + e.getMessage());
            }

            try {
                if (process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                    return process.exitValue() == 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 2. No response: close stdin so a tool waiting on EOF can proceed.
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // already closed
            }
            try {
                if (process.waitFor(2000, TimeUnit.MILLISECONDS)) {
                    return process.exitValue() == 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 3. Last resort.
            kill();
            return false;
        }

        public void kill() {
            stopRequested = true;
            process.destroy();
            try {
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        public int awaitExit(long millis) {
            try {
                if (process.waitFor(millis, TimeUnit.MILLISECONDS)) {
                    return process.exitValue();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Integer.MIN_VALUE;
        }

        private void joinPumps(long millis) {
            for (Thread t : pumps) {
                try {
                    t.join(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Starts a process in the background and streams output through the callbacks.
     *
     * @param command    full argv, index 0 is the executable
     * @param workingDir working directory, may be null
     * @param onStdout   called per stdout line, may be null
     * @param onStderr   called per stderr line, may be null
     * @param onExit     called with the exit code once the process ends, may be null
     */
    public static Handle start(List<String> command,
                               java.io.File workingDir,
                               Consumer<String> onStdout,
                               Consumer<String> onStderr,
                               java.util.function.IntConsumer onExit) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        Map<String, String> env = pb.environment();
        // FFmpeg reads these; force non-interactive behaviour.
        env.put("AV_LOG_FORCE_NOCOLOR", "1");
        pb.redirectErrorStream(false);

        Process process = pb.start();
        Handle handle = new Handle(process, String.join(" ", command));

        Thread outPump = pump(process.getInputStream(), onStdout, "out");
        Thread errPump = pump(process.getErrorStream(), onStderr, "err");
        handle.pumps.add(outPump);
        handle.pumps.add(errPump);

        Thread waiter = new Thread(() -> {
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Drain remaining output before reporting completion.
            handle.joinPumps(500);
            if (onExit != null) {
                try {
                    onExit.accept(code);
                } catch (RuntimeException e) {
                    Log.error("onExit callback failed", e);
                }
            }
        }, "proc-wait");
        waiter.setDaemon(true);
        waiter.start();

        return handle;
    }

    private static Thread pump(InputStream in, Consumer<String> sink, String kind) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sink != null) {
                        try {
                            sink.accept(line);
                        } catch (RuntimeException e) {
                            Log.warn("output consumer failed (" + kind + "): " + e.getMessage());
                        }
                    }
                }
            } catch (IOException ignored) {
                // stream closed on process exit
            }
        }, "proc-pump-" + kind);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Runs a command to completion, capturing all output.
     *
     * @param timeoutMillis hard timeout; the process is killed when exceeded
     */
    public static Result run(List<String> command, long timeoutMillis) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("AV_LOG_FORCE_NOCOLOR", "1");
        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outPump = drain(process.getInputStream(), out);
        Thread errPump = drain(process.getErrorStream(), err);

        boolean timedOut = false;
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        joinQuietly(outPump);
        joinQuietly(errPump);

        int code = process.isAlive() ? -1 : process.exitValue();
        return new Result(code, out.toString(), err.toString(), timedOut);
    }

    private static Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (!first) {
                        sink.append('\n');
                    }
                    sink.append(line);
                    first = false;
                }
            } catch (IOException ignored) {
                // stream closed
            }
        }, "proc-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package org.firefly.screenrec.core.recorder;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Short-lived, machine-local cache. Failure always falls back to real probes. */
public final class EncoderCache {
    private EncoderCache() {}
    private static final long TTL = 24 * 60 * 60 * 1000L;
    public static String fingerprint(Path engine) throws java.io.IOException {
        StringBuilder key = new StringBuilder("v1|").append(engine.toAbsolutePath().normalize())
                .append('|').append(Files.size(engine)).append('|').append(Files.getLastModifiedTime(engine))
                .append('|').append(System.getProperty("os.version"));
        try (var files = Files.list(engine.getParent())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".dll")).sorted().toList())
                key.append('|').append(file.getFileName()).append(':').append(Files.size(file)).append(':').append(Files.getLastModifiedTime(file));
        }
        String windows = System.getenv("SystemRoot");
        if (windows != null) {
            Path drivers = Path.of(windows, "System32", "drivers");
            try (var files = Files.list(drivers)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".sys")).sorted().toList())
                    key.append('|').append(file.getFileName()).append(':').append(Files.getLastModifiedTime(file));
            }
        }
        return key.toString();
    }
    public static List<EncoderBackend> read(Path cache, String key, List<EncoderBackend> advertised) {
        try (var in = Files.newInputStream(cache)) {
            Properties p = new Properties(); p.load(in);
            long age = System.currentTimeMillis() - Long.parseLong(p.getProperty("time"));
            if (!key.equals(p.getProperty("key")) || age < 0 || age > TTL) return List.of();
            List<EncoderBackend> result = Arrays.stream(p.getProperty("encoders").split(","))
                    .map(EncoderBackend::valueOf).filter(advertised::contains).toList();
            return result;
        } catch (Exception ignored) { return List.of(); }
    }
    public static void write(Path cache, String key, List<EncoderBackend> encoders) {
        try {
            Files.createDirectories(cache.getParent());
            Properties p = new Properties(); p.setProperty("key", key);
            p.setProperty("time", Long.toString(System.currentTimeMillis()));
            p.setProperty("encoders", encoders.stream().map(Enum::name).collect(Collectors.joining(",")));
            try (var out = Files.newOutputStream(cache)) { p.store(out, "Encoder verification cache; expires after 24h"); }
        } catch (Exception ignored) { /* Cache is optional. */ }
    }
}

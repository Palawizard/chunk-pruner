package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.config.Json;
import fr.palawi.chunkpruner.core.config.JsonException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The run asked with {@code /chunkpruner schedule} for the next server startup (SPEC section 7),
 * kept in {@code <world>/chunkpruner/schedule.json} so that it survives the restart.
 */
public record Schedule(RunMode mode, Instant requestedAt) {
    static final String FILE = "schedule.json";
    public static final String FILE_NAME = "chunkpruner/" + FILE;

    public static Path file(Path world) {
        return world.resolve("chunkpruner").resolve(FILE);
    }

    /** The pending schedule, if any. A file that cannot be understood is an error that names it. */
    public static Optional<Schedule> read(Path world) throws IOException {
        Path file = file(world);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)) {
                throw new JsonException("not a JSON object");
            }
            RunMode mode = mode(root.get("mode"));
            if (!(root.get("requestedAt") instanceof String at)) {
                throw new JsonException("requestedAt is missing");
            }
            return Optional.of(new Schedule(mode, Instant.parse(at)));
        } catch (JsonException | DateTimeException e) {
            throw new IOException(file + " cannot be read: " + e.getMessage(), e);
        }
    }

    public void write(Path world) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mode", mode.id());
        root.put("requestedAt", requestedAt.toString());
        AtomicFiles.writeString(file(world), Json.write(root));
    }

    /** Removes the pending schedule; returns whether there was one. */
    public static boolean clear(Path world) throws IOException {
        return Files.deleteIfExists(file(world));
    }

    static RunMode mode(Object value) throws JsonException {
        for (RunMode mode : RunMode.values()) {
            if (mode.id().equals(value)) {
                return mode;
            }
        }
        throw new JsonException("mode must be \"dry-run\" or \"prune\"");
    }
}

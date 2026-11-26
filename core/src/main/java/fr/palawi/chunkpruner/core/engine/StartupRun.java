package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.Json;
import fr.palawi.chunkpruner.core.config.JsonException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * What runs at server startup (E1, SPEC sections 3 and 4): the end of an unfinished run when a
 * journal exists, otherwise the pending schedule, otherwise auto-prune when it is on and its
 * interval has passed since its last run. One run per startup; a schedule left by a startup that
 * finished a journal runs at the next one.
 */
public final class StartupRun {
    static final String AUTO_PRUNE_FILE = "auto-prune.json";

    private StartupRun() {}

    public enum Trigger {
        JOURNAL("an unfinished run"),
        SCHEDULE("the pending schedule"),
        AUTO_PRUNE("auto-prune");

        private final String description;

        Trigger(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public record Plan(RunMode mode, Trigger trigger) {}

    public static Optional<Plan> decide(Path world, Config config, Instant now) throws IOException {
        if (Journal.exists(world)) {
            return Optional.of(new Plan(RunMode.DRY_RUN, Trigger.JOURNAL));
        }
        Optional<Schedule> schedule = Schedule.read(world);
        if (schedule.isPresent()) {
            return Optional.of(new Plan(schedule.get().mode(), Trigger.SCHEDULE));
        }
        if (config.autoPruneEnabled() && !now.isBefore(nextAutoPrune(world, config))) {
            RunMode mode = Config.MODE_PRUNE.equals(config.autoPruneMode()) ? RunMode.PRUNE : RunMode.DRY_RUN;
            return Optional.of(new Plan(mode, Trigger.AUTO_PRUNE));
        }
        return Optional.empty();
    }

    /** After a run that finished: the schedule it ran is cleared, an auto-prune run is recorded. */
    public static void finished(Path world, Plan plan, Instant now) throws IOException {
        if (plan.trigger() == Trigger.SCHEDULE) {
            Schedule.clear(world);
        } else if (plan.trigger() == Trigger.AUTO_PRUNE) {
            AtomicFiles.writeString(autoPruneFile(world), Json.write(Map.of("lastRun", now.toString())));
        }
    }

    /** When auto-prune last ran at startup, if it ever did. */
    public static Optional<Instant> lastAutoPrune(Path world) throws IOException {
        Path file = autoPruneFile(world);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("lastRun") instanceof String at) {
                return Optional.of(Instant.parse(at));
            }
            throw new JsonException("lastRun is missing");
        } catch (JsonException | DateTimeException e) {
            throw new IOException(file + " cannot be read: " + e.getMessage(), e);
        }
    }

    /** The first startup at which auto-prune may run: now when it never ran. */
    public static Instant nextAutoPrune(Path world, Config config) throws IOException {
        return lastAutoPrune(world)
                .map(last -> last.plus(Duration.ofDays(config.autoPruneIntervalDays())))
                .orElse(Instant.EPOCH);
    }

    static Path autoPruneFile(Path world) {
        return world.resolve("chunkpruner").resolve(AUTO_PRUNE_FILE);
    }
}

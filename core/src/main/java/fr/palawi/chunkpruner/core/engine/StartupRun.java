package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.Json;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.Engine.RunResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What runs at server startup (E1, SPEC sections 3 and 4): the end of an unfinished run when a
 * journal exists, otherwise the pending schedule, otherwise auto-prune when it is on and its
 * interval has passed since its last run. One run per startup. A prune that finishes, resumed or
 * not, clears the schedule (the engine does it) and counts as the last auto-prune run.
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

    /** Loads the mod's config file, which may be missing parts or not be valid JSON. */
    @FunctionalInterface
    public interface ConfigSource {
        Config load() throws IOException, JsonException;
    }

    /** The settings of the startup run, from the config and the plan. */
    @FunctionalInterface
    public interface SettingsFactory {
        RunSettings settings(Config config, Plan plan);
    }

    /** What happened at startup when the server may go on loading the world. */
    public sealed interface Outcome {}

    /** Nothing was due. */
    public record NothingDue() implements Outcome {}

    /** Nothing ran and no region file changed; {@code reason} says why. */
    public record NotRun(String reason, Exception cause) implements Outcome {}

    /**
     * The run is done. {@code problems} holds what failed after it (schedule, retention, the
     * auto-prune record); each one is logged, none stops the server.
     */
    public record Done(Plan plan, RunResult result, List<String> problems) implements Outcome {}

    /** The world must not load: an unfinished run is still there. */
    public static final class StartupRefusedException extends Exception {
        StartupRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static boolean hasUnfinishedRun(Path world) {
        return Journal.exists(world);
    }

    /**
     * Decides and runs what is due at this startup. The journal is looked at before the config: a
     * world with an unfinished run never loads until the run is finished, since it mixes old and
     * new region files.
     */
    public static Outcome atStartup(Path world, ConfigSource source, Clock clock, SettingsFactory factory)
            throws StartupRefusedException {
        Config config;
        try {
            config = source.load();
        } catch (IOException | JsonException e) {
            if (Journal.exists(world)) {
                throw new StartupRefusedException(
                        "the config file is not valid (" + e.getMessage() + ") and the world has an unfinished run,"
                                + " which must be finished before the world loads. Fix the config file and start"
                                + " the server again.",
                        e);
            }
            return new NotRun("the config file is not valid (" + e.getMessage() + ")", e);
        }
        Optional<Plan> due;
        try {
            due = decide(world, config, clock.instant());
        } catch (IOException e) {
            return new NotRun(e.getMessage(), e);
        }
        if (due.isEmpty()) {
            return new NothingDue();
        }
        Plan plan = due.get();
        List<String> problems = new ArrayList<>();
        RunResult result;
        try {
            result = Engine.run(factory.settings(config, plan));
        } catch (CleanupException e) {
            result = e.result();
            problems.add(e.getMessage());
        } catch (Exception e) {
            if (Journal.exists(world)) {
                throw new StartupRefusedException(
                        "the run could not be finished, so the world is not started: " + e.getMessage(), e);
            }
            return new NotRun("the run did not happen, nothing was changed: " + e.getMessage(), e);
        }
        try {
            finished(world, config, plan, clock.instant());
        } catch (IOException e) {
            problems.add(plan.trigger().description() + " could not be recorded (" + e.getMessage()
                    + "), so it runs again at the next startup");
        }
        return new Done(plan, result, List.copyOf(problems));
    }

    /** A new world, without {@code level.dat} yet, has nothing to prune and no schedule. */
    public static Optional<Plan> decide(Path world, Config config, Instant now) throws IOException {
        if (Journal.exists(world)) {
            return Optional.of(new Plan(RunMode.DRY_RUN, Trigger.JOURNAL));
        }
        if (!Files.isRegularFile(world.resolve("level.dat"))) {
            return Optional.empty();
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

    /**
     * After a run that finished: a scheduled dry run is cleared (a prune clears it in the engine),
     * and a run that rewrote files, or auto-prune itself, is recorded as the last auto-prune run.
     */
    static void finished(Path world, Config config, Plan plan, Instant now) throws IOException {
        if (plan.trigger() == Trigger.SCHEDULE) {
            Schedule.clear(world);
        }
        boolean rewrote = plan.trigger() == Trigger.JOURNAL || plan.mode() == RunMode.PRUNE;
        if (plan.trigger() == Trigger.AUTO_PRUNE || rewrote && config.autoPruneEnabled()) {
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

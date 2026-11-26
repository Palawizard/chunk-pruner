package fr.palawi.chunkpruner.core.command;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.engine.PastRuns;
import fr.palawi.chunkpruner.core.engine.PastRuns.Run;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.Schedule;
import fr.palawi.chunkpruner.core.engine.StartupRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What {@code /chunkpruner} answers (SPEC section 7), without Minecraft: each method takes the
 * world folder and returns the lines to send back. No command touches the region files; a run
 * only happens at the next server startup.
 */
public final class ServerCommands {
    /** A prune is scheduled only after a dry run of the same world that is this recent. */
    public static final Duration DRY_RUN_MAX_AGE = Duration.ofDays(7);

    private ServerCommands() {}

    public record Reply(boolean success, List<String> lines) {
        static Reply ok(String... lines) {
            return new Reply(true, List.of(lines));
        }

        static Reply refused(String... lines) {
            return new Reply(false, List.of(lines));
        }
    }

    public static Reply schedule(Path world, RunMode mode, Instant now) throws IOException {
        Optional<Schedule> previous = Schedule.read(world);
        String replaced = previous.map(
                        s -> " It replaces the pending " + s.mode().id() + ".")
                .orElse("");
        if (mode == RunMode.PRUNE) {
            Optional<Run> dryRun = PastRuns.latestDryRun(world);
            if (dryRun.isEmpty() || dryRun.get().startedAt().isBefore(now.minus(DRY_RUN_MAX_AGE))) {
                return Reply.refused(
                        "Refused: a prune needs a dry run of this world from the last " + DRY_RUN_MAX_AGE.toDays()
                                + " days.",
                        "Run /chunkpruner schedule dry-run, restart the server, read the report, then schedule"
                                + " the prune.");
            }
            new Schedule(RunMode.PRUNE, now).write(world);
            return Reply.ok(
                    "A prune will run at the next server startup, after a backup of the files it changes." + replaced,
                    "Latest dry run (" + dryRun.get().runId() + "): "
                            + dryRun.get().summary());
        }
        new Schedule(RunMode.DRY_RUN, now).write(world);
        return Reply.ok("A dry run will run at the next server startup. It changes no region file." + replaced);
    }

    public static Reply cancel(Path world) throws IOException {
        Optional<Schedule> previous = Schedule.read(world);
        if (previous.isEmpty()) {
            return Reply.ok("Nothing is scheduled.");
        }
        Schedule.clear(world);
        return Reply.ok("The pending " + previous.get().mode().id() + " is cancelled.");
    }

    public static Reply status(Path world, Config config, Instant now) throws IOException {
        List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(world.resolve("chunkpruner").resolve("journal.json"))) {
            lines.add("An unfinished run will be finished at the next server startup.");
        }
        Optional<Schedule> schedule = Schedule.read(world);
        lines.add(schedule.map(s -> "Scheduled: a " + s.mode().id() + " at the next server startup (asked "
                        + s.requestedAt() + ").")
                .orElse("Scheduled: nothing."));
        Optional<Run> last = PastRuns.latest(world);
        lines.add(last.map(r -> "Last run: " + r.runId() + ", " + r.mode() + " at " + r.context() + ". " + r.summary())
                .orElse("Last run: none."));
        if (!config.autoPruneEnabled()) {
            lines.add("Auto-prune: off.");
        } else {
            Instant next = StartupRun.nextAutoPrune(world, config);
            String when = now.isBefore(next) ? "at the first startup after " + next : "at the next startup";
            lines.add("Auto-prune: " + config.autoPruneMode() + " every " + config.autoPruneIntervalDays()
                    + " days, next " + when + ".");
        }
        return new Reply(true, lines);
    }

    /** The summary of the latest report, or of the given run id. */
    public static Reply report(Path world, String runId) throws IOException {
        Optional<Run> run = runId == null ? PastRuns.latest(world) : PastRuns.find(world, runId);
        if (run.isEmpty()) {
            return Reply.refused(runId == null ? "No report yet." : "No report with the id " + runId + ".");
        }
        Run r = run.get();
        return Reply.ok(
                "Report " + r.runId() + " (" + r.mode() + " at " + r.context() + ", " + r.startedAt() + "):",
                r.summary(),
                "Full report: " + world.relativize(r.text()).toString().replace('\\', '/'));
    }
}

package fr.palawi.chunkpruner.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.command.ServerCommands.Reply;
import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import fr.palawi.chunkpruner.core.engine.Schedule;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The answers of /chunkpruner on a copy of a committed world, S10 among them. */
class ServerCommandsTest {
    private static final Instant DRY_RUN_AT = Instant.parse("2026-11-20T08:00:00Z");

    @TempDir
    Path temp;

    private Path world;

    @BeforeEach
    void copyWorld() throws Exception {
        world = FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("world"));
    }

    /** S10: without a recent dry run, a prune is refused and nothing is written. */
    @Test
    void aPruneIsRefusedWithoutADryRun() throws Exception {
        Map<String, String> before = ReferenceWorld.checksums(world);
        Reply reply = ServerCommands.schedule(world, RunMode.PRUNE, DRY_RUN_AT);
        assertFalse(reply.success());
        assertTrue(reply.lines().get(0).contains("needs a dry run"), reply.lines().toString());
        assertTrue(reply.lines().get(1).contains("/chunkpruner schedule dry-run"), reply.lines().toString());
        assertEquals(before, ReferenceWorld.checksums(world), "a refused schedule writes nothing");
    }

    /** S10 with a schedule already there: the pending dry run stays, the prune is refused. */
    @Test
    void aPruneIsRefusedWhileOnlyADryRunIsScheduled() throws Exception {
        assertTrue(ServerCommands.schedule(world, RunMode.DRY_RUN, DRY_RUN_AT).success());
        Reply reply = ServerCommands.schedule(world, RunMode.PRUNE, DRY_RUN_AT.plusSeconds(60));
        assertFalse(reply.success());
        assertEquals(RunMode.DRY_RUN, Schedule.read(world).orElseThrow().mode());
    }

    @Test
    void aPruneIsScheduledOnlyAfterADryRunOfTheLastSevenDays() throws Exception {
        dryRunAt(DRY_RUN_AT);
        Instant late = DRY_RUN_AT.plus(ServerCommands.DRY_RUN_MAX_AGE).plusSeconds(1);
        assertFalse(ServerCommands.schedule(world, RunMode.PRUNE, late).success());
        assertTrue(Schedule.read(world).isEmpty());

        Instant inTime = DRY_RUN_AT.plus(Duration.ofDays(6));
        Reply reply = ServerCommands.schedule(world, RunMode.PRUNE, inTime);
        assertTrue(reply.success(), reply.lines().toString());
        assertTrue(reply.lines().get(1).startsWith("Latest dry run (20261120-080000-dry-run"), reply.lines().toString());
        assertEquals(new Schedule(RunMode.PRUNE, inTime), Schedule.read(world).orElseThrow());
    }

    @Test
    void aNewScheduleReplacesThePendingOneAndCancelRemovesIt() throws Exception {
        ServerCommands.schedule(world, RunMode.DRY_RUN, DRY_RUN_AT);
        dryRunAt(DRY_RUN_AT);
        Reply prune = ServerCommands.schedule(world, RunMode.PRUNE, DRY_RUN_AT.plusSeconds(60));
        assertTrue(prune.lines().get(0).endsWith("It replaces the pending dry-run."), prune.lines().toString());
        assertEquals(
                "The pending prune is cancelled.",
                ServerCommands.cancel(world).lines().get(0));
        assertFalse(Files.exists(Schedule.file(world)));
        assertEquals("Nothing is scheduled.", ServerCommands.cancel(world).lines().get(0));
    }

    @Test
    void statusAndReportDescribeTheWorld() throws Exception {
        Reply empty = ServerCommands.status(world, Config.defaults(), DRY_RUN_AT);
        assertEquals(
                java.util.List.of("Scheduled: nothing.", "Last run: none.", "Auto-prune: off."), empty.lines());
        assertFalse(ServerCommands.report(world, null).success());

        dryRunAt(DRY_RUN_AT);
        ServerCommands.schedule(world, RunMode.DRY_RUN, DRY_RUN_AT.plusSeconds(5));
        Config auto = Config.fromJson("{\"autoPrune\": {\"enabled\": true, \"intervalDays\": 3}}");
        Reply status = ServerCommands.status(world, auto, DRY_RUN_AT.plusSeconds(10));
        assertEquals("Scheduled: a dry-run at the next server startup (asked 2026-11-20T08:00:05Z).", status.lines().get(0));
        assertTrue(status.lines().get(1).startsWith("Last run: 20261120-080000-dry-run, dry-run at command line. Would remove "),
                status.lines().get(1));
        assertEquals("Auto-prune: dry-run every 3 days, next at the next startup.", status.lines().get(2));

        Reply report = ServerCommands.report(world, null);
        assertTrue(report.success());
        assertTrue(report.lines().get(1).startsWith("Would remove "), report.lines().toString());
        assertEquals(
                "Full report: chunkpruner/reports/20261120-080000-dry-run.txt", report.lines().get(2));
        assertEquals(report, ServerCommands.report(world, "20261120-080000-dry-run"));
        assertEquals(
                "No report with the id nope.", ServerCommands.report(world, "nope").lines().get(0));
    }

    private void dryRunAt(Instant at) throws Exception {
        Engine.run(RunSettings.of(world, Config.defaults(), RunMode.DRY_RUN, RunContext.E3)
                .withClock(Clock.fixed(at, ZoneOffset.UTC)));
    }
}

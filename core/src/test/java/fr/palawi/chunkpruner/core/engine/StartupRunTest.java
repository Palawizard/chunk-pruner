package fr.palawi.chunkpruner.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.engine.StartupRun.Plan;
import fr.palawi.chunkpruner.core.engine.StartupRun.Trigger;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What runs at server startup (E1), on a copy of a committed world. */
class StartupRunTest {
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    @TempDir
    Path temp;

    private Path world;

    @BeforeEach
    void copyWorld() throws Exception {
        world = FixtureWorlds.copy(FixtureWorlds.committed("26.3"), temp.resolve("world"));
    }

    @Test
    void nothingRunsWithoutJournalScheduleOrAutoPrune() throws Exception {
        assertEquals(Optional.empty(), StartupRun.decide(world, Config.defaults(), NOW));
    }

    @Test
    void aScheduleRunsAndIsClearedOnceFinished() throws Exception {
        new Schedule(RunMode.PRUNE, NOW.minusSeconds(60)).write(world);
        Plan plan = StartupRun.decide(world, Config.defaults(), NOW).orElseThrow();
        assertEquals(new Plan(RunMode.PRUNE, Trigger.SCHEDULE), plan);
        StartupRun.finished(world, plan, NOW);
        assertTrue(Schedule.read(world).isEmpty());
        assertEquals(Optional.empty(), StartupRun.decide(world, Config.defaults(), NOW));
    }

    @Test
    void anUnfinishedRunComesFirstAndLeavesTheScheduleForTheNextStartup() throws Exception {
        new Schedule(RunMode.DRY_RUN, NOW).write(world);
        Files.writeString(world.resolve("chunkpruner").resolve("journal.json"), "{}");
        Plan plan = StartupRun.decide(world, Config.defaults(), NOW).orElseThrow();
        assertEquals(Trigger.JOURNAL, plan.trigger());
        StartupRun.finished(world, plan, NOW);
        assertEquals(RunMode.DRY_RUN, Schedule.read(world).orElseThrow().mode());
    }

    @Test
    void autoPruneRunsOnceItsIntervalHasPassed() throws Exception {
        Config config =
                Config.fromJson("{\"autoPrune\": {\"enabled\": true, \"intervalDays\": 7, \"mode\": \"prune\"}}");
        Plan plan = StartupRun.decide(world, config, NOW).orElseThrow();
        assertEquals(new Plan(RunMode.PRUNE, Trigger.AUTO_PRUNE), plan);
        StartupRun.finished(world, plan, NOW);
        assertEquals(Optional.of(NOW), StartupRun.lastAutoPrune(world));
        assertEquals(Optional.empty(), StartupRun.decide(world, config, NOW.plus(Duration.ofDays(6))));
        assertEquals(
                Optional.of(new Plan(RunMode.PRUNE, Trigger.AUTO_PRUNE)),
                StartupRun.decide(world, config, NOW.plus(Duration.ofDays(7))));
    }

    @Test
    void autoPruneDefaultsToADryRun() throws Exception {
        Config config = Config.fromJson("{\"autoPrune\": {\"enabled\": true}}");
        assertEquals(
                RunMode.DRY_RUN,
                StartupRun.decide(world, config, NOW).orElseThrow().mode());
    }

    @Test
    void aDamagedScheduleIsAnErrorThatNamesTheFile() throws Exception {
        Files.createDirectories(Schedule.file(world).getParent());
        Files.writeString(Schedule.file(world), "{\"mode\": \"later\"}");
        IOException e = assertThrows(IOException.class, () -> StartupRun.decide(world, Config.defaults(), NOW));
        assertTrue(e.getMessage().contains("schedule.json"), e.getMessage());
    }
}

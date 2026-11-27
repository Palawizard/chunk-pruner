package fr.palawi.chunkpruner.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.StartupRun.ConfigSource;
import fr.palawi.chunkpruner.core.engine.StartupRun.Done;
import fr.palawi.chunkpruner.core.engine.StartupRun.NotRun;
import fr.palawi.chunkpruner.core.engine.StartupRun.NothingDue;
import fr.palawi.chunkpruner.core.engine.StartupRun.Outcome;
import fr.palawi.chunkpruner.core.engine.StartupRun.Plan;
import fr.palawi.chunkpruner.core.engine.StartupRun.SettingsFactory;
import fr.palawi.chunkpruner.core.engine.StartupRun.StartupRefusedException;
import fr.palawi.chunkpruner.core.engine.StartupRun.Trigger;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What runs at server startup (E1), and when the world may load, on a copy of a committed world. */
class StartupRunTest {
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String AUTO_DRY_RUN = "{\"autoPrune\": {\"enabled\": true, \"intervalDays\": 7}}";

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
        assertInstanceOf(NothingDue.class, atStartup(Config.defaults()));
    }

    @Test
    void aNewWorldWithoutLevelDatHasNothingDue() throws Exception {
        Files.delete(world.resolve("level.dat"));
        assertInstanceOf(NothingDue.class, atStartup(Config.fromJson(AUTO_DRY_RUN)));
    }

    @Test
    void aScheduledDryRunRunsAndIsCleared() throws Exception {
        new Schedule(RunMode.DRY_RUN, NOW.minusSeconds(60)).write(world);
        Map<String, String> before = worldFiles();
        Done done = assertInstanceOf(Done.class, atStartup(Config.defaults()));
        assertEquals(new Plan(RunMode.DRY_RUN, Trigger.SCHEDULE), done.plan());
        assertEquals(List.of(), done.problems());
        assertTrue(Schedule.read(world).isEmpty());
        assertEquals(before, worldFiles(), "a dry run changes no region file");
        assertInstanceOf(NothingDue.class, atStartup(Config.defaults()));
    }

    /** A prune clears the schedule and counts as the last auto-prune run. */
    @Test
    void aScheduledPruneClearsTheScheduleAndCountsAsAutoPrune() throws Exception {
        new Schedule(RunMode.PRUNE, NOW.minusSeconds(60)).write(world);
        Done done = assertInstanceOf(Done.class, atStartup(Config.fromJson(AUTO_DRY_RUN)));
        assertEquals(new Plan(RunMode.PRUNE, Trigger.SCHEDULE), done.plan());
        assertTrue(done.result().summary().startsWith("Removed "), done.result().summary());
        assertTrue(Schedule.read(world).isEmpty());
        assertEquals(Optional.of(NOW), StartupRun.lastAutoPrune(world));
    }

    @Test
    void autoPruneRunsOnceItsIntervalHasPassed() throws Exception {
        Config config =
                Config.fromJson("{\"autoPrune\": {\"enabled\": true, \"intervalDays\": 7, \"mode\": \"prune\"}}");
        Plan plan = StartupRun.decide(world, config, NOW).orElseThrow();
        assertEquals(new Plan(RunMode.PRUNE, Trigger.AUTO_PRUNE), plan);
        StartupRun.finished(world, config, plan, NOW);
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
    void aDamagedScheduleIsNotRunAndNamesTheFile() throws Exception {
        Files.createDirectories(Schedule.file(world).getParent());
        Files.writeString(Schedule.file(world), "{\"mode\": \"later\"}");
        NotRun notRun = assertInstanceOf(NotRun.class, atStartup(Config.defaults()));
        assertTrue(notRun.reason().contains("schedule.json"), notRun.reason());
    }

    @Test
    void aDamagedAutoPruneRecordIsNotRunAndNamesTheFile() throws Exception {
        Files.createDirectories(StartupRun.autoPruneFile(world).getParent());
        Files.writeString(StartupRun.autoPruneFile(world), "{}");
        NotRun notRun = assertInstanceOf(NotRun.class, atStartup(Config.fromJson(AUTO_DRY_RUN)));
        assertTrue(notRun.reason().contains("auto-prune.json"), notRun.reason());
    }

    @Test
    void anInvalidConfigWithoutJournalLetsTheServerStart() throws Exception {
        new Schedule(RunMode.PRUNE, NOW).write(world);
        Map<String, String> before = worldFiles();
        NotRun notRun = assertInstanceOf(NotRun.class, atStartup(invalidConfig()));
        assertTrue(notRun.reason().startsWith("the config file is not valid"), notRun.reason());
        assertEquals(before, worldFiles());
    }

    /** The world mixes old and new region files: it must not load, whatever the config says. */
    @Test
    void anInvalidConfigWithAnUnfinishedRunRefusesTheStartup() throws Exception {
        interruptAPrune();
        Map<String, String> before = worldFiles();
        StartupRefusedException e = assertThrows(
                StartupRefusedException.class, () -> StartupRun.atStartup(world, invalidConfig(), CLOCK, settings()));
        assertTrue(e.getMessage().contains("unfinished run"), e.getMessage());
        assertEquals(before, worldFiles());
    }

    @Test
    void aRunThatFailsBeforeItsJournalLetsTheServerStart() throws Exception {
        new Schedule(RunMode.PRUNE, NOW).write(world);
        Map<String, String> before = worldFiles();
        SettingsFactory noSpace =
                (config, plan) -> settings().settings(config, plan).withFreeSpace(folder -> 0);
        Outcome outcome = StartupRun.atStartup(world, () -> Config.defaults(), CLOCK, noSpace);
        NotRun notRun = assertInstanceOf(NotRun.class, outcome);
        assertTrue(notRun.reason().startsWith("the run did not happen, nothing was changed"), notRun.reason());
        assertEquals(before, worldFiles());
        assertEquals(RunMode.PRUNE, Schedule.read(world).orElseThrow().mode());
    }

    @Test
    void aJournalThatCannotBeFinishedRefusesTheStartup() throws Exception {
        Files.createDirectories(world.resolve("chunkpruner"));
        Files.writeString(world.resolve("chunkpruner/journal.json"), "{}");
        assertThrows(StartupRefusedException.class, () -> atStartup(Config.defaults()));
        assertTrue(StartupRun.hasUnfinishedRun(world));
    }

    /** The resumed prune is the one the schedule asked for: it does not run a second time. */
    @Test
    void anInterruptedPruneIsFinishedAndClearsTheSchedule() throws Exception {
        new Schedule(RunMode.PRUNE, NOW.minusSeconds(60)).write(world);
        interruptAPrune();
        Done done = assertInstanceOf(Done.class, atStartup(Config.fromJson(AUTO_DRY_RUN)));
        assertEquals(Trigger.JOURNAL, done.plan().trigger());
        assertTrue(done.result().resumed());
        assertFalse(StartupRun.hasUnfinishedRun(world));
        assertTrue(Schedule.read(world).isEmpty());
        assertEquals(Optional.of(NOW), StartupRun.lastAutoPrune(world));
        assertInstanceOf(NothingDue.class, atStartup(Config.fromJson(AUTO_DRY_RUN)));
    }

    @Test
    void aPruneWithAutoPruneOffWritesNoAutoPruneRecord() throws Exception {
        new Schedule(RunMode.PRUNE, NOW).write(world);
        assertInstanceOf(Done.class, atStartup(Config.defaults()));
        assertFalse(Files.exists(StartupRun.autoPruneFile(world)));
    }

    @Test
    void aConfigThatCannotBeWrittenIsNotCalledInvalid() throws Exception {
        interruptAPrune();
        ConfigSource unwritable = () -> {
            throw new AccessDeniedException("config/chunkpruner.json.tmp");
        };
        StartupRefusedException e = assertThrows(
                StartupRefusedException.class, () -> StartupRun.atStartup(world, unwritable, CLOCK, settings()));
        assertTrue(e.getMessage().startsWith("the config file cannot be read or written ("), e.getMessage());
    }

    /** The schedule cannot be cleared: the retention still runs, and both are in one problem. */
    @Test
    void aScheduleThatCannotBeClearedDoesNotStopTheRetention() throws Exception {
        Path old = world.resolve("chunkpruner/backups/20200101-000000-prune");
        Files.createDirectories(old);
        Files.writeString(old.resolve("restore.txt"), "old backup");
        Files.createDirectories(Schedule.file(world).resolve("in-the-way"));
        Config autoPrune =
                Config.fromJson("{\"backupRetention\": 1, \"autoPrune\": {\"enabled\": true, \"mode\": \"prune\"}}");
        Done done = assertInstanceOf(Done.class, atStartup(autoPrune));
        assertEquals(Trigger.AUTO_PRUNE, done.plan().trigger());
        assertFalse(Files.exists(old), "the retention deleted the old backup");
        assertTrue(
                done.problems().get(0).contains("the schedule could not be cleared"),
                done.problems().toString());
    }

    /** The prune is done; only its auto-prune record failed, and the problem says what runs again. */
    @Test
    void aFailedAutoPruneRecordSaysThatAutoPruneRunsAgain() throws Exception {
        Path record = StartupRun.autoPruneFile(world);
        Files.createDirectories(record.resolve("in-the-way"));
        new Schedule(RunMode.PRUNE, NOW).write(world);
        Done done = assertInstanceOf(Done.class, atStartup(Config.fromJson(AUTO_DRY_RUN)));
        assertEquals(1, done.problems().size(), done.problems().toString());
        String problem = done.problems().get(0);
        assertTrue(problem.startsWith("this run could not be recorded as the last auto-prune run ("), problem);
        assertTrue(problem.endsWith("so auto-prune runs at the next startup"), problem);
        assertTrue(Schedule.read(world).isEmpty(), "the prune cleared its schedule");
    }

    /** A backup the retention cannot delete: the prune is done and the log says so. */
    @Test
    void aFailedRetentionAfterThePruneIsAProblemOfADoneRun() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path old = world.resolve("chunkpruner/backups/20200101-000000-prune");
        Path locked = old.resolve("region");
        Files.createDirectories(locked);
        Files.writeString(old.resolve("restore.txt"), "old backup");
        Files.writeString(locked.resolve("r.0.0.mca"), "old copy");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(locked), "the tests run with rights that ignore folder permissions");
            new Schedule(RunMode.PRUNE, NOW).write(world);
            Done done = assertInstanceOf(Done.class, atStartup(Config.fromJson("{\"backupRetention\": 1}")));
            assertEquals(1, done.problems().size(), done.problems().toString());
            assertTrue(
                    done.problems().get(0).startsWith("the run is done (Removed "),
                    done.problems().get(0));
            assertFalse(StartupRun.hasUnfinishedRun(world));
            assertTrue(Schedule.read(world).isEmpty(), "the schedule is cleared before the retention");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /** The checksums of the world's own files, without the mod's folder. */
    private Map<String, String> worldFiles() throws IOException {
        Map<String, String> files = new TreeMap<>(ReferenceWorld.checksums(world));
        files.keySet().removeIf(name -> name.startsWith("chunkpruner/"));
        return files;
    }

    private Outcome atStartup(Config config) throws StartupRefusedException {
        return StartupRun.atStartup(world, () -> config, CLOCK, settings());
    }

    private Outcome atStartup(ConfigSource source) throws StartupRefusedException {
        return StartupRun.atStartup(world, source, CLOCK, settings());
    }

    private SettingsFactory settings() {
        return (config, plan) ->
                RunSettings.of(world, config, plan.mode(), RunContext.E1).withClock(CLOCK);
    }

    private static ConfigSource invalidConfig() {
        return () -> {
            throw new JsonException("expected a value at line 3");
        };
    }

    /** A prune killed after its first region: the journal stays, as after a crash. */
    private void interruptAPrune() throws IOException {
        RunListener stopAfterFirstRegion = (event, path) -> {
            if (event.equals(RunListener.REGION_DONE)) {
                throw new IllegalStateException("interrupted for the test");
            }
        };
        assertThrows(
                IllegalStateException.class,
                () -> Engine.run(RunSettings.of(world, Config.defaults(), RunMode.PRUNE, RunContext.E3)
                        .withClock(CLOCK)
                        .withListener(stopAfterFirstRegion)));
        assertTrue(StartupRun.hasUnfinishedRun(world));
    }
}

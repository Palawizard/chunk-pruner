package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC section 4, steps 5 to 8 and the resume paragraph, on copies of the 1.21.1 fixture. */
class BackupJournalFixtureTest {
    private static final Instant START = Instant.parse("2026-11-16T10:00:00Z");

    @TempDir
    Path temp;

    private Path world(String name) throws IOException {
        return FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve(name));
    }

    /**
     * The backup holds every changed file as it was, and following its restore.txt gives the world
     * back, on the layout with the overworld at the root (1.21.1) and on the one with every
     * dimension under dimensions/ (26.3).
     */
    @ParameterizedTest
    @ValueSource(strings = {"1.21.1", "26.3", "26.3-keep", "1.20.1-keep"})
    void followingRestoreTxtGivesTheWorldBack(String name) throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed(name), temp.resolve("world"));
        Map<String, String> before = ReferenceWorld.checksums(world);
        Engine.RunResult result =
                Engine.run(settings(world, Config.defaults(), RunMode.PRUNE).withClock(at(0)));
        String backup = (String) readJson(result.jsonReport()).get("backup");
        assertEquals("chunkpruner/backups/20261116-100000-prune", backup);
        assertTrue(!before.equals(ReferenceWorld.checksums(world)), "the prune changed the world");

        List<String> steps = Files.readAllLines(world.resolve(backup).resolve("restore.txt")).stream()
                .filter(l -> l.startsWith("Copy the files of "))
                .toList();
        assertTrue(!steps.isEmpty());
        for (String step : steps) {
            String[] parts = step.substring("Copy the files of ".length()).split(" into ");
            Path from = world.resolve(parts[0]);
            Path to = world.resolve(parts[1]);
            try (Stream<Path> files = Files.list(from)) {
                for (Path file : files.toList()) {
                    Files.copy(file, to.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Map<String, String> restored = new HashMap<>(ReferenceWorld.checksums(world));
        restored.keySet().removeIf(p -> p.startsWith("chunkpruner/"));
        assertEquals(before, restored, "following restore.txt gives the world as it was");
    }

    /** A backup folder without restore.txt, left by a run killed during its copy, is deleted. */
    @Test
    void aPartialBackupIsDeletedAndTakesNoRetentionSlot() throws Exception {
        Path world = world("world");
        Engine.run(settings(
                        world,
                        Config.fromJson("{\"protectRadiusChunks\": 8, \"protectNeighborRadiusChunks\": 3}"),
                        RunMode.PRUNE)
                .withClock(at(0)));
        Path partial = Files.createDirectories(world.resolve("chunkpruner/backups/20261116-100100-prune/minecraft"));
        Engine.run(settings(world, Config.fromJson("{\"backupRetention\": 2}"), RunMode.PRUNE)
                .withClock(at(2)));
        assertFalse(Files.exists(partial.getParent()));
        try (Stream<Path> folders = Files.list(world.resolve("chunkpruner/backups"))) {
            assertEquals(
                    List.of("20261116-100000-prune", "20261116-100200-prune"),
                    folders.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void withBackupOffTheRunStillUsesTheJournalAndWritesNoBackup() throws Exception {
        Path world = world("world");
        List<String> events = new java.util.ArrayList<>();
        Engine.run(settings(world, Config.fromJson("{\"backup\": false}"), RunMode.PRUNE)
                .withListener((event, path) -> events.add(event)));
        assertFalse(Files.exists(world.resolve("chunkpruner/backups")));
        assertTrue(events.contains(RunListener.JOURNAL_WRITTEN));
        assertFalse(events.contains(RunListener.BACKUP_WRITTEN));
        assertTrue(events.indexOf(RunListener.JOURNAL_WRITTEN) < events.indexOf(RunListener.FILE_REWRITTEN));
    }

    /** Backup, then journal, then the first rewrite, in that order. */
    @Test
    void theBackupAndTheJournalComeBeforeTheFirstRewrite() throws Exception {
        Path world = world("world");
        List<String> events = new java.util.ArrayList<>();
        Engine.run(settings(world, Config.defaults(), RunMode.PRUNE).withListener((event, path) -> events.add(event)));
        int backup = events.indexOf(RunListener.BACKUP_WRITTEN);
        int journal = events.indexOf(RunListener.JOURNAL_WRITTEN);
        int first = events.indexOf(RunListener.TEMP_WRITTEN);
        assertTrue(backup >= 0 && backup < journal && journal < first, events.toString());
    }

    @Test
    void aBackupThatCannotBeWrittenStopsTheRunBeforeAnyChange() throws Exception {
        Path world = world("world");
        Files.createDirectories(world.resolve("chunkpruner"));
        Files.writeString(world.resolve("chunkpruner/backups"), "not a folder");
        Map<String, String> before = ReferenceWorld.checksums(world);
        assertThrows(IOException.class, () -> Engine.run(settings(world, Config.defaults(), RunMode.PRUNE)));
        assertEquals(before, ReferenceWorld.checksums(world), "no file changed and no journal written");
    }

    /** Four prune runs, each removing more; backupRetention 2 keeps the two newest backups. */
    @Test
    void retentionKeepsTheNewestBackups() throws Exception {
        Path world = world("world");
        String[] configs = {
            "{\"backupRetention\": 2, \"protectRadiusChunks\": 8, \"protectNeighborRadiusChunks\": 3}",
            "{\"backupRetention\": 2, \"protectRadiusChunks\": 6, \"protectNeighborRadiusChunks\": 2}",
            "{\"backupRetention\": 2, \"protectRadiusChunks\": 4, \"protectNeighborRadiusChunks\": 1}",
            "{\"backupRetention\": 2, \"protectRadiusChunks\": 0, \"protectNeighborRadiusChunks\": 0}"
        };
        for (int i = 0; i < configs.length; i++) {
            Engine.RunResult result = Engine.run(
                    settings(world, Config.fromJson(configs[i]), RunMode.PRUNE).withClock(at(i)));
            assertTrue(result.report().pruned() > 0, "run " + i + " prunes something");
        }
        try (Stream<Path> folders = Files.list(world.resolve("chunkpruner/backups"))) {
            assertEquals(
                    List.of("20261116-100200-prune", "20261116-100300-prune"),
                    folders.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    /** A file that changed after the run planned it stops the resume; the journal stays. */
    @Test
    void aResumeStopsWhenAPendingFileChanged() throws Exception {
        Path world = world("world");
        RunListener stopAfterFirstRegion = (event, path) -> {
            if (event.equals(RunListener.REGION_DONE)) {
                throw new IllegalStateException("interrupted for the test");
            }
        };
        assertThrows(
                IllegalStateException.class,
                () -> Engine.run(
                        settings(world, Config.defaults(), RunMode.PRUNE).withListener(stopAfterFirstRegion)));
        assertTrue(Files.exists(world.resolve("chunkpruner/journal.json")));
        String journal = Files.readString(world.resolve("chunkpruner/journal.json"));
        assertTrue(journal.contains("\"done\"") && journal.contains("\"pending\""), journal);

        Path pending = null;
        for (Path file : ReferenceWorld.read(world).files("region")) {
            String name = file.getFileName().toString();
            if (journal.matches(
                    "(?s).*\"file\": \"" + java.util.regex.Pattern.quote(name) + "\",\\s*\"state\": \"pending\".*")) {
                pending = file;
                break;
            }
        }
        assertTrue(pending != null, "a region is still pending");
        Files.write(pending, new byte[4096], StandardOpenOption.APPEND);
        Map<String, String> before = ReferenceWorld.checksums(world);
        IOException stopped =
                assertThrows(IOException.class, () -> Engine.run(settings(world, Config.defaults(), RunMode.DRY_RUN)));
        assertTrue(stopped.getMessage().contains("changed since the run planned it"), stopped.getMessage());
        assertEquals(before, ReferenceWorld.checksums(world), "nothing more changed, the journal is kept");
    }

    /** A plan without its journal comes from a run stopped before its first rewrite. */
    @Test
    void aPlanWithoutAJournalIsDiscarded() throws Exception {
        Path world = world("world");
        Files.createDirectories(world.resolve("chunkpruner"));
        Files.writeString(world.resolve("chunkpruner/journal-plan.json"), "{}");
        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.DRY_RUN));
        assertFalse(result.resumed());
        assertFalse(Files.exists(world.resolve("chunkpruner/journal-plan.json")));
    }

    private static Clock at(int minutes) {
        return Clock.fixed(START.plusSeconds(60L * minutes), ZoneOffset.UTC);
    }
}

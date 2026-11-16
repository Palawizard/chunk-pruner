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

/** SPEC section 4, steps 5 to 8 and the resume paragraph, on copies of the 1.21.1 fixture. */
class BackupJournalFixtureTest {
    private static final Instant START = Instant.parse("2026-11-16T10:00:00Z");

    @TempDir
    Path temp;

    private Path world(String name) throws IOException {
        return FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve(name));
    }

    /** The backup holds every changed file as it was; copying it back restores the world. */
    @Test
    void theBackupHoldsTheChangedFilesAndRestoresTheWorld() throws Exception {
        Path world = world("world");
        Map<String, String> before = ReferenceWorld.checksums(world);
        Engine.RunResult result =
                Engine.run(settings(world, Config.defaults(), RunMode.PRUNE).withClock(at(0)));

        String backup = (String) readJson(result.jsonReport()).get("backup");
        assertEquals("chunkpruner/backups/20261116-100000-prune", backup);
        Path overworld = world.resolve(backup).resolve("minecraft/overworld");
        Map<String, String> after = ReferenceWorld.checksums(world);
        int changed = 0;
        for (Map.Entry<String, String> file : before.entrySet()) {
            if (!file.getValue().equals(after.get(file.getKey()))) {
                changed++;
                Path copy = overworld.resolve(file.getKey());
                assertTrue(Files.isRegularFile(copy), "backed up: " + file.getKey());
                assertEquals(
                        file.getValue(),
                        ReferenceWorld.checksums(copy.getParent())
                                .get(copy.getFileName().toString()));
            }
        }
        assertTrue(changed > 0);
        assertFalse(Files.exists(world.resolve("chunkpruner/journal.json")));
        assertFalse(Files.exists(world.resolve("chunkpruner/journal-plan.json")));

        try (Stream<Path> files = Files.walk(overworld)) {
            for (Path copy : files.filter(Files::isRegularFile).toList()) {
                Files.copy(
                        copy,
                        world.resolve(overworld.relativize(copy).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Map<String, String> restored = new HashMap<>(ReferenceWorld.checksums(world));
        restored.keySet().removeIf(p -> p.startsWith("chunkpruner/"));
        assertEquals(before, restored, "copying the backup back gives the world as it was");
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

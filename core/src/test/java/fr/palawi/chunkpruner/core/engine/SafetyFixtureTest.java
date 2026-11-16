package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.cli.CommandLine;
import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.LockHolder;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.report.ReportFiles;
import fr.palawi.chunkpruner.core.world.WorldInUseException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S7, S11, and the command line's refusal to prune in this version. */
class SafetyFixtureTest {
    @TempDir
    Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int cli(String... args) {
        return CommandLine.run(
                args,
                "0.1.0",
                null,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Path world() throws Exception {
        return FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("world"));
    }

    /** S7: a second Java process holds session.lock, as a running game does. */
    @Test
    void commandLineRefusesAWorldLockedByAnotherProcess() throws Exception {
        Path world = world();
        Map<String, String> before = ReferenceWorld.checksums(world);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        LockHolder.class.getName(),
                        world.toString())
                .redirectErrorStream(true)
                .start();
        try {
            BufferedReader lines =
                    new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = lines.readLine();
            } while (line != null && !line.equals("locked"));
            assertEquals("locked", line, "the second process took the lock");

            assertEquals(CommandLine.EXIT_REFUSED, cli("--world", world.toString(), "--dry-run"));
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("is open in another program"), err.toString());
            assertEquals(before, ReferenceWorld.checksums(world), "nothing was written, not even a report");
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(30, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
            }
        }
        assertEquals(CommandLine.EXIT_OK, cli("--world", world.toString(), "--dry-run"), err.toString());
    }

    @Test
    void engineRefusesALockHeldInTheSameProcess() throws Exception {
        Path world = world();
        try (FileChannel channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertThrows(
                    WorldInUseException.class, () -> Engine.run(settings(world, Config.defaults(), RunMode.DRY_RUN)));
        }
    }

    /**
     * S11: free space is injected. The threshold is the size of the files to change (the backup
     * copies them) plus the largest of them (the rewrite writes it again before it moves it over),
     * plus 10 percent.
     */
    @Test
    void pruneStopsBeforeAnyChangeWhenSpaceIsShort() throws Exception {
        Path world = world();
        ReferenceWorld reference = ReferenceWorld.read(world);
        Expected expected = Expected.compute(reference, 0, 4, 1, true);
        long toChange = 0;
        long largest = 0;
        for (String storage : ReferenceWorld.STORAGES) {
            for (Path file : reference.files(storage)) {
                boolean changes = ReferenceWorld.chunks(storage, file, true).stream()
                        .anyMatch(c -> expected.pruned.contains(c.key()));
                if (changes) {
                    toChange += Files.size(file);
                    largest = Math.max(largest, Files.size(file));
                }
            }
        }
        long required = ((toChange + largest) * 110 + 99) / 100;
        Map<String, String> before = ReferenceWorld.checksums(world);

        RunSettings tooSmall = settings(world, Config.defaults(), RunMode.PRUNE).withFreeSpace(folder -> required - 1);
        NotEnoughSpaceException refused = assertThrows(NotEnoughSpaceException.class, () -> Engine.run(tooSmall));
        assertTrue(refused.getMessage().contains(required + " bytes must be free"), refused.getMessage());
        assertEquals(before, ReferenceWorld.checksums(world), "no file changed, no report written");

        RunSettings lowDryRun =
                settings(world, Config.defaults(), RunMode.DRY_RUN).withFreeSpace(folder -> required - 1);
        Engine.RunResult dry = Engine.run(lowDryRun);
        assertTrue(readJson(dry.jsonReport()).get("warnings").toString().contains("low-disk-space"));

        RunSettings enough = settings(world, Config.defaults(), RunMode.PRUNE).withFreeSpace(folder -> required);
        Engine.RunResult pruned = Engine.run(enough);
        assertEquals(expected.pruned.size(), pruned.report().pruned());
    }

    /** E3 prunes without --dry-run, after the backup, and says where the backup is. */
    @Test
    void commandLinePrunesWithABackup() throws Exception {
        Path world = world();
        Expected expected = Expected.compute(ReferenceWorld.read(world), 0, 4, 1, true);
        assertEquals(CommandLine.EXIT_OK, cli("--world", world.toString()), err.toString(StandardCharsets.UTF_8));
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("The changed files were backed up to "), output);
        assertEquals(expected.pruned.size(), chunksMissing(world), "the expected chunks are gone");
        assertTrue(Files.isDirectory(world.resolve("chunkpruner/backups")));
        assertFalse(Files.exists(world.resolve("chunkpruner/journal.json")));
        assertTrue(Files.exists(ReportFiles.folder(world)));
    }

    private long chunksMissing(Path world) throws Exception {
        long now = ReferenceWorld.read(world).chunks("region", true).size();
        return ReferenceWorld.read(FixtureWorlds.committed("1.21.1"))
                        .chunks("region", true)
                        .size()
                - now;
    }

    @Test
    void commandLineDryRunPrintsTheSummaryAndWritesReports() throws Exception {
        Path world = world();
        assertEquals(CommandLine.EXIT_OK, cli("--world", world.toString(), "--dry-run"), err.toString());
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.startsWith("Would remove "), printed);
        assertTrue(printed.contains("This was a dry run: no file was changed."));
        try (var reports = Files.list(ReportFiles.folder(world))) {
            assertEquals(2, reports.count());
        }
    }

    @Test
    void commandLineReadsTheConfigFile() throws Exception {
        Path world = world();
        Path config = temp.resolve("chunkpruner.json");
        Files.writeString(config, "{\"protectNeighborRadiusChunks\": 0, \"futureKey\": 1}");
        assertEquals(CommandLine.EXIT_OK, cli("--world", world.toString(), "--dry-run", "--config", config.toString()));
        Path json;
        try (var reports = Files.list(ReportFiles.folder(world))) {
            json = reports.filter(p -> p.toString().endsWith(".json"))
                    .findFirst()
                    .orElseThrow();
        }
        assertTrue(readJson(json).get("config").toString().contains("protectNeighborRadiusChunks=0"));

        Files.writeString(config, "{\"protectRadiusChunks\": -1}");
        assertEquals(
                CommandLine.EXIT_ERROR, cli("--world", world.toString(), "--dry-run", "--config", config.toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("protectRadiusChunks must be a whole number"));
    }

    @Test
    void commandLineReportsAMissingWorld() {
        assertEquals(
                CommandLine.EXIT_ERROR, cli("--world", temp.resolve("nothing").toString(), "--dry-run"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no world folder"));
    }
}

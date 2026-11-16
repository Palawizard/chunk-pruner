package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.LockHolder;
import fr.palawi.chunkpruner.core.fixture.ReferenceNbt;
import fr.palawi.chunkpruner.core.fixture.ReferenceNbtWriter;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import fr.palawi.chunkpruner.core.world.ForcedChunks;
import fr.palawi.chunkpruner.core.world.WorldLayout;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Session lock, retention, forced chunk files and K4 near unreadable chunks, on real worlds. */
class ReviewFixesFixtureTest {
    @TempDir
    Path temp;

    /** A prune creates a missing session.lock and holds it, so the game cannot open the world. */
    @Test
    void aPruneLocksTheWorldEvenWithoutASessionLockFile() throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("world"));
        Files.delete(world.resolve("session.lock"));
        List<String> seen = new ArrayList<>();
        Engine.run(settings(world, Config.defaults(), RunMode.PRUNE).withListener((event, path) -> {
            if (event.equals(RunListener.JOURNAL_WRITTEN)) {
                seen.add(otherProcessLocks(world));
            }
        }));
        assertEquals(List.of("busy"), seen, "a second process cannot take the lock during the run");
        assertEquals("☃", Files.readString(world.resolve("session.lock"), StandardCharsets.UTF_8));

        Path dry = FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("dry"));
        Files.delete(dry.resolve("session.lock"));
        Engine.run(settings(dry, Config.defaults(), RunMode.DRY_RUN));
        assertFalse(Files.exists(dry.resolve("session.lock")), "a dry run creates nothing");
    }

    /** backupRetention 0 keeps the backup of the run that just finished, and so does a clock set back. */
    @Test
    void retentionNeverDeletesTheBackupOfTheRunThatFinished() throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("world"));
        Engine.run(settings(
                        world,
                        Config.fromJson("{\"protectRadiusChunks\": 8, \"protectNeighborRadiusChunks\": 3}"),
                        RunMode.PRUNE)
                .withClock(at("2030-01-01T00:00:00Z")));
        Engine.RunResult second = Engine.run(settings(world, Config.fromJson("{\"backupRetention\": 0}"), RunMode.PRUNE)
                .withClock(at("2026-11-16T10:00:00Z")));
        assertNotNull(second.report().backup());
        assertTrue(Files.isDirectory(world.resolve(second.report().backup())), "the reported backup exists");
        try (Stream<Path> folders = Files.list(world.resolve("chunkpruner/backups"))) {
            assertEquals(
                    List.of("20261116-100000-prune"),
                    folders.map(p -> p.getFileName().toString()).toList());
        }
    }

    /** NeoForge 26.3 forced chunks, written as ForcedChunkManager.OwnedChunks.CODEC encodes them. */
    @Test
    void neoForgeTicketDataIsRead() throws Exception {
        Path dimension = Files.createDirectories(temp.resolve("overworld"));
        Path file = ForcedChunks.file(dimension, WorldLayout.DIMENSION_FOLDERS);
        Files.createDirectories(file.getParent());
        Map<String, Object> root = readGzip(ForcedChunks.file(
                FixtureWorlds.committed("26.3-keep").resolve("dimensions/minecraft/overworld"),
                WorldLayout.DIMENSION_FOLDERS));
        Map<String, Object> data = new LinkedHashMap<>(ReferenceNbt.compound(root, "data"));
        Map<String, Object> tickets = new LinkedHashMap<>();
        tickets.put("normal", new long[] {ForcedChunks.pack(40, -3)});
        tickets.put("natural_spawning", new long[] {ForcedChunks.pack(-41, 7)});
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("position", new int[] {640, 64, -48});
        block.put("tickets", tickets);
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("controller", "examplemod:loader");
        owner.put("block_chunks", List.of(block));
        data.put("neo_ticket_data", List.of(owner));
        Map<String, Object> changed = new LinkedHashMap<>(root);
        changed.put("data", data);
        ReferenceNbtWriter.writeGzip(file, changed);

        ForcedChunks forced = ForcedChunks.read(dimension, WorldLayout.DIMENSION_FOLDERS);
        assertEquals(null, forced.unknownReason());
        assertTrue(forced.isForced(-9, -10), "the vanilla ticket of the keep world");
        assertTrue(forced.isForced(40, -3) && forced.isForced(-41, 7), "the NeoForge tickets");
        assertFalse(forced.isForced(0, 0));

        data.put("examplemod_data", 1);
        ReferenceNbtWriter.writeGzip(file, changed);
        assertNotNull(
                ForcedChunks.read(dimension, WorldLayout.DIMENSION_FOLDERS).unknownReason());
    }

    /** A key next to Forced that no version writes makes every chunk count as forced. */
    @Test
    void anUnknownKeyInChunksDatKeepsTheDimension() throws Exception {
        Path dimension = Files.createDirectories(temp.resolve("world"));
        Path file = ForcedChunks.file(dimension, WorldLayout.LEGACY);
        Files.createDirectories(file.getParent());
        Path real = ForcedChunks.file(FixtureWorlds.committed("1.21.1-keep"), WorldLayout.LEGACY);
        Files.copy(real, file);
        ForcedChunks known = ForcedChunks.read(dimension, WorldLayout.LEGACY);
        assertTrue(known.unknownReason() == null && known.isForced(-9, -10) && !known.isForced(0, 0));

        Map<String, Object> root = new LinkedHashMap<>(readGzip(real));
        Map<String, Object> data = new LinkedHashMap<>(ReferenceNbt.compound(root, "data"));
        data.put("SomeModForced", new long[] {ForcedChunks.pack(1, 1)});
        root.put("data", data);
        ReferenceNbtWriter.writeGzip(file, root);
        ForcedChunks unknown = ForcedChunks.read(dimension, WorldLayout.LEGACY);
        assertNotNull(unknown.unknownReason());
        assertTrue(unknown.isForced(0, 0));
    }

    /**
     * The village of the 1.21.1 world starts in chunk (0, 14), and no kept chunk references it.
     * When chunk (3, 14) cannot be read, its references are unknown, so the start is kept (K4).
     */
    @Test
    void aStartNearAnUnreadableChunkIsKept() throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("world"));
        long start = ReferenceWorld.key(0, 14);
        assertEquals(
                null,
                Expected.compute(ReferenceWorld.read(world), Expected.Rules.defaults(), true)
                        .ruleByChunk
                        .get(start));
        Chunk damaged = null;
        ReferenceWorld reference = ReferenceWorld.read(world);
        for (Chunk c : reference.chunks("region", true)) {
            if (c.x() == 3 && c.z() == 14) {
                damaged = c;
            }
        }
        Path file = reference.overworld().resolve("region").resolve(damaged.file());
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(damaged.sectorOffset() * 4096L + 4);
            raf.write(9);
        }
        Expected expected = Expected.compute(ReferenceWorld.read(world), Expected.Rules.defaults(), true);
        assertEquals("K4", expected.ruleByChunk.get(start));
        Engine.run(settings(world, Config.defaults(), RunMode.PRUNE));
        assertTrue(ReferenceWorld.read(world).chunks("region", true).stream().anyMatch(c -> c.key() == start));
    }

    private static String otherProcessLocks(Path world) {
        try {
            Path javaExe = Path.of(System.getProperty("java.home"), "bin", "java");
            Process holder = new ProcessBuilder(
                            javaExe.toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            LockHolder.class.getName(),
                            world.toString())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader out =
                    new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    if (line.equals("busy") || line.equals("locked")) {
                        return line;
                    }
                }
                return "no answer";
            } finally {
                holder.destroyForcibly();
                holder.waitFor(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static Map<String, Object> readGzip(Path file) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return ReferenceNbt.readRoot(in.readAllBytes());
        }
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}

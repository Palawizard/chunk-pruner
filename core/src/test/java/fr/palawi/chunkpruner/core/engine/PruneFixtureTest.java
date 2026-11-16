package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.assertMatches;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.config;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds.Fixture;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S2 on a temporary copy, by calling the engine directly: exactly the expected chunks leave {@code region}, {@code entities} and
 * {@code poi}, every kept chunk is byte for byte what it was, and the rest of the world is
 * untouched. Opening the pruned world on a real server is {@code scripts/check-pruned-world.ps1}.
 */
class PruneFixtureTest {
    @TempDir
    Path temp;

    static Stream<Arguments> cases() throws Exception {
        return FixtureWorlds.all().stream()
                .flatMap(f -> Stream.of(Arguments.of(f, 4, 1), Arguments.of(f, 0, 0), Arguments.of(f, 0, 1)));
    }

    @ParameterizedTest(name = "{0}, K2 radius {1}, K8 radius {2}")
    @MethodSource("cases")
    void pruneRemovesExactlyTheExpectedChunks(Fixture fixture, int radius, int neighbors) throws Exception {
        Path world = FixtureWorlds.copy(fixture.world(), temp.resolve("world"));
        ReferenceWorld before = ReferenceWorld.read(world);
        Expected expected = Expected.compute(before, 0, radius, neighbors, true);
        Map<String, Map<Long, Chunk>> original = new HashMap<>();
        for (String storage : ReferenceWorld.STORAGES) {
            original.put(storage, byKey(before.chunks(storage, true)));
        }
        Map<String, String> otherFiles = otherFiles(world);
        Config config = config(radius, neighbors);

        Engine.RunResult dryRun = Engine.run(settings(world, config, RunMode.DRY_RUN));
        Engine.RunResult prune = Engine.run(settings(world, config, RunMode.PRUNE));

        assertMatches(expected, readJson(dryRun.jsonReport()));
        assertMatches(expected, readJson(prune.jsonReport()));
        ReferenceWorld after = ReferenceWorld.read(world);
        for (String storage : ReferenceWorld.STORAGES) {
            Map<Long, Chunk> now = byKey(after.chunks(storage, true));
            for (Chunk old : original.get(storage).values()) {
                Chunk kept = now.get(old.key());
                if (expected.pruned.contains(old.key())) {
                    assertEquals(null, kept, storage + " chunk " + old.x() + "," + old.z() + " was pruned");
                } else {
                    assertTrue(kept != null, storage + " chunk " + old.x() + "," + old.z() + " was kept");
                    assertArrayEquals(old.sectors(), kept.sectors(), storage + " chunk " + old.x() + "," + old.z());
                    assertEquals(old.timestamp(), kept.timestamp(), storage + " timestamp " + old.x() + "," + old.z());
                }
            }
            assertEquals(original.get(storage).size() - countPruned(original.get(storage), expected), now.size());
        }
        Map<String, Long> sizes = storageSizes(after);
        expected.storages.forEach((storage, t) -> assertEquals(t[2], sizes.get(storage), storage + " size after"));
        assertEquals(otherFiles, otherFiles(world), "files outside the three storages are untouched");
        assertFalse(hasTempFiles(world), "no .tmp file is left");
    }

    private static long countPruned(Map<Long, Chunk> chunks, Expected expected) {
        return chunks.keySet().stream().filter(expected.pruned::contains).count();
    }

    private static Map<Long, Chunk> byKey(List<Chunk> chunks) {
        Map<Long, Chunk> map = new HashMap<>();
        for (Chunk c : chunks) {
            map.put(c.key(), c);
        }
        return map;
    }

    private static Map<String, Long> storageSizes(ReferenceWorld world) throws Exception {
        Map<String, Long> sizes = new HashMap<>();
        for (String storage : ReferenceWorld.STORAGES) {
            long total = 0;
            Path folder = world.overworld().resolve(storage);
            if (Files.isDirectory(folder)) {
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path f : files.toList()) {
                        String n = f.getFileName().toString();
                        if (n.endsWith(".mca") || n.endsWith(".mcc")) {
                            total += Files.size(f);
                        }
                    }
                }
            }
            sizes.put(storage, total);
        }
        return sizes;
    }

    private static Map<String, String> otherFiles(Path world) throws Exception {
        Map<String, String> sums = new HashMap<>(ReferenceWorld.checksums(world));
        sums.keySet().removeIf(p -> p.matches("(.*/)?(region|entities|poi)/[^/]+") || p.startsWith("chunkpruner/"));
        return sums;
    }

    private static boolean hasTempFiles(Path world) throws Exception {
        try (Stream<Path> files = Files.walk(world)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        }
    }
}

package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.number;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.overworld;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds.Fixture;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S6. The test damages a temporary copy of a real region file written by the game: one chunk
 * gets an unknown compression id, one gets its compressed bytes overwritten, one is marked as
 * stored in a {@code .mcc} file that does not exist, one gets the custom compression id 127.
 * Each was a chunk the run would prune; each is kept (K7), the report warns, and the run goes on.
 */
class DamagedRegionFixtureTest {
    @TempDir
    Path temp;

    static List<Fixture> fixtures() throws Exception {
        return FixtureWorlds.all();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void damagedChunksAreKeptAndReported(Fixture fixture) throws Exception {
        Path world = FixtureWorlds.copy(fixture.world(), temp.resolve("world"));
        ReferenceWorld reference = ReferenceWorld.read(world);
        Expected clean = Expected.compute(reference, 0, 4, 1, true);
        List<Chunk> victims = new ArrayList<>();
        for (Chunk c : reference.chunks("region", true)) {
            if (clean.pruned.contains(c.key()) && !isNeighbourOf(victims, c) && victims.size() < 4) {
                victims.add(c);
            }
        }
        assertEquals(4, victims.size());
        damage(world, reference, victims.get(0), Damage.UNKNOWN_COMPRESSION);
        damage(world, reference, victims.get(1), Damage.CORRUPT_DATA);
        damage(world, reference, victims.get(2), Damage.MISSING_EXTERNAL_FILE);
        damage(world, reference, victims.get(3), Damage.CUSTOM_COMPRESSION);
        ReferenceWorld damaged = ReferenceWorld.read(world);
        Expected expected = Expected.compute(damaged, 0, 4, 1, true);
        Map<Long, Chunk> damagedChunks = new java.util.HashMap<>();
        for (Chunk c : damaged.chunks("region", true)) {
            damagedChunks.put(c.key(), c);
        }
        for (Chunk victim : victims) {
            assertEquals("K7", expected.ruleByChunk.get(victim.key()), "the test reader cannot read it either");
        }

        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.PRUNE));

        EngineFixtures.assertMatches(expected, readJson(result.jsonReport()));
        Map<String, Object> report = readJson(result.jsonReport());
        assertEquals(expected.pruned.size(), number(overworld(report).get("chunksPruned")));
        String warnings = report.get("warnings").toString();
        Map<Long, Chunk> after = new java.util.HashMap<>();
        for (Chunk c : ReferenceWorld.read(world).chunks("region", true)) {
            after.put(c.key(), c);
        }
        for (Chunk victim : victims) {
            assertTrue(warnings.contains("chunkX=" + victim.x() + ", chunkZ=" + victim.z()), "warned: " + victim.x());
            assertArrayEquals(
                    damagedChunks.get(victim.key()).sectors(),
                    after.get(victim.key()).sectors());
        }
        assertTrue(warnings.contains("unknown compression unknown (9)"));
        assertTrue(warnings.contains("unknown compression custom"));
        assertTrue(warnings.contains(
                "external file c." + victims.get(2).x() + "." + victims.get(2).z() + ".mcc is missing"));
    }

    private static boolean isNeighbourOf(List<Chunk> chosen, Chunk c) {
        return chosen.stream().anyMatch(o -> Math.abs(o.x() - c.x()) <= 2 && Math.abs(o.z() - c.z()) <= 2);
    }

    private enum Damage {
        UNKNOWN_COMPRESSION,
        CORRUPT_DATA,
        MISSING_EXTERNAL_FILE,
        CUSTOM_COMPRESSION
    }

    private static void damage(Path world, ReferenceWorld reference, Chunk chunk, Damage damage) throws Exception {
        Path file = reference.overworld().resolve("region").resolve(chunk.file());
        long start = chunk.sectorOffset() * 4096L;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            switch (damage) {
                case UNKNOWN_COMPRESSION -> {
                    raf.seek(start + 4);
                    raf.write(9);
                }
                case CORRUPT_DATA -> {
                    raf.seek(start);
                    int length = raf.readInt();
                    raf.seek(start + 5 + length / 2);
                    raf.write(new byte[Math.min(64, length / 4)]);
                }
                case MISSING_EXTERNAL_FILE -> {
                    raf.seek(start + 4);
                    int type = raf.read();
                    raf.seek(start + 4);
                    raf.write(type | 0x80);
                }
                case CUSTOM_COMPRESSION -> {
                    raf.seek(start + 4);
                    raf.write(127);
                }
            }
        }
    }
}

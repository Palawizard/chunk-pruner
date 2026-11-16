package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.assertMatches;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.anvil.Compression;
import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The 1.21.1 world saved with {@code region-file-compression=lz4}. The tests have lz4-java on
 * their class path; a class loader that cannot see it stands for the command line run without it.
 */
class Lz4FixtureTest {
    @TempDir
    Path temp;

    @Test
    void lz4ChunksAreReadWhenTheLibraryIsThere() throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed(FixtureWorlds.LZ4), temp.resolve("world"));
        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.DRY_RUN));
        assertTrue(result.report().lz4Available());
        Expected expected = Expected.compute(ReferenceWorld.read(world), 0, 4, 1, true);
        assertEquals(0L, expected.keptByRule.get("K7"));
        assertMatches(expected, readJson(result.jsonReport()));
    }

    @Test
    void withoutTheLibraryEveryLz4ChunkIsKeptAndReported() throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed(FixtureWorlds.LZ4), temp.resolve("world"));
        Compression without = Compression.detect(new ClassLoader(null) {});
        assertFalse(without.lz4Available());
        RunSettings run = settings(world, Config.defaults(), RunMode.PRUNE).withCompression(without);
        Map<String, String> before = ReferenceWorld.checksums(world);

        Engine.RunResult result = Engine.run(run);

        Expected expected = Expected.compute(ReferenceWorld.read(world), 0, 4, 1, false);
        assertEquals(0, expected.pruned.size(), "nothing can be read, so nothing goes");
        assertMatches(expected, readJson(result.jsonReport()));
        String warnings = readJson(result.jsonReport()).get("warnings").toString();
        assertTrue(warnings.contains("LZ4 compression and no LZ4 library on the class path"));
        Map<String, String> after = new java.util.HashMap<>(ReferenceWorld.checksums(world));
        after.keySet().removeIf(p -> p.startsWith("chunkpruner/"));
        assertEquals(before, after);
    }
}

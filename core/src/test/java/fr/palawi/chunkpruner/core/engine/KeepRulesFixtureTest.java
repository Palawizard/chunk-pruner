package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S3, S4, S8, K3 and K5 on the keep worlds. The chunks and what they hold come from how the
 * worlds were made (scripts/make-fixture-world.ps1 -Plan keep, see each NOTE.md), not from the
 * engine: chunk row z=-10 holds one setup per chunk, chunk (-9, -10) is force-loaded, the plains
 * village starts in chunk (0, 14) and the player placed glass in its street in chunk (5, 14).
 */
class KeepRulesFixtureTest {
    /** Chunk x of row z=-10, what was summoned there, and whether K5 keeps it. */
    private static final Object[][] ROW = {
        {-7, "tamed wolf (S3)", true},
        {-6, "named pig", true},
        {-5, "sheep leashed to a fence", true},
        {-4, "persistent cow", true},
        {-3, "armor stand", true},
        {-2, "item frame with a diamond", true},
        {-1, "minecart", true},
        {0, "boat", true},
        {1, "end crystal placed from the item (ShowBottom false)", true},
        {2, "dropped diamond", true},
        {3, "chest minecart with a loot table, outside any mineshaft", true},
        {4, "end crystal that shows its base, outside the End", true},
        {5, "plain cow", false},
        {6, "nothing", false}
    };

    @TempDir
    Path temp;

    static Set<String> worlds() {
        return Set.copyOf(FixtureWorlds.KEEP);
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void theTestReaderSeesTheWorldAsItWasMade(String name) throws Exception {
        ReferenceWorld world = ReferenceWorld.read(FixtureWorlds.committed(name));
        Map<Long, Chunk> region = byKey(world.chunks("region", false));
        Chunk start = region.get(ReferenceWorld.key(0, 14));
        assertEquals(0, start.inhabitedTime(), "the village start chunk was never simulated");
        assertTrue(region.get(ReferenceWorld.key(5, 14)).inhabitedTime() > 0, "the street chunk was played in");
        assertEquals(Set.of(ReferenceWorld.key(-9, -10)), world.forcedChunks());
        for (Object[] setup : ROW) {
            assertEquals(0, region.get(ReferenceWorld.key((int) setup[0], -10)).inhabitedTime(), (String) setup[1]);
        }
    }

    /** Defaults: the reasons the test reader gives match how the world was made. */
    @ParameterizedTest
    @MethodSource("worlds")
    void eachSetupIsDecidedByTheExpectedRule(String name) throws Exception {
        Expected expected =
                Expected.compute(ReferenceWorld.read(FixtureWorlds.committed(name)), Expected.Rules.defaults(), true);
        assertEquals("K4", expected.ruleByChunk.get(ReferenceWorld.key(0, 14)), "S4: the village start");
        assertEquals("K1", expected.ruleByChunk.get(ReferenceWorld.key(5, 14)), "the street the player changed");
        assertEquals("K3", expected.ruleByChunk.get(ReferenceWorld.key(-9, -10)), "the force-loaded chunk");
        for (Object[] setup : ROW) {
            String rule = expected.ruleByChunk.get(ReferenceWorld.key((int) setup[0], -10));
            assertEquals((boolean) setup[2] ? "K5" : null, rule, (String) setup[1]);
        }
    }

    /** Each rule on and off, by pruning a copy with the engine and reading what is left. */
    @ParameterizedTest
    @MethodSource("worlds")
    void pruneKeepsWhatEachRuleProtects(String name) throws Exception {
        Set<Long> defaults = remainingAfterPrune(name, "{}");
        assertTrue(defaults.contains(ReferenceWorld.key(0, 14)), "S4: the village start is kept");
        assertTrue(defaults.contains(ReferenceWorld.key(-9, -10)), "K3: the forced chunk is kept");
        for (Object[] setup : ROW) {
            assertEquals(
                    (boolean) setup[2],
                    defaults.contains(ReferenceWorld.key((int) setup[0], -10)),
                    "after prune: " + setup[1]);
        }
        Set<Long> noRules = remainingAfterPrune(
                name,
                "{\"protectForcedChunks\": false, \"protectStructures\": false, \"protectPlayerEntities\": false}");
        assertTrue(!noRules.contains(ReferenceWorld.key(0, 14)), "without K4 the village start goes");
        assertTrue(!noRules.contains(ReferenceWorld.key(-9, -10)), "without K3 the forced chunk goes");
        for (Object[] setup : ROW) {
            assertTrue(!noRules.contains(ReferenceWorld.key((int) setup[0], -10)), "without K5: " + setup[1]);
        }
        assertTrue(noRules.contains(ReferenceWorld.key(5, 14)), "K1 still keeps the street");
    }

    /** S8: the datapack dimension is left alone unless the config lists it. */
    @ParameterizedTest
    @MethodSource("worlds")
    void aDimensionThatIsNotIncludedIsUntouched(String name) throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed(name), temp.resolve("default"));
        Map<String, String> before = mining(world);
        assertTrue(before.keySet().stream().anyMatch(p -> p.contains("/region/")), "the dimension has region files");
        Engine.run(settings(world, Config.defaults(), RunMode.PRUNE));
        assertEquals(before, mining(world), "examplemod:mining is not in dimensions.include");

        Path included = FixtureWorlds.copy(FixtureWorlds.committed(name), temp.resolve("included"));
        Config config =
                Config.fromJson("{\"dimensions\": {\"include\": [\"minecraft:overworld\", \"examplemod:mining\"]}}");
        Engine.run(settings(included, config, RunMode.PRUNE));
        assertNotEquals(before, mining(included), "listed in dimensions.include, it is pruned");
    }

    private Set<Long> remainingAfterPrune(String name, String config) throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed(name), temp.resolve("w" + config.hashCode()));
        Engine.run(settings(world, Config.fromJson(config), RunMode.PRUNE));
        Set<Long> keys = new HashSet<>();
        for (Chunk c : ReferenceWorld.read(world).chunks("region", false)) {
            keys.add(c.key());
        }
        return keys;
    }

    private static Map<String, String> mining(Path world) throws Exception {
        Map<String, String> sums = new HashMap<>(ReferenceWorld.checksums(world));
        sums.keySet().removeIf(p -> !p.startsWith("dimensions/examplemod/mining/"));
        return sums;
    }

    private static Map<Long, Chunk> byKey(java.util.List<Chunk> chunks) {
        Map<Long, Chunk> map = new HashMap<>();
        for (Chunk c : chunks) {
            map.put(c.key(), c);
        }
        return map;
    }
}

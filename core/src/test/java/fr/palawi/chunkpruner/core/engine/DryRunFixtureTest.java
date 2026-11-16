package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.assertMatches;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.config;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds.Fixture;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** S1: a dry run changes no file, and its counts match the values of the test reader. */
class DryRunFixtureTest {
    @TempDir
    Path temp;

    static List<Fixture> fixtures() throws Exception {
        return FixtureWorlds.all();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void dryRunChangesNothingAndCountsMatch(Fixture fixture) throws Exception {
        Path world = FixtureWorlds.copy(fixture.world(), temp.resolve("world"));
        Map<String, String> before = ReferenceWorld.checksums(world);

        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.DRY_RUN));

        Map<String, String> after = new HashMap<>(ReferenceWorld.checksums(world));
        String json = "chunkpruner/reports/" + result.report().runId() + ".json";
        String text = "chunkpruner/reports/" + result.report().runId() + ".txt";
        assertTrue(after.remove(json) != null && after.remove(text) != null, "both reports were written");
        assertEquals(before, after, "a dry run changes no file of the world");

        Expected expected = Expected.compute(ReferenceWorld.read(world), 0, 4, 1, true);
        assertTrue(expected.pruned.size() > 0 && expected.keptByRule.get("K1") > 0);
        assertMatches(expected, readJson(result.jsonReport()));
        assertEquals(expectedSummary(expected), result.summary());
        assertTrue(Files.readString(result.textReport()).startsWith(expectedSummary(expected) + "\n"));
    }

    /**
     * The worlds must have chunks over several sectors, which the rewrite copies whole. The game
     * only writes a .mcc file for a chunk of 1 MiB or more; when a world has one, the other tests
     * cover it like any other chunk (the CI job lists the .mcc files it finds).
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void fixturesHaveMultiSectorChunks(Fixture fixture) throws Exception {
        ReferenceWorld world = ReferenceWorld.read(fixture.world());
        assertTrue(world.chunks("region", true).stream().anyMatch(c -> c.sectorCount() >= 2));
    }

    /** K8 at radius 0 is off, and a wider radius keeps more chunks next to the played ones. */
    @ParameterizedTest
    @MethodSource("fixtures")
    void neighborRadiusChangesWhatIsKept(Fixture fixture) throws Exception {
        Path world = FixtureWorlds.copy(fixture.world(), temp.resolve("world"));
        ReferenceWorld reference = ReferenceWorld.read(world);
        for (int neighbors : new int[] {0, 1, 3}) {
            Engine.RunResult result = Engine.run(settings(world, config(4, neighbors), RunMode.DRY_RUN));
            Expected expected = Expected.compute(reference, 0, 4, neighbors, true);
            assertMatches(expected, readJson(result.jsonReport()));
            if (neighbors == 0) {
                assertEquals(0L, expected.keptByRule.get("K8"));
            } else {
                assertTrue(expected.keptByRule.get("K8") > 0, "K8 keeps chunks at radius " + neighbors);
            }
        }
    }

    /** K6 boxes, in block coordinates, here over part of the flown-over area. */
    @ParameterizedTest
    @MethodSource("fixtures")
    void protectedAreaKeepsItsChunks(Fixture fixture) throws Exception {
        Path world = FixtureWorlds.copy(fixture.world(), temp.resolve("world"));
        Config config = Config.fromJson("{\"protectedAreas\": [{\"dimension\": \"minecraft:overworld\","
                + " \"minX\": -200, \"minZ\": 300, \"maxX\": 200, \"maxZ\": 500}]}");
        Engine.RunResult result = Engine.run(settings(world, config, RunMode.DRY_RUN));
        Expected expected =
                Expected.compute(ReferenceWorld.read(world), 0, 4, 1, new int[][] {{-200, 300, 200, 500}}, true);
        assertTrue(expected.keptByRule.get("K6") > 0);
        assertMatches(expected, readJson(result.jsonReport()));
    }

    /** SPEC section 6 wording, from the expected values; every fixture has K1, K2 and K8 chunks. */
    private static String expectedSummary(Expected e) {
        long saved = 0;
        for (long[] t : e.storages.values()) {
            saved += t[1] - t[2];
        }
        long kept = e.keptByRule.values().stream().mapToLong(Long::longValue).sum();
        StringBuilder parts = new StringBuilder();
        String[][] names = {
            {"K1", "played in"},
            {"K2", "near players or spawn"},
            {"K3", "force-loaded"},
            {"K4", "holding structure starts"},
            {"K5", "with player entities"},
            {"K6", "in protected areas"},
            {"K7", "unreadable"},
            {"K8", "next to played chunks"}
        };
        for (String[] rule : names) {
            long n = e.keptByRule.get(rule[0]);
            if (n > 0) {
                parts.append(parts.length() == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%,d %s", n, rule[1]));
            }
        }
        return String.format(
                        Locale.ROOT,
                        "Would remove %,d unvisited chunks (%.1f MB) from 1 dimension.",
                        e.pruned.size(),
                        saved / 1e6)
                + String.format(Locale.ROOT, " Would keep %,d chunks: %s.", kept, parts);
    }
}

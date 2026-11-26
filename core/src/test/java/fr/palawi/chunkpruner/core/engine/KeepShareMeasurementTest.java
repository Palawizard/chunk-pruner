package fr.palawi.chunkpruner.core.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.ExpectedEntities;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds.Fixture;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How many of the overworld chunks that K1 and K8 alone would prune are kept by K4 and by K5,
 * computed with the test reader only, with the reasons per entity type. The numbers go to
 * {@code core/build/measurements/}, which the CI job prints for the normal-terrain worlds. K5 must
 * not keep more than 10 percent of those chunks: above that the rule would empty the mod of its
 * point (decided on 2026-11-16).
 */
class KeepShareMeasurementTest {
    static Stream<Fixture> worlds() throws Exception {
        return FixtureWorlds.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    void k4AndK5KeepFewOfTheChunksK1AndK8WouldPrune(Fixture fixture) throws Exception {
        ReferenceWorld world = ReferenceWorld.read(fixture.world());
        Expected baseline =
                Expected.compute(world, new Expected.Rules(0, -1, 1, new int[0][], false, false, false), true);
        Set<Long> prunable = baseline.pruned;
        Expected withK4 = Expected.compute(world, new Expected.Rules(0, -1, 1, new int[0][], false, true, false), true);
        long k4 = prunable.stream()
                .filter(k -> "K4".equals(withK4.ruleByChunk.get(k)))
                .count();

        Set<Long> overworld = new HashSet<>();
        for (Chunk c : world.chunks("region", true)) {
            overworld.add(c.key());
        }
        Map<String, Long> kept = new TreeMap<>();
        Set<Long> k5Chunks = new HashSet<>();
        Set<List<Integer>> players = world.players();
        for (Chunk entities : world.chunks("entities", true)) {
            if (!prunable.contains(entities.key()) || !ExpectedEntities.readable(entities)) {
                continue;
            }
            Set<String> reasons = new HashSet<>();
            collect((List<?>) entities.nbt().getOrDefault("Entities", List.of()), players, reasons);
            reasons.forEach(r -> kept.merge(r, 1L, Long::sum));
            if (!reasons.isEmpty()) {
                k5Chunks.add(entities.key());
            }
        }
        double k5Share = 100.0 * k5Chunks.size() / Math.max(1, prunable.size());
        String text = String.format(
                Locale.ROOT,
                "%s: %d overworld chunks, %d prunable by K1 and K8 alone.%n"
                        + "K4 keeps %d of them (%.2f %%).%n"
                        + "K5 keeps %d of them (%.2f %%): %s%n",
                fixture.label(),
                overworld.size(),
                prunable.size(),
                k4,
                100.0 * k4 / Math.max(1, prunable.size()),
                k5Chunks.size(),
                k5Share,
                kept);
        Path out = Path.of("build", "measurements", fixture.label().replace(' ', '-') + ".txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
        if (!fixture.label().endsWith("-keep")) {
            assertTrue(k5Share <= 10.0, text);
        }
    }

    private static void collect(List<?> list, Set<List<Integer>> players, Set<String> reasons) {
        for (Object o : list) {
            if (o instanceof Map<?, ?> e) {
                String why = ExpectedEntities.why(e, players);
                if (why != null) {
                    reasons.add(why);
                }
                if (e.get("Passengers") instanceof List<?> passengers) {
                    collect(passengers, players, reasons);
                }
            }
        }
    }
}

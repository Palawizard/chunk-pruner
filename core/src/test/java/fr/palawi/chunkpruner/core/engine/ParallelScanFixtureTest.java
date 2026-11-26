package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds.Fixture;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The scan reads region files on several threads (ARCHITECTURE, Concurrency and safety): a run
 * with 4 threads must give the same report, the same warnings in the same order, and the same
 * files as a run with one thread, on every fixture world, and on a copy with an unreadable region
 * file and a damaged chunk.
 */
class ParallelScanFixtureTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temp;

    static Stream<Fixture> worlds() throws Exception {
        return FixtureWorlds.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    void dryRunAndPruneMatchTheSingleThreadRun(Fixture fixture) throws Exception {
        compare(fixture.world(), "clean");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("worlds")
    void damagedRegionsGiveTheSameResult(Fixture fixture) throws Exception {
        Path damaged = FixtureWorlds.copy(fixture.world(), temp.resolve("damaged-source"));
        List<Path> regions = regionFiles(damaged);
        assertTrue(regions.size() >= 3, "the fixture has several region files");
        // A header cut short: the whole file is unreadable and kept.
        try (RandomAccessFile file = new RandomAccessFile(regions.get(0).toFile(), "rw")) {
            file.setLength(100);
        }
        // Garbage in the middle of the second file: some of its chunks cannot be decoded (K7).
        try (RandomAccessFile file = new RandomAccessFile(regions.get(1).toFile(), "rw")) {
            long middle = Math.max(8192, file.length() / 2);
            file.seek(middle);
            file.write(new byte[4096]);
        }
        compare(damaged, "damaged");
    }

    private void compare(Path source, String label) throws Exception {
        Map<String, Object> dryOne = dryRun(source, label + "-dry-1", 1);
        Map<String, Object> dryMany = dryRun(source, label + "-dry-4", 4);
        assertEquals(dryOne, dryMany, "dry run report with 1 and 4 threads");

        Path one = prune(source, label + "-prune-1", 1);
        Path many = prune(source, label + "-prune-4", 4);
        assertEquals(withoutReports(one), withoutReports(many), "files after a prune with 1 and 4 threads");
        assertEquals(
                comparable(readJson(onlyReport(one))),
                comparable(readJson(onlyReport(many))),
                "prune report with 1 and 4 threads");
    }

    private Map<String, Object> dryRun(Path source, String name, int threads) throws Exception {
        Path world = FixtureWorlds.copy(source, temp.resolve(name));
        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.DRY_RUN)
                .withClock(CLOCK)
                .withScanThreads(threads));
        return comparable(readJson(result.jsonReport()));
    }

    private Path prune(Path source, String name, int threads) throws Exception {
        Path world = FixtureWorlds.copy(source, temp.resolve(name));
        Engine.run(settings(world, Config.defaults(), RunMode.PRUNE)
                .withClock(CLOCK)
                .withScanThreads(threads));
        return world;
    }

    /** The report without what differs from one run to the next (its duration). */
    private static Map<String, Object> comparable(Map<String, Object> report) {
        report.remove("durationMillis");
        return report;
    }

    private static Map<String, String> withoutReports(Path world) throws Exception {
        Map<String, String> sums = new java.util.TreeMap<>(ReferenceWorld.checksums(world));
        sums.keySet().removeIf(p -> p.startsWith("chunkpruner/reports/"));
        return sums;
    }

    private static Path onlyReport(Path world) throws Exception {
        try (Stream<Path> files = Files.list(world.resolve("chunkpruner/reports"))) {
            List<Path> json = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertEquals(1, json.size(), "one JSON report");
            return json.get(0);
        }
    }

    private static List<Path> regionFiles(Path world) throws Exception {
        Path folder = world.resolve("region");
        if (!Files.isDirectory(folder)) {
            folder = world.resolve("dimensions/minecraft/overworld/region");
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .filter(p -> {
                        try {
                            return Files.size(p) > 16384;
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .sorted()
                    .toList();
        }
    }
}

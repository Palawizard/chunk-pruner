package fr.palawi.chunkpruner.core.fixture;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import fr.palawi.chunkpruner.core.report.DimensionReport;
import fr.palawi.chunkpruner.core.report.RunReport;
import fr.palawi.chunkpruner.core.report.Storage;
import fr.palawi.chunkpruner.core.report.StorageReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Scan speed for docs/ARCHITECTURE.md: dry runs over a copy of each world given, with K5 on (the
 * default) and off, for each scan thread count given, timed after one warm-up run. Prints the Java
 * version and hardware threads first, then the region files of {@code region/} and the chunks
 * scanned per second, both taken from the report.
 * Usage: {@code <runs> <threads,threads,...> <world>...}; each world is copied to a folder under
 * {@code java.io.tmpdir} first, because a dry run writes its report into the world.
 */
public final class ScanBenchmark {
    private ScanBenchmark() {}

    public static void main(String[] args) throws Exception {
        int runs = Integer.parseInt(args[0]);
        int[] threads =
                Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray();
        System.out.println(
                "Java " + Runtime.version() + ", " + Runtime.getRuntime().availableProcessors()
                        + " hardware threads, max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        for (int i = 2; i < args.length; i++) {
            Path source = Path.of(args[i]);
            Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
            Path temp = Files.createTempDirectory("chunkpruner-benchmark");
            try {
                Path world = FixtureWorlds.copy(source, temp.resolve("world"));
                for (String config : new String[] {"{}", "{\"protectPlayerEntities\": false}"}) {
                    for (int count : threads) {
                        RunSettings settings = RunSettings.of(
                                        world, Config.fromJson(config), RunMode.DRY_RUN, RunContext.E3)
                                .withScanThreads(count);
                        RunReport report = Engine.run(settings).report();
                        long regions = report.dimensions().stream()
                                .map(DimensionReport::storages)
                                .mapToLong(s -> s.getOrDefault(Storage.REGION, StorageReport.EMPTY)
                                        .files())
                                .sum();
                        long chunks = report.scanned();
                        long best = Long.MAX_VALUE;
                        long total = 0;
                        for (int r = 0; r < runs; r++) {
                            long start = System.nanoTime();
                            Engine.run(settings);
                            long nanos = System.nanoTime() - start;
                            best = Math.min(best, nanos);
                            total += nanos;
                        }
                        double seconds = total / 1e9 / runs;
                        System.out.println(String.format(
                                Locale.ROOT,
                                "%s, %s, %d threads: %d region files, %d chunks, mean %.0f ms, best %.0f ms,"
                                        + " %.1f regions/s, %.0f chunks/s (mean)",
                                source,
                                config,
                                count,
                                regions,
                                chunks,
                                seconds * 1000,
                                best / 1e6,
                                regions / seconds,
                                chunks / seconds));
                    }
                }
            } finally {
                try (Stream<Path> files = Files.walk(temp)) {
                    files.sorted(Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                }
            }
        }
    }
}

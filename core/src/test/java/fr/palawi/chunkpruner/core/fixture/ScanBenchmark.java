package fr.palawi.chunkpruner.core.fixture;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Scan speed for docs/ARCHITECTURE.md: dry runs over a copy of each world given, with K5 on (the
 * default) and off, timed after one warm-up run. Prints the region files of {@code region/}
 * and chunks scanned per second. Usage: {@code <runs> <world>...}; each world is copied to a temporary
 * folder first, because a dry run writes its report into the world.
 */
public final class ScanBenchmark {
    private ScanBenchmark() {}

    public static void main(String[] args) throws Exception {
        int runs = Integer.parseInt(args[0]);
        for (int i = 1; i < args.length; i++) {
            Path source = Path.of(args[i]);
            Path temp = Files.createTempDirectory("chunkpruner-benchmark");
            Path world = FixtureWorlds.copy(source, temp.resolve("world"));
            long regions = ReferenceWorld.read(world).files("region").size();
            long chunks = ReferenceWorld.read(world).chunks("region", true).size();
            for (String config : new String[] {"{}", "{\"protectPlayerEntities\": false}"}) {
                RunSettings settings = RunSettings.of(world, Config.fromJson(config), RunMode.DRY_RUN, RunContext.E3);
                Engine.run(settings);
                long best = Long.MAX_VALUE;
                long total = 0;
                for (int r = 0; r < runs; r++) {
                    long start = System.nanoTime();
                    Engine.run(settings);
                    long nanos = System.nanoTime() - start;
                    best = Math.min(best, nanos);
                    total += nanos;
                }
                System.out.println(String.format(
                        Locale.ROOT,
                        "%s, %s: %d region files, %d chunks, mean %.0f ms, best %.0f ms, %.1f regions/s, %.0f chunks/s (mean)",
                        source,
                        config,
                        regions,
                        chunks,
                        total / 1e6 / runs,
                        best / 1e6,
                        regions / (total / 1e9 / runs),
                        chunks / (total / 1e9 / runs)));
            }
            try (Stream<Path> files = Files.walk(temp)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }
}

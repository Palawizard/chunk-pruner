package fr.palawi.chunkpruner.core.fixture;

import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Expected decisions for the overworld of a fixture, computed from the test reader and the rule
 * definitions of SPEC section 2, never from the engine.
 */
public final class Expected {
    public final Map<Long, String> ruleByChunk = new HashMap<>();
    public final Set<Long> pruned = new HashSet<>();
    public final Map<String, Long> keptByRule = new TreeMap<>();
    public final Map<String, long[]> storages = new LinkedHashMap<>();

    private Expected() {}

    /** files, bytes before, bytes after, files changed, files deleted, per storage. */
    public static Expected compute(ReferenceWorld world, long threshold, int radius, int neighborRadius, boolean lz4)
            throws IOException {
        return compute(world, threshold, radius, neighborRadius, new int[0][], lz4);
    }

    /** {@code areas}: overworld boxes in block coordinates, {minX, minZ, maxX, maxZ}. */
    public static Expected compute(
            ReferenceWorld world, long threshold, int radius, int neighborRadius, int[][] areas, boolean lz4)
            throws IOException {
        Expected e = new Expected();
        for (String rule : List.of("K1", "K2", "K6", "K7", "K8")) {
            e.keptByRule.put(rule, 0L);
        }
        List<Chunk> region = world.chunks("region", lz4);
        Set<Long> k1 = new HashSet<>();
        for (Chunk c : region) {
            if (c.readable() && c.inhabitedTime() > threshold && positionMatches(c)) {
                k1.add(c.key());
            }
        }
        Set<String> notRewritable = notRewritableFiles(world);
        for (Chunk c : region) {
            boolean readable = c.readable() && positionMatches(c);
            String rule = null;
            if (readable && k1.contains(c.key())) {
                rule = "K1";
            } else if (nearPoint(world, c, radius)) {
                rule = "K2";
            } else if (inArea(areas, c)) {
                rule = "K6";
            } else if (!readable) {
                rule = "K7";
            } else if (neighborRadius > 0 && nearPlayed(k1, c, neighborRadius)) {
                rule = "K8";
            } else if (notRewritable.contains(c.file())) {
                rule = "K7";
            }
            if (rule == null) {
                e.pruned.add(c.key());
            } else {
                e.ruleByChunk.put(c.key(), rule);
                e.keptByRule.merge(rule, 1L, Long::sum);
            }
        }
        for (String storage : ReferenceWorld.STORAGES) {
            e.storages.put(storage, sizes(world, storage, e.pruned, lz4));
        }
        return e;
    }

    private static boolean positionMatches(Chunk c) {
        return c.nbt().get("xPos") instanceof Integer x
                && c.nbt().get("zPos") instanceof Integer z
                && x == c.x()
                && z == c.z();
    }

    private static boolean nearPoint(ReferenceWorld world, Chunk c, int radius) {
        for (int[] p : world.overworldPoints()) {
            if (Math.max(Math.abs(p[0] - c.x()), Math.abs(p[1] - c.z())) <= radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean inArea(int[][] areas, Chunk c) {
        for (int[] a : areas) {
            int minBlockX = c.x() * 16;
            int minBlockZ = c.z() * 16;
            if (minBlockX + 15 >= a[0] && minBlockX <= a[2] && minBlockZ + 15 >= a[1] && minBlockZ <= a[3]) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearPlayed(Set<Long> k1, Chunk c, int distance) {
        for (int dx = -distance; dx <= distance; dx++) {
            for (int dz = -distance; dz <= distance; dz++) {
                long key = (long) (c.x() + dx) << 32 | ((c.z() + dz) & 0xFFFFFFFFL);
                if ((dx != 0 || dz != 0) && k1.contains(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> notRewritableFiles(ReferenceWorld world) throws IOException {
        Set<String> result = new HashSet<>();
        for (String storage : ReferenceWorld.STORAGES) {
            for (Path file : world.files(storage)) {
                long size = Files.size(file);
                boolean bad = size > 0 && size < 8192;
                if (!bad) {
                    for (Chunk c : ReferenceWorld.chunks(storage, file, false)) {
                        bad |= c.sectors() == null;
                    }
                }
                if (bad) {
                    result.add(file.getFileName().toString());
                }
            }
        }
        return result;
    }

    private static long[] sizes(ReferenceWorld world, String storage, Set<Long> pruned, boolean lz4)
            throws IOException {
        long[] t = new long[5];
        Path folder = world.overworld().resolve(storage);
        if (Files.isDirectory(folder)) {
            try (var files = Files.list(folder)) {
                for (Path f : files.toList()) {
                    String name = f.getFileName().toString();
                    if (name.matches("r\\.-?\\d+\\.-?\\d+\\.mca") || name.endsWith(".mcc")) {
                        t[1] += Files.size(f);
                    }
                }
            }
        }
        long after = t[1];
        for (Path file : world.files(storage)) {
            t[0]++;
            long keptSectors = 0;
            boolean changed = false;
            for (Chunk c : ReferenceWorld.chunks(storage, file, lz4)) {
                if (pruned.contains(c.key())) {
                    changed = true;
                    if (c.external()) {
                        after -= Files.size(file.resolveSibling("c." + c.x() + "." + c.z() + ".mcc"));
                    }
                } else {
                    keptSectors += c.sectorCount();
                }
            }
            if (changed) {
                long newSize = keptSectors == 0 ? 0 : 8192 + keptSectors * 4096;
                after -= Files.size(file) - newSize;
                t[newSize == 0 ? 4 : 3]++;
            }
        }
        t[2] = after;
        return t;
    }
}

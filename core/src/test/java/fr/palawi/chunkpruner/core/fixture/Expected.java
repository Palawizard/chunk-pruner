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
        return compute(world, new Rules(threshold, radius, neighborRadius, areas, true, true, true), lz4);
    }

    /** The settings of the rules, as in the config of SPEC section 9. */
    public record Rules(
            long threshold,
            int radius,
            int neighborRadius,
            int[][] areas,
            boolean forced,
            boolean structures,
            boolean entities) {
        public static Rules defaults() {
            return new Rules(0, 4, 1, new int[0][], true, true, true);
        }

        public Rules withK3K4K5(boolean k3, boolean k4, boolean k5) {
            return new Rules(threshold, radius, neighborRadius, areas, k3, k4, k5);
        }
    }

    public static Expected compute(ReferenceWorld world, Rules rules, boolean lz4) throws IOException {
        Expected e = new Expected();
        for (String rule : List.of("K1", "K2", "K3", "K4", "K5", "K6", "K7", "K8")) {
            e.keptByRule.put(rule, 0L);
        }
        List<Chunk> region = world.chunks("region", lz4);
        Map<Long, Chunk> entities = new HashMap<>();
        for (Chunk c : world.chunks("entities", lz4)) {
            entities.put(c.key(), c);
        }
        Set<Long> k1 = new HashSet<>();
        for (Chunk c : region) {
            if (c.readable() && c.inhabitedTime() > rules.threshold() && positionMatches(c)) {
                k1.add(c.key());
            }
        }
        Set<Long> forced = rules.forced() ? world.forcedChunks() : Set.of();
        Set<String> notRewritable = notRewritableFiles(world);
        Map<Long, String> withoutK4 = new HashMap<>();
        Set<Long> unreadable = new HashSet<>();
        Set<Long> regionUnreadable = new HashSet<>();
        Set<Long> k5 = new HashSet<>();
        for (Chunk c : region) {
            boolean readable = c.readable() && positionMatches(c);
            if (!readable) {
                regionUnreadable.add(c.key());
            }
            Chunk entityChunk = entities.get(c.key());
            if (readable && rules.entities() && entityChunk != null) {
                if (!ExpectedEntities.readable(entityChunk)) {
                    readable = false;
                } else if (ExpectedEntities.keeps(entityChunk, c, world.players(), "minecraft:overworld")) {
                    k5.add(c.key());
                }
            }
            if (!readable) {
                unreadable.add(c.key());
            }
            String rule = null;
            if (!unreadable.contains(c.key()) && k1.contains(c.key())) {
                rule = "K1";
            } else if (nearPoint(world, c, rules.radius())) {
                rule = "K2";
            } else if (forced.contains(c.key())) {
                rule = "K3";
            } else if (k5.contains(c.key())) {
                rule = "K5";
            } else if (inArea(rules.areas(), c)) {
                rule = "K6";
            } else if (unreadable.contains(c.key())) {
                rule = "K7";
            } else if (rules.neighborRadius() > 0 && nearPlayed(k1, c, rules.neighborRadius())) {
                rule = "K8";
            } else if (notRewritable.contains(c.file())) {
                rule = "K7";
            }
            withoutK4.put(c.key(), rule);
        }
        Set<Long> k4 = rules.structures() ? referencedStarts(region, regionUnreadable, withoutK4) : Set.of();
        for (Chunk c : region) {
            String rule = withoutK4.get(c.key());
            if (k4.contains(c.key()) && (rule == null || rule.compareTo("K4") > 0)) {
                rule = "K4";
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

    /**
     * Start chunks referenced, for the structure that starts there, by another kept chunk; a
     * chunk found this way counts as kept in turn. Computed again until nothing changes.
     */
    private static Set<Long> referencedStarts(List<Chunk> region, Set<Long> unreadable, Map<Long, String> rules) {
        Map<Long, Map<String, Object>> structures = new HashMap<>();
        for (Chunk c : region) {
            if (!unreadable.contains(c.key()) && ReferenceNbt.compound(c.nbt(), "structures") != null) {
                structures.put(c.key(), ReferenceNbt.compound(c.nbt(), "structures"));
            }
        }
        Set<Long> referenced = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Long, Map<String, Object>> from : structures.entrySet()) {
                if (rules.get(from.getKey()) == null && !referenced.contains(from.getKey())) {
                    continue;
                }
                Map<String, Object> refs = ReferenceNbt.compound(from.getValue(), "References");
                if (refs == null) {
                    continue;
                }
                for (Map.Entry<String, Object> ref : refs.entrySet()) {
                    for (long target : (long[]) ref.getValue()) {
                        int x = (int) target;
                        int z = (int) (target >> 32);
                        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
                        if (key != from.getKey()
                                && startsThere(structures.get(key), ref.getKey())
                                && referenced.add(key)) {
                            changed = true;
                        }
                    }
                }
            }
        }
        return referenced;
    }

    private static boolean startsThere(Map<String, Object> structures, String id) {
        Map<String, Object> starts = structures == null ? null : ReferenceNbt.compound(structures, "starts");
        return starts != null && starts.get(id) instanceof Map<?, ?> start && !"INVALID".equals(start.get("id"));
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

package fr.palawi.chunkpruner.core.rules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * The structure starts of one dimension and the chunks that reference them (K4). Chunk positions
 * are packed longs, as in the game's {@code ChunkPos.pack}; structure ids are kept once each.
 */
public final class StructureGraph {
    private final Map<String, Integer> ids = new HashMap<>();
    private final Map<Long, int[]> starts = new HashMap<>();
    private final Map<Long, long[][]> references = new HashMap<>();
    private final Map<Long, int[]> referenceIds = new HashMap<>();
    private final Set<Long> referenced = new HashSet<>();

    public static long pack(int chunkX, int chunkZ) {
        return chunkX & 0xFFFFFFFFL | (chunkZ & 0xFFFFFFFFL) << 32;
    }

    public void add(int chunkX, int chunkZ, Set<String> chunkStarts, Map<String, long[]> chunkReferences) {
        long key = pack(chunkX, chunkZ);
        if (!chunkStarts.isEmpty()) {
            int[] startIds = new int[chunkStarts.size()];
            int i = 0;
            for (String start : chunkStarts) {
                startIds[i++] = id(start);
            }
            starts.put(key, startIds);
        }
        if (!chunkReferences.isEmpty()) {
            long[][] positions = new long[chunkReferences.size()][];
            int[] structureIds = new int[chunkReferences.size()];
            int i = 0;
            for (Map.Entry<String, long[]> entry : chunkReferences.entrySet()) {
                structureIds[i] = id(entry.getKey());
                positions[i++] = entry.getValue();
            }
            references.put(key, positions);
            referenceIds.put(key, structureIds);
        }
    }

    /** How far a reference reaches: the game drops one more than 8 chunks away. */
    public static final int REFERENCE_RANGE = 8;

    /**
     * Computes which start chunks are referenced, for the structure that starts there, by another
     * kept chunk. {@code keptByOtherRules} says whether a chunk is kept by a rule other than K4;
     * {@code unknown} whether a chunk's references cannot be read (its {@code region/} entry or
     * file is unreadable). A start within {@link #REFERENCE_RANGE} chunks of an unknown chunk may
     * be referenced by it, so it is kept. A chunk found here is kept too, so its own references
     * count in turn.
     */
    public void resolve(LongPredicate keptByOtherRules, LongPredicate unknown) {
        referenced.clear();
        Set<Long> kept = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (Long start : starts.keySet()) {
            if (nearUnknown(start, unknown)) {
                referenced.add(start);
                kept.add(start);
                queue.add(start);
            }
        }
        for (Long chunk : references.keySet()) {
            if (keptByOtherRules.test(chunk)) {
                kept.add(chunk);
                queue.add(chunk);
            }
        }
        while (!queue.isEmpty()) {
            long from = queue.poll();
            long[][] positions = references.get(from);
            if (positions == null) {
                continue;
            }
            int[] structureIds = referenceIds.get(from);
            for (int i = 0; i < positions.length; i++) {
                for (long target : positions[i]) {
                    if (target != from && startsHere(target, structureIds[i])) {
                        referenced.add(target);
                        if (kept.add(target)) {
                            queue.add(target);
                        }
                    }
                }
            }
        }
    }

    /** After {@link #resolve}: true when K4 matches the chunk. */
    public boolean isReferencedStart(int chunkX, int chunkZ) {
        return referenced.contains(pack(chunkX, chunkZ));
    }

    private static boolean nearUnknown(long chunk, LongPredicate unknown) {
        int x = (int) chunk;
        int z = (int) (chunk >> 32);
        for (int dz = -REFERENCE_RANGE; dz <= REFERENCE_RANGE; dz++) {
            for (int dx = -REFERENCE_RANGE; dx <= REFERENCE_RANGE; dx++) {
                if ((dx != 0 || dz != 0) && unknown.test(pack(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean startsHere(long chunk, int structureId) {
        int[] startIds = starts.get(chunk);
        if (startIds == null) {
            return false;
        }
        for (int id : startIds) {
            if (id == structureId) {
                return true;
            }
        }
        return false;
    }

    private int id(String name) {
        return ids.computeIfAbsent(name, n -> ids.size());
    }
}

package fr.palawi.chunkpruner.core.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * The structure starts of one dimension and the chunks that reference them (K4). Chunk positions
 * are packed longs, as in the game's {@code ChunkPos.pack}; structure ids are kept once each.
 */
public final class StructureGraph {
    private final Map<String, Integer> ids = new HashMap<>();
    private final List<String> names = new ArrayList<>();
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

    /** The ids of the structures that start in the chunk or reference it. */
    public Set<String> structuresAt(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        Set<String> result = new HashSet<>();
        int[] startIds = starts.get(key);
        if (startIds != null) {
            for (int id : startIds) {
                result.add(names.get(id));
            }
        }
        int[] refIds = referenceIds.get(key);
        if (refIds != null) {
            for (int id : refIds) {
                result.add(names.get(id));
            }
        }
        return result;
    }

    /**
     * Computes which start chunks are referenced, for the structure that starts there, by another
     * kept chunk. {@code keptByOtherRules} says whether a chunk is kept by a rule other than K4;
     * a chunk found here is kept too, so its own references count in turn.
     */
    public void resolve(LongPredicate keptByOtherRules) {
        referenced.clear();
        Set<Long> kept = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
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
        return ids.computeIfAbsent(name, n -> {
            names.add(n);
            return names.size() - 1;
        });
    }
}

package fr.palawi.chunkpruner.core.rules;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** The chunks of one dimension kept by K1, one bit per chunk, grouped by region. */
public final class PlayedChunks {
    private final Map<Long, BitSet> regions = new HashMap<>();

    public void add(int chunkX, int chunkZ) {
        regions.computeIfAbsent(regionKey(chunkX >> 5, chunkZ >> 5), k -> new BitSet(1024))
                .set(index(chunkX, chunkZ));
    }

    public boolean contains(int chunkX, int chunkZ) {
        BitSet bits = regions.get(regionKey(chunkX >> 5, chunkZ >> 5));
        return bits != null && bits.get(index(chunkX, chunkZ));
    }

    /** True when a played chunk lies within the given Chebyshev distance, the chunk itself excluded. */
    public boolean anyWithin(int chunkX, int chunkZ, int distance) {
        for (int dz = -distance; dz <= distance; dz++) {
            for (int dx = -distance; dx <= distance; dx++) {
                if ((dx != 0 || dz != 0) && contains(chunkX + dx, chunkZ + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int index(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    private static long regionKey(int regionX, int regionZ) {
        return (long) regionX << 32 | (regionZ & 0xFFFFFFFFL);
    }
}

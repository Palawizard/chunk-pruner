package fr.palawi.chunkpruner.core.rules;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.Config.ProtectedArea;
import fr.palawi.chunkpruner.core.world.DimensionId;
import fr.palawi.chunkpruner.core.world.ProtectedPoint;
import java.util.List;

/**
 * Decides one chunk of one dimension. K1 and K8 need the scan of the whole dimension first:
 * {@link PlayedChunks} holds the chunks kept by K1.
 */
public final class KeepRules {
    private final DimensionId dimension;
    private final long threshold;
    private final int radius;
    private final int neighborRadius;
    private final List<ProtectedPoint> points;
    private final List<ProtectedArea> areas;

    public KeepRules(Config config, DimensionId dimension, List<ProtectedPoint> allPoints) {
        this.dimension = dimension;
        this.threshold = config.inhabitedThresholdTicks();
        this.radius = config.protectRadiusChunks();
        this.neighborRadius = config.protectNeighborRadiusChunks();
        this.points =
                allPoints.stream().filter(p -> p.dimension().equals(dimension)).toList();
        this.areas = config.protectedAreas().stream()
                .filter(a -> a.dimension().equals(dimension))
                .toList();
    }

    /** K1 alone, used while scanning to fill {@link PlayedChunks}. */
    public boolean played(long inhabitedTime) {
        return inhabitedTime > threshold;
    }

    /**
     * The rule that keeps the chunk, or null when it can be pruned. {@code readable} is false
     * for a chunk that cannot be read or understood; {@code played} is the K1 result of a
     * readable chunk.
     */
    public KeepRule decide(int chunkX, int chunkZ, boolean readable, boolean played, PlayedChunks playedChunks) {
        if (readable && played) {
            return KeepRule.K1;
        }
        for (ProtectedPoint point : points) {
            if (Math.abs(point.chunkX() - chunkX) <= radius && Math.abs(point.chunkZ() - chunkZ) <= radius) {
                return KeepRule.K2;
            }
        }
        for (ProtectedArea area : areas) {
            if (area.overlapsChunk(dimension, chunkX, chunkZ)) {
                return KeepRule.K6;
            }
        }
        if (!readable) {
            return KeepRule.K7;
        }
        if (neighborRadius > 0 && playedChunks.anyWithin(chunkX, chunkZ, neighborRadius)) {
            return KeepRule.K8;
        }
        return null;
    }
}

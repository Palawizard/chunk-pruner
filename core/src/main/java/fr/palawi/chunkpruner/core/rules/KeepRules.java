package fr.palawi.chunkpruner.core.rules;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.Config.ProtectedArea;
import fr.palawi.chunkpruner.core.world.DimensionId;
import fr.palawi.chunkpruner.core.world.ProtectedPoint;
import java.util.List;

/**
 * Decides one chunk of one dimension. K1, K4 and K8 need the scan of the whole dimension first:
 * {@link PlayedChunks} holds the chunks kept by K1, and the {@link StructureGraph} is resolved
 * against the chunks kept by the other rules.
 */
public final class KeepRules {
    private final DimensionId dimension;
    private final long threshold;
    private final int radius;
    private final int neighborRadius;
    private final boolean forcedChunks;
    private final boolean structures;
    private final boolean playerEntities;
    private final List<ProtectedPoint> points;
    private final List<ProtectedArea> areas;

    public KeepRules(Config config, DimensionId dimension, List<ProtectedPoint> allPoints) {
        this.dimension = dimension;
        this.threshold = config.inhabitedThresholdTicks();
        this.radius = config.protectRadiusChunks();
        this.neighborRadius = config.protectNeighborRadiusChunks();
        this.forcedChunks = config.protectForcedChunks();
        this.structures = config.protectStructures();
        this.playerEntities = config.protectPlayerEntities();
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

    public boolean checksForcedChunks() {
        return forcedChunks;
    }

    public boolean checksStructures() {
        return structures;
    }

    public boolean checksPlayerEntities() {
        return playerEntities;
    }

    /**
     * What the scan found about one chunk. {@code readable} is false when the chunk, or its
     * {@code entities/} entry while K5 is on, cannot be read or understood; {@code played} is the
     * K1 result of a readable chunk; {@code forced}, {@code referencedStart} and
     * {@code playerEntity} are the K3, K4 and K5 findings, whatever the config says.
     */
    public record Facts(
            int chunkX,
            int chunkZ,
            boolean readable,
            boolean played,
            boolean forced,
            boolean referencedStart,
            boolean playerEntity) {}

    /** The rule that keeps the chunk, or null when it can be pruned. */
    public KeepRule decide(Facts chunk, PlayedChunks playedChunks) {
        return rule(chunk, playedChunks, true);
    }

    /**
     * Whether a rule other than K4 keeps the chunk. Used to resolve the structure graph, whose
     * result K4 then reads.
     */
    public boolean keptWithoutStructures(Facts chunk, PlayedChunks playedChunks) {
        return rule(chunk, playedChunks, false) != null;
    }

    private KeepRule rule(Facts chunk, PlayedChunks playedChunks, boolean withStructures) {
        int x = chunk.chunkX();
        int z = chunk.chunkZ();
        if (chunk.readable() && chunk.played()) {
            return KeepRule.K1;
        }
        for (ProtectedPoint point : points) {
            if (Math.abs(point.chunkX() - x) <= radius && Math.abs(point.chunkZ() - z) <= radius) {
                return KeepRule.K2;
            }
        }
        if (forcedChunks && chunk.forced()) {
            return KeepRule.K3;
        }
        if (withStructures && structures && chunk.referencedStart()) {
            return KeepRule.K4;
        }
        if (playerEntities && chunk.playerEntity()) {
            return KeepRule.K5;
        }
        for (ProtectedArea area : areas) {
            if (area.overlapsChunk(dimension, x, z)) {
                return KeepRule.K6;
            }
        }
        if (!chunk.readable()) {
            return KeepRule.K7;
        }
        if (neighborRadius > 0 && playedChunks.anyWithin(x, z, neighborRadius)) {
            return KeepRule.K8;
        }
        return null;
    }
}

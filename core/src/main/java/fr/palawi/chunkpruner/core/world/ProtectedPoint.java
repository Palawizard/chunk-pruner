package fr.palawi.chunkpruner.core.world;

/** A K2 point, in block coordinates. */
public record ProtectedPoint(DimensionId dimension, int blockX, int blockZ, String source) {
    public int chunkX() {
        return Math.floorDiv(blockX, 16);
    }

    public int chunkZ() {
        return Math.floorDiv(blockZ, 16);
    }
}

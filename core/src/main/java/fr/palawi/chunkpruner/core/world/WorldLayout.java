package fr.palawi.chunkpruner.core.world;

import java.nio.file.Path;

/**
 * Where a world keeps its dimensions and player files. Data version 4772 (a 26.1 snapshot) moved
 * every dimension under {@code dimensions/<namespace>/<path>/} and player files to
 * {@code players/data/} ({@code DimensionStorageFileFix} and {@code PlayerStorageFileFix}); the game
 * moves the folders before it loads such a world, so a world saved by that version or a later one
 * uses the new layout. Older worlds keep the overworld at the root, the nether in {@code DIM-1},
 * the end in {@code DIM1}, other dimensions under {@code dimensions/}, and players in
 * {@code playerdata/}.
 */
public enum WorldLayout {
    LEGACY,
    DIMENSION_FOLDERS;

    public static final int DIMENSION_FOLDERS_DATA_VERSION = 4772;

    public static WorldLayout forDataVersion(int dataVersion) {
        return dataVersion >= DIMENSION_FOLDERS_DATA_VERSION ? DIMENSION_FOLDERS : LEGACY;
    }

    public Path dimensionFolder(Path world, DimensionId dimension) {
        if (this == LEGACY) {
            if (dimension.equals(DimensionId.OVERWORLD)) {
                return world;
            }
            if (dimension.equals(DimensionId.NETHER)) {
                return world.resolve("DIM-1");
            }
            if (dimension.equals(DimensionId.END)) {
                return world.resolve("DIM1");
            }
        }
        return world.resolve("dimensions").resolve(dimension.namespace()).resolve(dimension.path());
    }

    public Path playerDataFolder(Path world) {
        return this == LEGACY
                ? world.resolve("playerdata")
                : world.resolve("players").resolve("data");
    }
}

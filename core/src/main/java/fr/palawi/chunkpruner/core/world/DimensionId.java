package fr.palawi.chunkpruner.core.world;

import java.util.regex.Pattern;

/** A dimension id such as {@code minecraft:overworld}. */
public record DimensionId(String namespace, String path) {
    // Declared before the constants below, which use them while the class initializes.
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");
    public static final DimensionId OVERWORLD = new DimensionId("minecraft", "overworld");
    public static final DimensionId NETHER = new DimensionId("minecraft", "the_nether");
    public static final DimensionId END = new DimensionId("minecraft", "the_end");

    public DimensionId {
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("not a dimension id: " + namespace + ":" + path);
        }
        if (namespace.equals(".") || namespace.equals("..") || path.contains("..") || path.startsWith(".")) {
            throw new IllegalArgumentException("not a dimension id: " + namespace + ":" + path);
        }
    }

    /** Parses {@code namespace:path}; a bare path means the {@code minecraft} namespace, as in the game. */
    public static DimensionId parse(String id) {
        int colon = id.indexOf(':');
        return colon < 0
                ? new DimensionId("minecraft", id)
                : new DimensionId(id.substring(0, colon), id.substring(colon + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}

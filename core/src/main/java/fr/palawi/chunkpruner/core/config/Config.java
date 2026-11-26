package fr.palawi.chunkpruner.core.config;

import fr.palawi.chunkpruner.core.world.DimensionId;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/chunkpruner.json} (SPEC section 9). The mod and the command line read the same
 * file (ADR-005, ADR-CP-005). Missing keys take their default, unknown keys are kept as they are
 * when the file is written back, and a known key with a wrong type or range is an error.
 */
public final class Config {
    public static final String MODE_DRY_RUN = "dry-run";
    public static final String MODE_PRUNE = "prune";
    private static final int MAX_RADIUS = 4096;
    private static final int MAX_NEIGHBOR_RADIUS = 16;

    private final Map<String, Object> raw;
    private final long inhabitedThresholdTicks;
    private final int protectRadiusChunks;
    private final int protectNeighborRadiusChunks;
    private final boolean protectForcedChunks;
    private final boolean protectStructures;
    private final boolean protectPlayerEntities;
    private final List<ProtectedArea> protectedAreas;
    private final List<DimensionId> dimensions;
    private final boolean backup;
    private final int backupRetention;
    private final boolean autoPruneEnabled;
    private final int autoPruneIntervalDays;
    private final String autoPruneMode;

    private Config(Map<String, Object> raw) throws JsonException {
        this.raw = raw;
        inhabitedThresholdTicks = whole(raw, "inhabitedThresholdTicks", 0, 0, Long.MAX_VALUE);
        protectRadiusChunks = (int) whole(raw, "protectRadiusChunks", 4, 0, MAX_RADIUS);
        protectNeighborRadiusChunks = (int) whole(raw, "protectNeighborRadiusChunks", 1, 0, MAX_NEIGHBOR_RADIUS);
        protectForcedChunks = bool(raw, "protectForcedChunks", true);
        protectStructures = bool(raw, "protectStructures", true);
        protectPlayerEntities = bool(raw, "protectPlayerEntities", true);
        protectedAreas = raw.containsKey("protectedAreas") ? areas(raw.get("protectedAreas")) : List.of();
        Map<String, Object> dimensionsObject = object(raw, "dimensions");
        dimensions = dimensionList(dimensionsObject, dimensionsObject.get("include"));
        backup = bool(raw, "backup", true);
        backupRetention = (int) whole(raw, "backupRetention", 3, 0, 1000);
        Map<String, Object> autoPrune = object(raw, "autoPrune");
        autoPruneEnabled = bool(autoPrune, "autoPrune.enabled", "enabled", false);
        autoPruneIntervalDays = (int) whole(autoPrune, "autoPrune.intervalDays", "intervalDays", 7, 1, 3650);
        Object mode = autoPrune.getOrDefault("mode", MODE_DRY_RUN);
        if (!MODE_DRY_RUN.equals(mode) && !MODE_PRUNE.equals(mode)) {
            throw new JsonException("autoPrune.mode must be \"dry-run\" or \"prune\"");
        }
        autoPruneMode = (String) mode;
    }

    public static Config defaults() {
        try {
            return new Config(new LinkedHashMap<>());
        } catch (JsonException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Config fromJson(String text) throws JsonException {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        if (!(Json.parse(body) instanceof Map<?, ?> root)) {
            throw new JsonException("the config must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) Json.freeze(root);
        return new Config(frozen);
    }

    public static Config load(Path file) throws IOException, JsonException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The known keys with the values in use, in the order of SPEC section 9. */
    public Map<String, Object> effective() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inhabitedThresholdTicks", inhabitedThresholdTicks);
        out.put("protectRadiusChunks", protectRadiusChunks);
        out.put("protectNeighborRadiusChunks", protectNeighborRadiusChunks);
        out.put("protectForcedChunks", protectForcedChunks);
        out.put("protectStructures", protectStructures);
        out.put("protectPlayerEntities", protectPlayerEntities);
        List<Object> areas = new ArrayList<>();
        for (ProtectedArea area : protectedAreas) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("dimension", area.dimension().toString());
            a.put("minX", area.minX());
            a.put("minZ", area.minZ());
            a.put("maxX", area.maxX());
            a.put("maxZ", area.maxZ());
            areas.add(a);
        }
        out.put("protectedAreas", areas);
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("include", dimensions.stream().map(DimensionId::toString).toList());
        out.put("dimensions", dims);
        out.put("backup", backup);
        out.put("backupRetention", backupRetention);
        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("enabled", autoPruneEnabled);
        auto.put("intervalDays", autoPruneIntervalDays);
        auto.put("mode", autoPruneMode);
        out.put("autoPrune", auto);
        return out;
    }

    /**
     * The file content: every key of the file as it was (they were all checked when it was read,
     * unknown keys included, also inside {@code protectedAreas} entries), plus the missing known
     * keys with their default.
     */
    public String toJson() {
        return Json.write(addMissing(raw, effective()));
    }

    private static Map<String, Object> addMissing(Map<String, Object> original, Map<String, Object> known) {
        Map<String, Object> out = new LinkedHashMap<>(original);
        known.forEach((key, value) -> {
            if (!original.containsKey(key)) {
                out.put(key, value);
            } else if (value instanceof Map<?, ?> inner && original.get(key) instanceof Map<?, ?> existing) {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>) existing;
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) inner;
                out.put(key, addMissing(e, v));
            }
        });
        return out;
    }

    private static long whole(Map<String, Object> map, String key, long fallback, long min, long max)
            throws JsonException {
        return whole(map, key, key, fallback, min, max);
    }

    private static long whole(Map<String, Object> map, String name, String key, long fallback, long min, long max)
            throws JsonException {
        Object value = map.get(key);
        if (value == null && !map.containsKey(key)) {
            return fallback;
        }
        if (value instanceof BigDecimal number) {
            try {
                long result = number.longValueExact();
                if (result >= min && result <= max) {
                    return result;
                }
            } catch (ArithmeticException e) {
                // falls through to the error below
            }
        }
        throw new JsonException(name + " must be a whole number from " + min + " to " + max);
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) throws JsonException {
        return bool(map, key, key, fallback);
    }

    private static boolean bool(Map<String, Object> map, String name, String key, boolean fallback)
            throws JsonException {
        if (!map.containsKey(key)) {
            return fallback;
        }
        if (map.get(key) instanceof Boolean b) {
            return b;
        }
        throw new JsonException(name + " must be true or false");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) throws JsonException {
        if (!map.containsKey(key)) {
            return Map.of();
        }
        if (map.get(key) instanceof Map<?, ?> inner) {
            return (Map<String, Object>) inner;
        }
        throw new JsonException(key + " must be an object");
    }

    private static List<DimensionId> dimensionList(Map<String, Object> dimensions, Object value) throws JsonException {
        if (!dimensions.containsKey("include")) {
            return List.of(DimensionId.OVERWORLD, DimensionId.NETHER, DimensionId.END);
        }
        if (!(value instanceof List<?> list)) {
            throw new JsonException("dimensions.include must be a list of dimension ids");
        }
        List<DimensionId> result = new ArrayList<>();
        for (Object item : list) {
            DimensionId id = dimension(item, "dimensions.include");
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return List.copyOf(result);
    }

    private static List<ProtectedArea> areas(Object value) throws JsonException {
        if (!(value instanceof List<?> list)) {
            throw new JsonException("protectedAreas must be a list");
        }
        List<ProtectedArea> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> area)) {
                throw new JsonException("each protectedAreas entry must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) area;
            DimensionId dimension = dimension(a.get("dimension"), "protectedAreas[].dimension");
            int minX = coordinate(a, "minX");
            int minZ = coordinate(a, "minZ");
            int maxX = coordinate(a, "maxX");
            int maxZ = coordinate(a, "maxZ");
            if (minX > maxX || minZ > maxZ) {
                throw new JsonException("protectedAreas: minX and minZ must not be above maxX and maxZ");
            }
            result.add(new ProtectedArea(dimension, minX, minZ, maxX, maxZ));
        }
        return List.copyOf(result);
    }

    private static int coordinate(Map<String, Object> area, String key) throws JsonException {
        if (!area.containsKey(key)) {
            throw new JsonException("protectedAreas[]." + key + " is missing");
        }
        return (int) whole(area, "protectedAreas[]." + key, key, 0, -30_000_000, 30_000_000);
    }

    private static DimensionId dimension(Object value, String name) throws JsonException {
        if (value instanceof String id) {
            try {
                return DimensionId.parse(id);
            } catch (IllegalArgumentException e) {
                throw new JsonException(name + ": " + e.getMessage());
            }
        }
        throw new JsonException(name + " must be a dimension id such as \"minecraft:overworld\"");
    }

    public boolean autoPruneEnabled() {
        return autoPruneEnabled;
    }

    public int autoPruneIntervalDays() {
        return autoPruneIntervalDays;
    }

    /** {@link #MODE_DRY_RUN} or {@link #MODE_PRUNE}. */
    public String autoPruneMode() {
        return autoPruneMode;
    }

    public long inhabitedThresholdTicks() {
        return inhabitedThresholdTicks;
    }

    public int protectRadiusChunks() {
        return protectRadiusChunks;
    }

    public int protectNeighborRadiusChunks() {
        return protectNeighborRadiusChunks;
    }

    public boolean protectForcedChunks() {
        return protectForcedChunks;
    }

    public boolean protectStructures() {
        return protectStructures;
    }

    public boolean protectPlayerEntities() {
        return protectPlayerEntities;
    }

    public List<ProtectedArea> protectedAreas() {
        return protectedAreas;
    }

    public List<DimensionId> dimensions() {
        return dimensions;
    }

    public boolean backup() {
        return backup;
    }

    public int backupRetention() {
        return backupRetention;
    }

    /** A K6 box in block coordinates, bounds included. */
    public record ProtectedArea(DimensionId dimension, int minX, int minZ, int maxX, int maxZ) {
        public boolean overlapsChunk(DimensionId in, int chunkX, int chunkZ) {
            int x0 = chunkX * 16;
            int z0 = chunkZ * 16;
            return dimension.equals(in) && x0 <= maxX && x0 + 15 >= minX && z0 <= maxZ && z0 + 15 >= minZ;
        }
    }
}

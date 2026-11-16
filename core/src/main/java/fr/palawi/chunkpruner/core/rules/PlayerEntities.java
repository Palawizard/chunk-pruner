package fr.palawi.chunkpruner.core.rules;

import fr.palawi.chunkpruner.core.world.DimensionId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * K5: tells whether the entities of one chunk hold one that a player cares about, or that a
 * player may have placed or moved, leaving out what world generation places by itself (SPEC
 * section 2, ADR-CP-007). The keys and entity ids were checked in the decompiled sources and the
 * structure templates of 1.20.1, 1.21.1 and 26.3 (ARCHITECTURE, Entities).
 */
public final class PlayerEntities {
    private static final String MC = "minecraft:";
    private static final Set<String> VILLAGES = Set.of(
            MC + "village_plains",
            MC + "village_desert",
            MC + "village_savanna",
            MC + "village_snowy",
            MC + "village_taiga");
    private static final Set<String> OCEAN_RUINS = Set.of(MC + "ocean_ruin_cold", MC + "ocean_ruin_warm");
    private static final Set<String> MINESHAFTS = Set.of(MC + "mineshaft", MC + "mineshaft_mesa");
    private static final String ABANDONED_CAMP = MC + "abandoned_camp_";

    /** Entity id to the structures whose generation places it, from their code and templates. */
    private static final Map<String, Set<String>> PLACED_BY_STRUCTURES = new HashMap<>();

    /** Entities a player places with an item or builds, or that only commands create. */
    private static final Set<String> PLAYER_TYPES = Set.of(
            MC + "armor_stand",
            MC + "item_frame",
            MC + "glow_item_frame",
            MC + "painting",
            MC + "leash_knot",
            MC + "end_crystal",
            MC + "mannequin",
            MC + "cushion",
            MC + "item",
            MC + "boat",
            MC + "chest_boat",
            MC + "snow_golem",
            MC + "copper_golem",
            MC + "wither",
            MC + "block_display",
            MC + "item_display",
            MC + "text_display",
            MC + "interaction",
            MC + "marker");

    static {
        Set<String> villagesAndIgloo = union(VILLAGES, Set.of(MC + "igloo"));
        PLACED_BY_STRUCTURES.put(MC + "villager", villagesAndIgloo);
        PLACED_BY_STRUCTURES.put(MC + "zombie_villager", villagesAndIgloo);
        PLACED_BY_STRUCTURES.put(MC + "cat", union(VILLAGES, Set.of(MC + "swamp_hut")));
        for (String animal : List.of("cow", "pig", "sheep", "horse")) {
            PLACED_BY_STRUCTURES.put(MC + animal, VILLAGES);
        }
        PLACED_BY_STRUCTURES.put(MC + "camel", Set.of(MC + "village_desert"));
        PLACED_BY_STRUCTURES.put(MC + "armor_stand", Set.of(MC + "village_taiga"));
        PLACED_BY_STRUCTURES.put(MC + "iron_golem", union(VILLAGES, Set.of(MC + "pillager_outpost")));
        PLACED_BY_STRUCTURES.put(MC + "allay", Set.of(MC + "pillager_outpost", MC + "mansion"));
        for (String mob : List.of("piglin", "piglin_brute", "hoglin")) {
            PLACED_BY_STRUCTURES.put(MC + mob, Set.of(MC + "bastion_remnant"));
        }
        PLACED_BY_STRUCTURES.put(MC + "witch", Set.of(MC + "swamp_hut"));
        PLACED_BY_STRUCTURES.put(MC + "elder_guardian", Set.of(MC + "monument"));
        PLACED_BY_STRUCTURES.put(MC + "drowned", OCEAN_RUINS);
        PLACED_BY_STRUCTURES.put(MC + "zombie_nautilus", OCEAN_RUINS);
        PLACED_BY_STRUCTURES.put(MC + "evoker", Set.of(MC + "mansion"));
        PLACED_BY_STRUCTURES.put(MC + "vindicator", Set.of(MC + "mansion"));
        PLACED_BY_STRUCTURES.put(MC + "shulker", Set.of(MC + "end_city"));
        PLACED_BY_STRUCTURES.put(MC + "item_frame", Set.of(MC + "end_city"));
        PLACED_BY_STRUCTURES.put(MC + "chest_minecart", MINESHAFTS);
    }

    private final DimensionId dimension;
    private final Set<UUID> players;

    /** {@code players}: the UUIDs of the world's player files, to tell a player's leash apart. */
    public PlayerEntities(DimensionId dimension, Set<UUID> players) {
        this.dimension = dimension;
        this.players = Set.copyOf(players);
    }

    /**
     * The first entity of the list (passengers included) that keeps the chunk, as "id (why)", or
     * null when none does. {@code structures} holds the ids of the structures that start in the
     * chunk or reference it, from its {@code region/} entry.
     */
    public String keeper(List<?> entities, Set<String> structures) {
        for (Object value : entities) {
            if (value instanceof Map<?, ?> entity) {
                String why = keeper(entity, structures);
                if (why != null) {
                    return why;
                }
            }
        }
        return null;
    }

    private String keeper(Map<?, ?> entity, Set<String> structures) {
        String id = entity.get("id") instanceof String s ? s : "an entity without an id";
        String mark = playerMark(id, entity);
        if (mark != null) {
            return id + " (" + mark + ")";
        }
        boolean persistent = isTrue(entity.get("PersistenceRequired"));
        if ((persistent || isPlayerType(id)) && !placedByGeneration(id, entity, structures)) {
            return id + (persistent ? " (persistent)" : " (placed by a player)");
        }
        if (entity.get("Passengers") instanceof List<?> passengers) {
            return keeper(passengers, structures);
        }
        return null;
    }

    /** A sign that only a player leaves, whatever placed the entity. */
    private String playerMark(String id, Map<?, ?> entity) {
        if (entity.containsKey("CustomName")) {
            return "named";
        }
        if (entity.containsKey("Owner") && entity.containsKey("Health")) {
            return "owned";
        }
        if (entity.get("Trusted") instanceof List<?> trusted && !trusted.isEmpty()) {
            return "trusts a player";
        }
        if (entity.get("Brain") instanceof Map<?, ?> brain
                && brain.get("memories") instanceof Map<?, ?> memories
                && memories.containsKey(MC + "liked_player")) {
            return "likes a player";
        }
        Object leash = entity.containsKey("leash") ? entity.get("leash") : entity.get("Leash");
        if (leash != null && !(id.equals(MC + "trader_llama") && leashedToAnotherEntity(leash))) {
            return "leashed";
        }
        if (isTrue(entity.get("FromBucket"))) {
            return "released from a bucket";
        }
        if (isTrue(entity.get("PlayerCreated"))) {
            return "built by a player";
        }
        return null;
    }

    /** A wandering trader's llama is leashed to the trader: a UUID that is not a player's. */
    private boolean leashedToAnotherEntity(Object leash) {
        if (leash instanceof Map<?, ?> holder && holder.get("UUID") instanceof int[] uuid && uuid.length == 4) {
            return !players.contains(uuid(uuid));
        }
        return false;
    }

    private boolean placedByGeneration(String id, Map<?, ?> entity, Set<String> structures) {
        if (id.equals(MC + "end_crystal")) {
            // End spike crystals show their bedrock base; a crystal placed from the item does not.
            return dimension.equals(DimensionId.END) && !isFalse(entity.get("ShowBottom"));
        }
        if (id.equals(MC + "item_frame") && !(dimension.equals(DimensionId.END) && holds(entity, MC + "elytra"))) {
            return false;
        }
        if (id.equals(MC + "chest_minecart") && !entity.containsKey("LootTable")) {
            return false;
        }
        if (id.equals(MC + "cushion")) {
            return structures.stream().anyMatch(s -> s.startsWith(ABANDONED_CAMP));
        }
        Set<String> placers = PLACED_BY_STRUCTURES.get(id);
        if (placers == null) {
            return false;
        }
        for (String structure : structures) {
            if (placers.contains(structure)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayerType(String id) {
        return PLAYER_TYPES.contains(id) || id.endsWith("minecart") || id.endsWith("_boat") || id.endsWith("_raft");
    }

    private static boolean holds(Map<?, ?> frame, String item) {
        return frame.get("Item") instanceof Map<?, ?> stack && item.equals(stack.get("id"));
    }

    private static boolean isTrue(Object value) {
        return value instanceof Long l && l != 0;
    }

    private static boolean isFalse(Object value) {
        return value instanceof Long l && l == 0;
    }

    static UUID uuid(int[] parts) {
        return new UUID((long) parts[0] << 32 | parts[1] & 0xFFFFFFFFL, (long) parts[2] << 32 | parts[3] & 0xFFFFFFFFL);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.Set<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}

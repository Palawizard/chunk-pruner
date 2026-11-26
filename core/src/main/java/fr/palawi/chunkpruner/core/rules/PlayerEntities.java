package fr.palawi.chunkpruner.core.rules;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * K5: tells whether the entities of one chunk hold one that a player cares about, one that is
 * persistent, or one of a type a player places or builds, whoever placed it there (SPEC section
 * 2, ADR-CP-009). The keys and entity ids were checked in the decompiled sources of 1.20.1,
 * 1.21.1 and 26.3 (ARCHITECTURE, Entities).
 */
public final class PlayerEntities {
    private static final String MC = "minecraft:";

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

    private final Set<UUID> players;

    /** {@code players}: the UUIDs of the world's player files, to tell a player's leash apart. */
    public PlayerEntities(Set<UUID> players) {
        this.players = Set.copyOf(players);
    }

    /**
     * The first entity of the list (passengers included) that keeps the chunk, as "id (why)", or
     * null when none does.
     */
    public String keeper(List<?> entities) {
        for (Object value : entities) {
            if (value instanceof Map<?, ?> entity) {
                String why = keeper(entity);
                if (why != null) {
                    return why;
                }
            }
        }
        return null;
    }

    private String keeper(Map<?, ?> entity) {
        String id = entity.get("id") instanceof String s ? s : "an entity without an id";
        String mark = playerMark(id, entity);
        if (mark != null) {
            return id + " (" + mark + ")";
        }
        if (isTrue(entity.get("PersistenceRequired"))) {
            return id + " (persistent)";
        }
        if (isPlayerType(id)) {
            return id + " (placed by a player)";
        }
        if (entity.get("Passengers") instanceof List<?> passengers) {
            return keeper(passengers);
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

    private static boolean isPlayerType(String id) {
        return PLAYER_TYPES.contains(id) || id.endsWith("minecart") || id.endsWith("_boat") || id.endsWith("_raft");
    }

    private static boolean isTrue(Object value) {
        return value instanceof Long l && l != 0;
    }

    static UUID uuid(int[] parts) {
        return new UUID((long) parts[0] << 32 | parts[1] & 0xFFFFFFFFL, (long) parts[2] << 32 | parts[3] & 0xFFFFFFFFL);
    }
}

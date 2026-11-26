package fr.palawi.chunkpruner.core.fixture;

import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K5 as SPEC section 2 words it, written again for the tests from the test reader's full tree:
 * an entity a player cares about (named, owned, trusting or liked, leashed, released from a
 * bucket, built), or persistent, or of a type a player places, whoever placed it.
 */
public final class ExpectedEntities {
    private ExpectedEntities() {}

    public static boolean readable(Chunk entities) {
        return entities.readable()
                && entities.nbt().get("Position") instanceof int[] pos
                && pos.length == 2
                && pos[0] == entities.x()
                && pos[1] == entities.z()
                && (entities.nbt().get("Entities") == null || entities.nbt().get("Entities") instanceof List<?>);
    }

    /** {@code players}: UUIDs as four ints, from the player file names. */
    public static boolean keeps(Chunk entities, Set<List<Integer>> players) {
        Object list = entities.nbt().get("Entities");
        return list instanceof List<?> all && anyKept(all, players);
    }

    /** Why an entity keeps its chunk, or null. */
    public static String why(Map<?, ?> e, Set<List<Integer>> players) {
        String id = String.valueOf(e.get("id")).replace("minecraft:", "");
        if (e.containsKey("CustomName")) return "named " + id;
        if (e.containsKey("Owner") && e.containsKey("Health")) return "owned " + id;
        if (e.get("Trusted") instanceof List<?> t && !t.isEmpty()) return "trusting " + id;
        if (e.get("Brain") instanceof Map<?, ?> b
                && b.get("memories") instanceof Map<?, ?> m
                && m.containsKey("minecraft:liked_player")) return "liking " + id;
        Object leash = e.containsKey("leash") ? e.get("leash") : e.get("Leash");
        if (leash != null) {
            boolean traderLeash = id.equals("trader_llama")
                    && leash instanceof Map<?, ?> l
                    && l.get("UUID") instanceof int[] u
                    && !players.contains(List.of(u[0], u[1], u[2], u[3]));
            if (!traderLeash) return "leashed " + id;
        }
        if (flag(e.get("FromBucket"))) return "bucket " + id;
        if (flag(e.get("PlayerCreated"))) return "built " + id;
        boolean persistent = flag(e.get("PersistenceRequired"));
        boolean placedType = Set.of(
                                "armor_stand",
                                "item_frame",
                                "glow_item_frame",
                                "painting",
                                "leash_knot",
                                "end_crystal",
                                "mannequin",
                                "cushion",
                                "item",
                                "boat",
                                "chest_boat",
                                "snow_golem",
                                "copper_golem",
                                "wither",
                                "block_display",
                                "item_display",
                                "text_display",
                                "interaction",
                                "marker")
                        .contains(id)
                || id.endsWith("minecart")
                || id.endsWith("_boat")
                || id.endsWith("_raft");
        if (!persistent && !placedType) return null;
        return (persistent ? "persistent " : "placed ") + id;
    }

    private static boolean anyKept(List<?> list, Set<List<Integer>> players) {
        for (Object o : list) {
            if (o instanceof Map<?, ?> e) {
                if (why(e, players) != null) return true;
                if (e.get("Passengers") instanceof List<?> p && anyKept(p, players)) return true;
            }
        }
        return false;
    }

    private static boolean flag(Object value) {
        return value instanceof Byte b && b != 0;
    }
}

package fr.palawi.chunkpruner.core.fixture;

import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K5 as SPEC section 2 words it, written again for the tests from the test reader's full tree:
 * an entity a player cares about (named, owned, trusting or liked, leashed, released from a
 * bucket, built), or persistent, or of a type a player places, unless world generation placed it
 * inside the structure that places it.
 */
public final class ExpectedEntities {
    private static final List<String> VILLAGES =
            List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga");

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
    public static boolean keeps(Chunk entities, Chunk region, Set<List<Integer>> players, String dimension) {
        Set<String> structures = new HashSet<>();
        Map<String, Object> s = ReferenceNbt.compound(region.nbt(), "structures");
        if (s != null) {
            for (String key : List.of("starts", "References")) {
                Map<String, Object> part = ReferenceNbt.compound(s, key);
                if (part != null) {
                    for (Map.Entry<String, Object> e : part.entrySet()) {
                        if (!(key.equals("starts")
                                && e.getValue() instanceof Map<?, ?> st
                                && "INVALID".equals(st.get("id")))) {
                            structures.add(e.getKey().replace("minecraft:", ""));
                        }
                    }
                }
            }
        }
        Object list = entities.nbt().get("Entities");
        return list instanceof List<?> all && anyKept(all, structures, players, dimension);
    }

    /** Why an entity keeps its chunk, for the measurements, or null. */
    public static String why(Map<?, ?> e, Set<String> structures, Set<List<Integer>> players, String dimension) {
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
        if (fromGeneration(id, e, structures, dimension)) return null;
        return (persistent ? "persistent " : "placed ") + id;
    }

    private static boolean anyKept(List<?> list, Set<String> structures, Set<List<Integer>> players, String dimension) {
        for (Object o : list) {
            if (o instanceof Map<?, ?> e) {
                if (why(e, structures, players, dimension) != null) return true;
                if (e.get("Passengers") instanceof List<?> p && anyKept(p, structures, players, dimension)) return true;
            }
        }
        return false;
    }

    private static boolean fromGeneration(String id, Map<?, ?> e, Set<String> structures, String dimension) {
        boolean end = dimension.equals("minecraft:the_end");
        switch (id) {
            case "end_crystal":
                return end && !(e.get("ShowBottom") instanceof Byte b && b == 0);
            case "item_frame":
                return end
                        && e.get("Item") instanceof Map<?, ?> item
                        && "minecraft:elytra".equals(item.get("id"))
                        && structures.contains("end_city");
            case "chest_minecart":
                return e.containsKey("LootTable")
                        && (structures.contains("mineshaft") || structures.contains("mineshaft_mesa"));
            case "cushion":
                return structures.stream().anyMatch(s -> s.startsWith("abandoned_camp_"));
            case "villager", "zombie_villager":
                return anyVillage(structures) || structures.contains("igloo");
            case "cat":
                return anyVillage(structures) || structures.contains("swamp_hut");
            case "cow", "pig", "sheep", "horse":
                return anyVillage(structures);
            case "camel":
                return structures.contains("village_desert");
            case "armor_stand":
                return structures.contains("village_taiga");
            case "iron_golem":
                return anyVillage(structures) || structures.contains("pillager_outpost");
            case "allay":
                return structures.contains("pillager_outpost") || structures.contains("mansion");
            case "piglin", "piglin_brute", "hoglin":
                return structures.contains("bastion_remnant");
            case "witch":
                return structures.contains("swamp_hut");
            case "elder_guardian":
                return structures.contains("monument");
            case "drowned", "zombie_nautilus":
                return structures.contains("ocean_ruin_cold") || structures.contains("ocean_ruin_warm");
            case "evoker", "vindicator":
                return structures.contains("mansion");
            case "shulker":
                return structures.contains("end_city");
            default:
                return false;
        }
    }

    private static boolean anyVillage(Set<String> structures) {
        return VILLAGES.stream().anyMatch(structures::contains);
    }

    private static boolean flag(Object value) {
        return value instanceof Byte b && b != 0;
    }
}

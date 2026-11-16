package fr.palawi.chunkpruner.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers of the engine tests on fixture worlds. */
final class EngineFixtures {
    private EngineFixtures() {}

    static Config config(int radius, int neighborRadius) throws Exception {
        return Config.fromJson(
                "{\"protectRadiusChunks\": " + radius + ", \"protectNeighborRadiusChunks\": " + neighborRadius + "}");
    }

    static RunSettings settings(Path world, Config config, RunMode mode) {
        return RunSettings.of(world, config, mode, RunContext.E3);
    }

    /** Reads a report with Gson, a parser independent of the core's own JSON code. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readJson(Path file) throws Exception {
        JsonReader reader = new JsonReader(new StringReader(Files.readString(file)));
        reader.setStrictness(Strictness.STRICT);
        JsonElement root = JsonParser.parseReader(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new AssertionError("text after the JSON value in " + file);
        }
        return (Map<String, Object>) plain(root);
    }

    private static Object plain(JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry :
                    element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), plain(entry.getValue()));
            }
            return map;
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(plain(item));
            }
            return list;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsBigDecimal();
        }
        return primitive.getAsString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> overworld(Map<String, Object> report) {
        for (Object d : (List<Object>) report.get("dimensions")) {
            Map<String, Object> dimension = (Map<String, Object>) d;
            if ("minecraft:overworld".equals(dimension.get("id"))) {
                return dimension;
            }
        }
        throw new AssertionError("no overworld in the report");
    }

    static long number(Object value) {
        return ((BigDecimal) value).longValueExact();
    }

    /** Compares the overworld counts and sizes of a JSON report with the expected values. */
    @SuppressWarnings("unchecked")
    static void assertMatches(Expected expected, Map<String, Object> report) {
        Map<String, Object> overworld = overworld(report);
        assertEquals(expected.pruned.size(), number(overworld.get("chunksPruned")), "pruned");
        Map<String, Object> rules = (Map<String, Object>) overworld.get("keptByRule");
        expected.keptByRule.forEach((rule, count) ->
                assertEquals(count, number(((Map<String, Object>) rules.get(rule)).get("chunks")), rule));
        Map<String, Object> storages = (Map<String, Object>) overworld.get("storages");
        expected.storages.forEach((storage, t) -> {
            Map<String, Object> s = (Map<String, Object>) storages.get(storage);
            assertEquals(t[0], number(s.get("files")), storage + " files");
            assertEquals(t[1], number(s.get("bytesBefore")), storage + " bytes before");
            assertEquals(t[2], number(s.get("bytesAfter")), storage + " bytes after");
            assertEquals(t[3], number(s.get("filesChanged")), storage + " files changed");
            assertEquals(t[4], number(s.get("filesDeleted")), storage + " files deleted");
        });
    }

    static long size(Path file) throws IOException {
        return Files.exists(file) ? Files.size(file) : 0;
    }
}

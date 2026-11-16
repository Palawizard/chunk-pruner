package fr.palawi.chunkpruner.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
    @Test
    void parsesEveryKindOfValue() throws Exception {
        Object value = Json.parse(" {\"a\": [1, -2.5e3, true, false, null, \"x\\n\\u00e9\\\"\"], \"b\": {}} ");
        Map<?, ?> map = (Map<?, ?>) value;
        assertEquals(List.of("a", "b"), List.copyOf(map.keySet()));
        List<?> a = (List<?>) map.get("a");
        assertEquals(new BigDecimal("1"), a.get(0));
        assertEquals(new BigDecimal("-2.5e3"), a.get(1));
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals(Boolean.FALSE, a.get(3));
        assertEquals(null, a.get(4));
        assertEquals("x\né\"", a.get(5));
        assertEquals(Map.of(), map.get("b"));
    }

    @Test
    void writesWhatItReadsBack() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("text", "tab\there \"quoted\" \\ back \u0001");
        root.put("number", 42L);
        root.put("decimal", 0.5);
        root.put("list", List.of(1, "two", false));
        root.put("empty", List.of());
        root.put("nothing", null);
        String written = Json.write(root);
        assertTrue(written.endsWith("}\n"));
        Map<?, ?> back = (Map<?, ?>) Json.parse(written);
        assertEquals(root.get("text"), back.get("text"));
        assertEquals(new BigDecimal("42"), back.get("number"));
        assertEquals(new BigDecimal("0.5"), back.get("decimal"));
        assertEquals(List.of(new BigDecimal("1"), "two", false), back.get("list"));
        assertEquals(List.copyOf(root.keySet()), List.copyOf(back.keySet()));
        assertEquals(written, Json.write(back));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{",
                "{\"a\": 1,}",
                "[1 2]",
                "{\"a\": 1} x",
                "{\"a\": 1, \"a\": 2}",
                "\"unterminated",
                "\"bad \\q escape\"",
                "01",
                "1.",
                "-",
                "tru",
                "{a: 1}",
                "\"tab\tinside\""
            })
    void rejectsInvalidText(String text) {
        assertThrows(JsonException.class, () -> Json.parse(text));
    }

    @Test
    void rejectsDeepNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void errorsSayWhere() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\n  \"a\": nope\n}"));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }
}

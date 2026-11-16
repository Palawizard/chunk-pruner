package fr.palawi.chunkpruner.core.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader and writer (RFC 8259) for the config file and the reports, so the command
 * line tool needs no library (ADR-CP-005). Values map to {@code LinkedHashMap} (key order kept),
 * {@code List}, {@code String}, {@code BigDecimal}, {@code Boolean} and {@code null}. A duplicate
 * key, trailing data or a nesting deeper than {@link #MAX_DEPTH} is an error.
 */
public final class Json {
    public static final int MAX_DEPTH = 64;

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) throws JsonException {
        Json parser = new Json(text);
        parser.skipSpace();
        Object value = parser.value(0);
        parser.skipSpace();
        if (parser.at != text.length()) {
            throw parser.error("unexpected text after the value");
        }
        return value;
    }

    private Object value(int depth) throws JsonException {
        if (depth > MAX_DEPTH) {
            throw error("nesting deeper than " + MAX_DEPTH);
        }
        if (at >= text.length()) {
            throw error("unexpected end of the text");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || c >= '0' && c <= '9') {
                    yield number();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (peek('}')) {
            at++;
            return result;
        }
        while (true) {
            skipSpace();
            if (!peek('"')) {
                throw error("expected a key in double quotes");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            if (result.containsKey(key)) {
                throw error("duplicate key \"" + key + "\"");
            }
            result.put(key, value(depth + 1));
            skipSpace();
            if (peek(',')) {
                at++;
                continue;
            }
            expect('}');
            return result;
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> result = new ArrayList<>();
        at++;
        skipSpace();
        if (peek(']')) {
            at++;
            return result;
        }
        while (true) {
            skipSpace();
            result.add(value(depth + 1));
            skipSpace();
            if (peek(',')) {
                at++;
                continue;
            }
            expect(']');
            return result;
        }
    }

    private String string() throws JsonException {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("control character in a string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error("unterminated escape");
            }
            char e = text.charAt(at++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) {
                        throw error("short unicode escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    at += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private BigDecimal number() throws JsonException {
        int start = at;
        if (peek('-')) {
            at++;
        }
        if (peek('0')) {
            at++;
        } else if (!digits()) {
            throw error("bad number");
        }
        if (peek('.')) {
            at++;
            if (!digits()) {
                throw error("bad number");
            }
        }
        if (peek('e') || peek('E')) {
            at++;
            if (peek('+') || peek('-')) {
                at++;
            }
            if (!digits()) {
                throw error("bad number");
            }
        }
        try {
            return new BigDecimal(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private boolean digits() {
        int start = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
            at++;
        }
        return at > start;
    }

    private Object literal(String word, Object value) throws JsonException {
        if (!text.startsWith(word, at)) {
            throw error("unexpected word");
        }
        at += word.length();
        return value;
    }

    private void expect(char c) throws JsonException {
        if (!peek(c)) {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private boolean peek(char c) {
        return at < text.length() && text.charAt(at) == c;
    }

    private void skipSpace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            at++;
        }
    }

    private JsonException error(String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < Math.min(at, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException(message + " at line " + line + ", column " + column);
    }

    /** Writes a value with two-space indentation and a final newline. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof BigDecimal d) {
            out.append(d.toString());
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException("JSON has no " + d);
            }
            out.append(BigDecimal.valueOf(d).toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(out, list, indent);
        } else {
            throw new IllegalArgumentException(
                    "cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            pad(out, indent + 1);
            quote(out, String.valueOf(entry.getKey()));
            out.append(": ");
            write(out, entry.getValue(), indent + 1);
            out.append(++i < map.size() ? ",\n" : "\n");
        }
        pad(out, indent);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(out, indent + 1);
            write(out, list.get(i), indent + 1);
            out.append(i + 1 < list.size() ? ",\n" : "\n");
        }
        pad(out, indent);
        out.append(']');
    }

    private static void pad(StringBuilder out, int indent) {
        out.append("  ".repeat(indent));
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Unmodifiable deep copy, used where parsed values are shared. */
    static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), freeze(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(freeze(v)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}

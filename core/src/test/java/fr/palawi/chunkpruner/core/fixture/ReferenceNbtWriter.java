package fr.palawi.chunkpruner.core.fixture;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Test writer for the trees of {@link ReferenceNbt}, to change a real saved data file in a known
 * way (types as the test reader returns them: Byte, Short, Integer, Long, Float, Double, String,
 * byte[], int[], long[], List, Map).
 */
public final class ReferenceNbtWriter {
    private ReferenceNbtWriter() {}

    public static void writeGzip(Path file, Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            compound(out, root);
        }
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(bytes.toByteArray());
        }
    }

    private static void compound(DataOutputStream out, Map<?, ?> map) throws IOException {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.writeByte(type(e.getValue()));
            out.writeUTF((String) e.getKey());
            payload(out, e.getValue());
        }
        out.writeByte(0);
    }

    private static int type(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List<?>) return 9;
        if (v instanceof Map<?, ?>) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("no NBT type for " + v);
    }

    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (type(v)) {
            case 1 -> out.writeByte((Byte) v);
            case 2 -> out.writeShort((Short) v);
            case 3 -> out.writeInt((Integer) v);
            case 4 -> out.writeLong((Long) v);
            case 5 -> out.writeFloat((Float) v);
            case 6 -> out.writeDouble((Double) v);
            case 7 -> {
                out.writeInt(((byte[]) v).length);
                out.write((byte[]) v);
            }
            case 8 -> out.writeUTF((String) v);
            case 9 -> {
                List<?> list = (List<?>) v;
                out.writeByte(list.isEmpty() ? 0 : type(list.get(0)));
                out.writeInt(list.size());
                for (Object item : list) {
                    payload(out, item);
                }
            }
            case 10 -> compound(out, (Map<?, ?>) v);
            case 11 -> {
                out.writeInt(((int[]) v).length);
                for (int i : (int[]) v) {
                    out.writeInt(i);
                }
            }
            default -> {
                out.writeInt(((long[]) v).length);
                for (long l : (long[]) v) {
                    out.writeLong(l);
                }
            }
        }
    }
}

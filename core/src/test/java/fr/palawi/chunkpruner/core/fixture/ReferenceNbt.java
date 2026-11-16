package fr.palawi.chunkpruner.core.fixture;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test reader, written apart from the engine: a plain NBT parser that builds the whole tree, so
 * expected values never come from the code under test. Compounds become maps, lists become lists,
 * arrays become Java arrays, numbers keep their Java type.
 */
public final class ReferenceNbt {
    private ReferenceNbt() {}

    /**
     * Reads the root compound of fully decoded data. Lengths are checked against the bytes left, so
     * damaged data fails instead of asking for a huge array.
     */
    public static Map<String, Object> readRoot(byte[] data) throws IOException {
        return readRoot(new DataInputStream(new ByteArrayInputStream(data)));
    }

    private static Map<String, Object> readRoot(DataInputStream in) throws IOException {
        int type = in.readByte();
        if (type != 10) {
            throw new IOException("root is not a compound: " + type);
        }
        in.readUTF();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) tag(in, 10);
        return root;
    }

    private static Object tag(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] bytes = new byte[length(in, 1)];
                in.readFully(bytes);
                return bytes;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int elementType = in.readByte();
                int size = length(in, elementType == 0 ? 0 : 1);
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    list.add(tag(in, elementType));
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int child = in.readByte();
                    if (child == 0) {
                        return map;
                    }
                    String key = in.readUTF();
                    map.put(key, tag(in, child));
                }
            }
            case 11: {
                int[] ints = new int[length(in, 4)];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case 12: {
                long[] longs = new long[length(in, 8)];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("unknown tag " + type);
        }
    }

    private static int length(DataInputStream in, int elementBytes) throws IOException {
        int n = in.readInt();
        if (n < 0 || (long) n * elementBytes > in.available()) {
            throw new IOException("length " + n + " does not fit in the data left");
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}

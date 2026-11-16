package fr.palawi.chunkpruner.core.fixture;

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

    public static Map<String, Object> readRoot(DataInputStream in) throws IOException {
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
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                return bytes;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int elementType = in.readByte();
                int size = in.readInt();
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
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case 12: {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("unknown tag " + type);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}

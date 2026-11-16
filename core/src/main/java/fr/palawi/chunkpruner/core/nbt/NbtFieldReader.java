package fr.palawi.chunkpruner.core.nbt;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UTFDataFormatException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streaming, read-only NBT reader. It walks a whole root compound, keeps only the values at the
 * requested paths and skips everything else without building a tree. Paths are compound keys
 * joined with dots ({@code Data.SpawnX}); list elements are not addressable, but a requested
 * list of numbers or strings is kept whole.
 *
 * <p>Kept values: integer tags as {@link Long}, float and double tags as {@link Double}, strings
 * as {@link String}, int arrays as {@code int[]}, lists of numbers or strings as {@code List}.
 * Byte and long arrays, compounds and other lists are only skipped.
 */
public final class NbtFieldReader {
    public static final int MAX_DEPTH = 512;
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;
    private static final int MAX_KEPT_ELEMENTS = 1 << 16;

    private final DataInputStream in;
    private final Set<String> wanted;
    private final Set<String> prefixes = new HashSet<>();
    private final Map<String, Object> found = new HashMap<>();

    private NbtFieldReader(InputStream in, Set<String> wanted) {
        this.in = in instanceof DataInputStream data ? data : new DataInputStream(in);
        this.wanted = wanted;
        for (String path : wanted) {
            int dot = path.indexOf('.');
            while (dot > 0) {
                prefixes.add(path.substring(0, dot));
                dot = path.indexOf('.', dot + 1);
            }
        }
    }

    /**
     * Reads one named root compound (the format of chunk payloads, {@code level.dat} and player
     * files once decompressed) and returns the requested values that were present.
     */
    public static Map<String, Object> read(InputStream in, Set<String> paths) throws IOException {
        NbtFieldReader reader = new NbtFieldReader(in, paths);
        try {
            int type = reader.in.readUnsignedByte();
            if (type != TAG_COMPOUND) {
                throw new NbtFormatException("root tag is type " + type + ", not a compound");
            }
            reader.skipString();
            reader.compound("", 1);
        } catch (EOFException e) {
            throw new NbtFormatException("data ends inside the root compound", e);
        } catch (UTFDataFormatException e) {
            throw new NbtFormatException("malformed string", e);
        }
        return Collections.unmodifiableMap(reader.found);
    }

    private void compound(String path, int depth) throws IOException {
        checkDepth(depth);
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }
            String key = in.readUTF();
            payload(type, path.isEmpty() ? key : path + "." + key, depth);
        }
    }

    private void payload(int type, String path, int depth) throws IOException {
        boolean keep = path != null && wanted.contains(path);
        switch (type) {
            case TAG_BYTE -> keep(keep, path, (long) in.readByte());
            case TAG_SHORT -> keep(keep, path, (long) in.readShort());
            case TAG_INT -> keep(keep, path, (long) in.readInt());
            case TAG_LONG -> keep(keep, path, in.readLong());
            case TAG_FLOAT -> keep(keep, path, (double) in.readFloat());
            case TAG_DOUBLE -> keep(keep, path, in.readDouble());
            case TAG_BYTE_ARRAY -> skipArray(1);
            case TAG_STRING -> {
                if (keep) {
                    found.put(path, in.readUTF());
                } else {
                    skipString();
                }
            }
            case TAG_LIST -> list(path, keep, depth + 1);
            case TAG_COMPOUND -> {
                String inner = path != null && prefixes.contains(path) ? path : null;
                if (inner == null) {
                    skipCompound(depth + 1);
                } else {
                    compound(inner, depth + 1);
                }
            }
            case TAG_INT_ARRAY -> {
                if (keep) {
                    found.put(path, readIntArray());
                } else {
                    skipArray(4);
                }
            }
            case TAG_LONG_ARRAY -> skipArray(8);
            default -> throw new NbtFormatException("unknown tag type " + type);
        }
    }

    private void keep(boolean keep, String path, Object value) {
        if (keep) {
            found.put(path, value);
        }
    }

    private void list(String path, boolean keep, int depth) throws IOException {
        checkDepth(depth);
        int elementType = in.readUnsignedByte();
        int size = in.readInt();
        if (size < 0) {
            throw new NbtFormatException("negative list size " + size);
        }
        if (elementType == TAG_END && size > 0) {
            throw new NbtFormatException("list of end tags with " + size + " elements");
        }
        boolean keepElements = keep && size <= MAX_KEPT_ELEMENTS && isKeptScalar(elementType);
        List<Object> values = keepElements ? new ArrayList<>(size) : null;
        for (int i = 0; i < size; i++) {
            if (keepElements) {
                values.add(scalar(elementType));
            } else {
                payload(elementType, null, depth);
            }
        }
        if (keepElements) {
            found.put(path, Collections.unmodifiableList(values));
        }
    }

    private static boolean isKeptScalar(int type) {
        return type >= TAG_BYTE && type <= TAG_DOUBLE || type == TAG_STRING;
    }

    private Object scalar(int type) throws IOException {
        return switch (type) {
            case TAG_BYTE -> (long) in.readByte();
            case TAG_SHORT -> (long) in.readShort();
            case TAG_INT -> (long) in.readInt();
            case TAG_LONG -> in.readLong();
            case TAG_FLOAT -> (double) in.readFloat();
            case TAG_DOUBLE -> in.readDouble();
            default -> in.readUTF();
        };
    }

    private void skipCompound(int depth) throws IOException {
        checkDepth(depth);
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }
            skipString();
            payload(type, null, depth);
        }
    }

    private int[] readIntArray() throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_KEPT_ELEMENTS) {
            throw new NbtFormatException("int array length " + length + " out of range");
        }
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = in.readInt();
        }
        return values;
    }

    private void skipArray(int elementBytes) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new NbtFormatException("negative array length " + length);
        }
        in.skipNBytes((long) length * elementBytes);
    }

    private void skipString() throws IOException {
        in.skipNBytes(in.readUnsignedShort());
    }

    private static void checkDepth(int depth) throws NbtFormatException {
        if (depth > MAX_DEPTH) {
            throw new NbtFormatException("nesting deeper than " + MAX_DEPTH);
        }
    }
}

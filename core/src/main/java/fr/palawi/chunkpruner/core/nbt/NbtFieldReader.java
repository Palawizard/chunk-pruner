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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Streaming, read-only NBT reader. It walks a whole root compound, keeps only the values at the
 * requested paths and skips everything else without building a tree. Paths are compound keys
 * joined with dots ({@code Data.SpawnX}); list elements are not addressable, but a requested
 * list or compound is kept whole.
 *
 * <p>Kept values: integer tags as {@link Long}, float and double tags as {@link Double}, strings
 * as {@link String}, int arrays as {@code int[]}, long arrays as {@code long[]}, lists as {@code
 * List} and compounds as {@code Map<String, Object>} of the same kinds of values. Byte arrays are
 * only skipped. A requested list or array, and any list or array inside a requested compound or
 * list, holds at most {@link #MAX_KEPT_ELEMENTS} elements, and one read keeps at most {@link
 * #MAX_KEPT_VALUES} values; more is a format error, so the caller never works on part of the data.
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
    public static final int MAX_KEPT_ELEMENTS = 1 << 16;
    /** Values kept in one read, all requested subtrees together. */
    public static final int MAX_KEPT_VALUES = 1 << 20;

    private final DataInputStream in;
    private final Set<String> wanted;
    private final Set<String> prefixes = new HashSet<>();
    private final Map<String, Object> found = new HashMap<>();
    private int keptValues;

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
                if (keep) {
                    found.put(path, compoundTree(depth + 1));
                } else if (path != null && prefixes.contains(path)) {
                    compound(path, depth + 1);
                } else {
                    skipCompound(depth + 1);
                }
            }
            case TAG_INT_ARRAY -> {
                if (keep) {
                    found.put(path, readIntArray());
                } else {
                    skipArray(4);
                }
            }
            case TAG_LONG_ARRAY -> {
                if (keep) {
                    found.put(path, readLongArray());
                } else {
                    skipArray(8);
                }
            }
            default -> throw new NbtFormatException("unknown tag type " + type);
        }
    }

    /** The whole value of a tag inside a requested compound or list. */
    private Object tree(int type, int depth) throws IOException {
        if (++keptValues > MAX_KEPT_VALUES) {
            throw new NbtFormatException("more than " + MAX_KEPT_VALUES + " values to keep");
        }
        return switch (type) {
            case TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE, TAG_STRING -> scalar(type);
            case TAG_BYTE_ARRAY -> {
                skipArray(1);
                yield new byte[0];
            }
            case TAG_LIST -> listTree(depth + 1);
            case TAG_COMPOUND -> compoundTree(depth + 1);
            case TAG_INT_ARRAY -> readIntArray();
            case TAG_LONG_ARRAY -> readLongArray();
            default -> throw new NbtFormatException("unknown tag type " + type);
        };
    }

    private Map<String, Object> compoundTree(int depth) throws IOException {
        checkDepth(depth);
        Map<String, Object> values = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return Collections.unmodifiableMap(values);
            }
            String key = in.readUTF();
            values.put(key, tree(type, depth));
        }
    }

    private List<Object> listTree(int depth) throws IOException {
        checkDepth(depth);
        int elementType = in.readUnsignedByte();
        int size = listSize(elementType);
        if (size > MAX_KEPT_ELEMENTS) {
            throw new NbtFormatException("list of " + size + " elements is too long to keep");
        }
        List<Object> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(tree(elementType, depth));
        }
        return Collections.unmodifiableList(values);
    }

    private int listSize(int elementType) throws IOException {
        int size = in.readInt();
        if (size < 0) {
            throw new NbtFormatException("negative list size " + size);
        }
        if (elementType == TAG_END && size > 0) {
            throw new NbtFormatException("list of end tags with " + size + " elements");
        }
        return size;
    }

    private void keep(boolean keep, String path, Object value) {
        if (keep) {
            found.put(path, value);
        }
    }

    private void list(String path, boolean keep, int depth) throws IOException {
        checkDepth(depth);
        int elementType = in.readUnsignedByte();
        int size = listSize(elementType);
        if (keep && size > MAX_KEPT_ELEMENTS) {
            throw new NbtFormatException("list of " + size + " elements is too long to keep");
        }
        boolean keepElements = keep;
        List<Object> values = keepElements ? new ArrayList<>(size) : null;
        for (int i = 0; i < size; i++) {
            if (keepElements) {
                values.add(tree(elementType, depth));
            } else {
                payload(elementType, null, depth);
            }
        }
        if (keepElements) {
            found.put(path, Collections.unmodifiableList(values));
        }
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

    private long[] readLongArray() throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_KEPT_ELEMENTS) {
            throw new NbtFormatException("long array length " + length + " out of range");
        }
        long[] values = new long[length];
        for (int i = 0; i < length; i++) {
            values[i] = in.readLong();
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

package fr.palawi.chunkpruner.core.anvil;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Chunk compression ids of {@code RegionFileVersion}: 1 gzip, 2 zlib, 3 none on 1.20.1, plus
 * 4 LZ4 and 127 custom on 1.21.1 and 26.3. LZ4 is read with the lz4-java library when it is on
 * the class path (Minecraft bundles it from 1.20.5); without it, or for any other id, a chunk
 * cannot be read and is kept (K7).
 */
public final class Compression {
    public static final int GZIP = 1;
    public static final int ZLIB = 2;
    public static final int NONE = 3;
    public static final int LZ4 = 4;
    public static final int CUSTOM = 127;
    static final String LZ4_STREAM_CLASS = "net.jpountz.lz4.LZ4BlockInputStream";

    private final Constructor<?> lz4Stream;

    private Compression(Constructor<?> lz4Stream) {
        this.lz4Stream = lz4Stream;
    }

    /** Looks for lz4-java through the given class loader. */
    public static Compression detect(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(LZ4_STREAM_CLASS, false, loader);
            return new Compression(type.getConstructor(InputStream.class));
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            return new Compression(null);
        }
    }

    public static Compression detect() {
        return detect(Compression.class.getClassLoader());
    }

    public boolean lz4Available() {
        return lz4Stream != null;
    }

    public boolean canRead(int id) {
        return id == GZIP || id == ZLIB || id == NONE || id == LZ4 && lz4Available();
    }

    /** Wraps the compressed bytes of a chunk. The caller checks {@link #canRead} first. */
    public InputStream open(int id, InputStream compressed) throws IOException {
        return switch (id) {
            case GZIP -> new BufferedInputStream(new GZIPInputStream(compressed));
            case ZLIB -> new BufferedInputStream(new InflaterInputStream(compressed));
            case NONE -> compressed;
            case LZ4 -> new BufferedInputStream(openLz4(compressed));
            default -> throw new IOException("unknown compression id " + id);
        };
    }

    private InputStream openLz4(InputStream compressed) throws IOException {
        if (lz4Stream == null) {
            throw new IOException("LZ4 library not on the class path");
        }
        try {
            return (InputStream) lz4Stream.newInstance(compressed);
        } catch (InvocationTargetException e) {
            throw new IOException("LZ4 stream could not start", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IOException("LZ4 stream could not start", e);
        }
    }

    public static String describe(int id) {
        return switch (id) {
            case GZIP -> "gzip";
            case ZLIB -> "zlib";
            case NONE -> "none";
            case LZ4 -> "lz4";
            case CUSTOM -> "custom";
            default -> "unknown (" + id + ")";
        };
    }
}

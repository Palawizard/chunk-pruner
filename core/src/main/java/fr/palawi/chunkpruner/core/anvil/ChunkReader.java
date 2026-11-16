package fr.palawi.chunkpruner.core.anvil;

import fr.palawi.chunkpruner.core.anvil.RegionFile.ChunkEntry;
import fr.palawi.chunkpruner.core.nbt.NbtFieldReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Reads the fields the rules need from one chunk of a {@code region/} file: {@code xPos},
 * {@code zPos}, {@code InhabitedTime} and {@code Status}, the same keys on 1.20.1, 1.21.1 and
 * 26.3. The whole payload is decoded to its end, so a checksum or format error anywhere in it
 * makes the chunk unreadable.
 */
public final class ChunkReader {
    static final int EXTERNAL_FLAG = 0x80;
    private static final Set<String> FIELDS = Set.of("xPos", "zPos", "InhabitedTime", "Status");

    private final Compression compression;

    public ChunkReader(Compression compression) {
        this.compression = compression;
    }

    /** Path of the external payload file of a chunk, next to its region file. */
    public static Path externalFile(RegionFile region, ChunkEntry entry) {
        return region.path().resolveSibling("c." + region.chunkX(entry) + "." + region.chunkZ(entry) + ".mcc");
    }

    /** Reads the 5-byte payload header and tells whether the payload is in a {@code .mcc} file. */
    public static boolean isExternal(FileChannel channel, ChunkEntry entry) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(5);
        readFully(channel, head, entry.byteOffset());
        return (head.get(4) & EXTERNAL_FLAG) != 0;
    }

    public ChunkFields read(FileChannel channel, RegionFile region, ChunkEntry entry) {
        try {
            return decode(channel, region, entry);
        } catch (IOException | RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ChunkFields.unreadable("cannot decode the chunk data: " + reason, false);
        }
    }

    private ChunkFields decode(FileChannel channel, RegionFile region, ChunkEntry entry) throws IOException {
        if (!entry.inFile()) {
            return ChunkFields.unreadable("its location entry points outside the file", false);
        }
        ByteBuffer sectors = ByteBuffer.allocate((int) entry.byteLength());
        readFully(channel, sectors, entry.byteOffset());
        sectors.flip();
        int length = sectors.getInt();
        int type = sectors.get() & 0xFF;
        if (length <= 0) {
            return ChunkFields.unreadable("the chunk is allocated but its data length is " + length, false);
        }
        boolean external = (type & EXTERNAL_FLAG) != 0;
        int id = type & ~EXTERNAL_FLAG;
        if (!compression.canRead(id)) {
            String reason = id == Compression.LZ4
                    ? "LZ4 compression and no LZ4 library on the class path"
                    : "unknown compression " + Compression.describe(id);
            return ChunkFields.unreadable(reason, external);
        }
        InputStream compressed;
        if (external) {
            Path file = externalFile(region, entry);
            if (!Files.isRegularFile(file)) {
                return ChunkFields.unreadable("its external file " + file.getFileName() + " is missing", true);
            }
            compressed = Files.newInputStream(file);
        } else {
            if (length - 1 > sectors.remaining()) {
                return ChunkFields.unreadable("its data runs past its sectors", false);
            }
            compressed = new ByteArrayInputStream(sectors.array(), 5, length - 1);
        }
        try (InputStream data = compression.open(id, compressed)) {
            Map<String, Object> values = NbtFieldReader.read(data, FIELDS);
            data.transferTo(java.io.OutputStream.nullOutputStream());
            return fields(values, region, entry, external);
        }
    }

    private static ChunkFields fields(
            Map<String, Object> values, RegionFile region, ChunkEntry entry, boolean external) {
        Object x = values.get("xPos");
        Object z = values.get("zPos");
        Object inhabited = values.get("InhabitedTime");
        Object status = values.get("Status");
        if (!(x instanceof Long) || !(z instanceof Long) || !(inhabited instanceof Long)) {
            return ChunkFields.unreadable("xPos, zPos or InhabitedTime is missing", external);
        }
        if ((Long) x != region.chunkX(entry) || (Long) z != region.chunkZ(entry)) {
            return ChunkFields.unreadable("it says it is chunk " + x + ", " + z, external);
        }
        return ChunkFields.readable((Long) inhabited, status instanceof String s ? s : null, external);
    }

    static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw new IOException("region file ends inside a chunk");
            }
            at += read;
        }
    }

    /** What the rules need from one chunk, or why it could not be read. */
    public record ChunkFields(boolean readable, long inhabitedTime, String status, boolean external, String problem) {
        static ChunkFields readable(long inhabitedTime, String status, boolean external) {
            return new ChunkFields(true, inhabitedTime, status, external, null);
        }

        static ChunkFields unreadable(String problem, boolean external) {
            return new ChunkFields(false, -1, null, external, problem);
        }
    }
}

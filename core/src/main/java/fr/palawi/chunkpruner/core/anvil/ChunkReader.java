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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the fields the rules need from one chunk: from a {@code region/} entry {@code xPos},
 * {@code zPos}, {@code InhabitedTime}, {@code Status} and the {@code structures} compound with
 * {@code starts} and {@code References}; from an {@code entities/} entry {@code Position} and the
 * {@code Entities} list. The keys are the same on 1.20.1, 1.21.1 and 26.3. The whole payload is
 * decoded to its end, so a checksum or format error anywhere in it makes the chunk unreadable.
 */
public final class ChunkReader {
    static final int EXTERNAL_FLAG = 0x80;
    private static final String STARTS = "structures.starts";
    private static final String REFERENCES = "structures.References";
    private static final Set<String> FIELDS = Set.of("xPos", "zPos", "InhabitedTime", "Status", STARTS, REFERENCES);
    private static final Set<String> ENTITY_FIELDS = Set.of("Position", "Entities");

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
        Decoded decoded = decodeSafely(channel, region, entry, FIELDS);
        return decoded.values == null
                ? ChunkFields.unreadable(decoded.problem, decoded.external)
                : fields(decoded.values, region, entry, decoded.external);
    }

    /** The entity list of an {@code entities/} entry, or why it cannot be read. */
    public EntityFields readEntities(FileChannel channel, RegionFile region, ChunkEntry entry) {
        Decoded decoded = decodeSafely(channel, region, entry, ENTITY_FIELDS);
        if (decoded.values == null) {
            return new EntityFields(null, decoded.problem);
        }
        if (!(decoded.values.get("Position") instanceof int[] pos)
                || pos.length != 2
                || pos[0] != region.chunkX(entry)
                || pos[1] != region.chunkZ(entry)) {
            return new EntityFields(null, "its Position is missing or names another chunk");
        }
        Object entities = decoded.values.get("Entities");
        if (entities != null && !(entities instanceof List<?>)) {
            return new EntityFields(null, "Entities is not a list");
        }
        return new EntityFields(entities == null ? List.of() : (List<?>) entities, null);
    }

    private record Decoded(Map<String, Object> values, String problem, boolean external) {}

    private Decoded decodeSafely(FileChannel channel, RegionFile region, ChunkEntry entry, Set<String> fields) {
        try {
            return decode(channel, region, entry, fields);
        } catch (IOException | RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Decoded(null, "cannot decode the chunk data: " + reason, false);
        }
    }

    private Decoded decode(FileChannel channel, RegionFile region, ChunkEntry entry, Set<String> fields)
            throws IOException {
        if (!entry.inFile()) {
            return new Decoded(null, "its location entry points outside the file", false);
        }
        ByteBuffer sectors = ByteBuffer.allocate((int) entry.byteLength());
        readFully(channel, sectors, entry.byteOffset());
        sectors.flip();
        int length = sectors.getInt();
        int type = sectors.get() & 0xFF;
        if (length <= 0) {
            return new Decoded(null, "the chunk is allocated but its data length is " + length, false);
        }
        boolean external = (type & EXTERNAL_FLAG) != 0;
        int id = type & ~EXTERNAL_FLAG;
        if (!compression.canRead(id)) {
            String reason = id == Compression.LZ4
                    ? "LZ4 compression and no LZ4 library on the class path"
                    : "unknown compression " + Compression.describe(id);
            return new Decoded(null, reason, external);
        }
        InputStream compressed;
        if (external) {
            Path file = externalFile(region, entry);
            if (!Files.isRegularFile(file)) {
                return new Decoded(null, "its external file " + file.getFileName() + " is missing", true);
            }
            compressed = Files.newInputStream(file);
        } else {
            if (length - 1 > sectors.remaining()) {
                return new Decoded(null, "its data runs past its sectors", false);
            }
            compressed = new ByteArrayInputStream(sectors.array(), 5, length - 1);
        }
        InputStream opened;
        try {
            opened = compression.open(id, compressed);
        } catch (IOException | RuntimeException e) {
            compressed.close();
            throw e;
        }
        try (InputStream data = opened) {
            Map<String, Object> values = NbtFieldReader.read(data, fields);
            data.transferTo(java.io.OutputStream.nullOutputStream());
            return new Decoded(values, null, external);
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
        Object starts = values.get(STARTS);
        Object references = values.get(REFERENCES);
        if (starts != null && !(starts instanceof Map<?, ?>)
                || references != null && !(references instanceof Map<?, ?>)) {
            return ChunkFields.unreadable("its structures data is not made of compounds", external);
        }
        Set<String> validStarts = new HashSet<>();
        if (starts instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> start : map.entrySet()) {
                if (start.getValue() instanceof Map<?, ?> s && !"INVALID".equals(s.get("id"))) {
                    validStarts.add((String) start.getKey());
                }
            }
        }
        Map<String, long[]> refs = new HashMap<>();
        if (references instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> ref : map.entrySet()) {
                if (!(ref.getValue() instanceof long[] positions)) {
                    return ChunkFields.unreadable("a structure reference is not a long array", external);
                }
                refs.put((String) ref.getKey(), positions);
            }
        }
        return new ChunkFields(
                true,
                (Long) inhabited,
                status instanceof String s ? s : null,
                external,
                null,
                Set.copyOf(validStarts),
                Map.copyOf(refs));
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

    /**
     * What the rules need from one {@code region/} entry, or why it could not be read.
     * {@code starts} holds the ids of the structures that start in the chunk (not the
     * {@code INVALID} placeholder); {@code references} maps structure ids to the packed positions
     * of the chunks that hold their start.
     */
    public record ChunkFields(
            boolean readable,
            long inhabitedTime,
            String status,
            boolean external,
            String problem,
            Set<String> starts,
            Map<String, long[]> references) {
        static ChunkFields unreadable(String problem, boolean external) {
            return new ChunkFields(false, -1, null, external, problem, Set.of(), Map.of());
        }
    }

    /** The entity list of one {@code entities/} entry, or null with the reason it cannot be read. */
    public record EntityFields(List<?> entities, String problem) {}
}

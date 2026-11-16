package fr.palawi.chunkpruner.core.anvil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Header of one Anvil region file ({@code r.<x>.<z>.mca}): 1024 location entries (3-byte sector
 * offset, 1-byte sector count) then 1024 timestamps, followed by 4 KiB sectors. Empty files are
 * valid and hold no chunk, as the game writes them.
 */
public final class RegionFile {
    public static final int SECTOR_BYTES = 4096;
    public static final int HEADER_BYTES = 2 * SECTOR_BYTES;
    public static final int CHUNKS = 1024;
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final Path path;
    private final int regionX;
    private final int regionZ;
    private final long size;
    private final boolean truncatedHeader;
    private final List<ChunkEntry> entries;

    private RegionFile(Path path, int regionX, int regionZ, long size, boolean truncated, List<ChunkEntry> entries) {
        this.path = path;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.size = size;
        this.truncatedHeader = truncated;
        this.entries = entries;
    }

    /** Region coordinates from a file name, or null when the name is not a region file name. */
    public static int[] coordinates(String fileName) {
        Matcher matcher = NAME.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static RegionFile read(Path path) throws IOException {
        int[] coordinates = coordinates(path.getFileName().toString());
        if (coordinates == null) {
            throw new IOException("not a region file name: " + path.getFileName());
        }
        long size = Files.size(path);
        if (size == 0) {
            return new RegionFile(path, coordinates[0], coordinates[1], 0, false, List.of());
        }
        if (size < HEADER_BYTES) {
            return new RegionFile(path, coordinates[0], coordinates[1], size, true, List.of());
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                if (channel.read(header, header.position()) < 0) {
                    throw new IOException("region header ends early: " + path);
                }
            }
        }
        header.flip();
        List<ChunkEntry> entries = new ArrayList<>();
        long sectorsInFile = size / SECTOR_BYTES;
        for (int index = 0; index < CHUNKS; index++) {
            int location = header.getInt(index * 4);
            if (location == 0) {
                continue;
            }
            int offset = location >>> 8;
            int count = location & 0xFF;
            int timestamp = header.getInt(SECTOR_BYTES + index * 4);
            boolean inFile = offset >= 2 && count > 0 && offset + (long) count <= sectorsInFile;
            entries.add(new ChunkEntry(index, offset, count, timestamp, inFile));
        }
        return new RegionFile(path, coordinates[0], coordinates[1], size, false, Collections.unmodifiableList(entries));
    }

    public Path path() {
        return path;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public long size() {
        return size;
    }

    /** A non-empty file shorter than the 8 KiB header: no entry can be trusted. */
    public boolean truncatedHeader() {
        return truncatedHeader;
    }

    public List<ChunkEntry> entries() {
        return entries;
    }

    /** True when every entry lies inside the file, so the file can be rebuilt byte for byte. */
    public boolean rewritable() {
        return !truncatedHeader && entries.stream().allMatch(ChunkEntry::inFile);
    }

    public int chunkX(ChunkEntry entry) {
        return regionX * 32 + (entry.index() & 31);
    }

    public int chunkZ(ChunkEntry entry) {
        return regionZ * 32 + (entry.index() >> 5);
    }

    /**
     * One location entry. {@code inFile} is false when the offset points into the header, the
     * sector count is zero, or the sectors run past the end of the file.
     */
    public record ChunkEntry(int index, int sectorOffset, int sectorCount, int timestamp, boolean inFile) {
        public long byteOffset() {
            return (long) sectorOffset * SECTOR_BYTES;
        }

        public long byteLength() {
            return (long) sectorCount * SECTOR_BYTES;
        }
    }
}

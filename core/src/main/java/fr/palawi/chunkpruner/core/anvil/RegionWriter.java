package fr.palawi.chunkpruner.core.anvil;

import fr.palawi.chunkpruner.core.anvil.RegionFile.ChunkEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds a region file with only some of its chunks. The sectors of each kept chunk are copied
 * byte for byte, in their original order, and only the location table is rebuilt (timestamps are
 * copied). The new file is written next to the old one as {@code .tmp}, forced to disk, then
 * moved over it. When no chunk is left, the file is deleted. The external {@code .mcc} payload
 * of each removed chunk is deleted with it.
 */
public final class RegionWriter {
    private RegionWriter() {}

    /** Size of the rebuilt file for the kept chunks, 0 when it would be deleted. */
    public static long sizeAfter(RegionFile region, Set<Integer> removed) {
        long sectors = 0;
        for (ChunkEntry entry : region.entries()) {
            if (!removed.contains(entry.index())) {
                sectors += entry.sectorCount();
            }
        }
        return sectors == 0 ? 0 : RegionFile.HEADER_BYTES + sectors * RegionFile.SECTOR_BYTES;
    }

    /**
     * Removes the given chunk indices from the file. The region must be {@link
     * RegionFile#rewritable()}; the caller has checked it during the scan.
     */
    public static void remove(RegionFile region, Set<Integer> removed) throws IOException {
        if (!region.rewritable()) {
            throw new IOException("region file cannot be rebuilt byte for byte: " + region.path());
        }
        List<ChunkEntry> kept = new ArrayList<>();
        List<Path> externalFiles = new ArrayList<>();
        try (FileChannel source = FileChannel.open(region.path(), StandardOpenOption.READ)) {
            for (ChunkEntry entry : region.entries()) {
                if (!removed.contains(entry.index())) {
                    kept.add(entry);
                } else if (ChunkReader.isExternal(source, entry)) {
                    externalFiles.add(ChunkReader.externalFile(region, entry));
                }
            }
            if (kept.isEmpty()) {
                Files.delete(region.path());
            } else {
                write(region, kept, source);
            }
        }
        for (Path external : externalFiles) {
            Files.deleteIfExists(external);
        }
    }

    private static void write(RegionFile region, List<ChunkEntry> kept, FileChannel source) throws IOException {
        Path target = region.path();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        ByteBuffer header = ByteBuffer.allocate(RegionFile.HEADER_BYTES);
        int nextSector = 2;
        for (ChunkEntry entry : kept) {
            header.putInt(entry.index() * 4, nextSector << 8 | entry.sectorCount());
            header.putInt(RegionFile.SECTOR_BYTES + entry.index() * 4, entry.timestamp());
            nextSector += entry.sectorCount();
        }
        try (FileChannel out = FileChannel.open(
                temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeFully(out, header, 0);
            long position = RegionFile.HEADER_BYTES;
            for (ChunkEntry entry : kept) {
                long copied = 0;
                while (copied < entry.byteLength()) {
                    long moved = source.transferTo(
                            entry.byteOffset() + copied, entry.byteLength() - copied, out.position(position + copied));
                    if (moved <= 0) {
                        throw new IOException("could not copy chunk " + entry.index() + " of " + target);
                    }
                    copied += moved;
                }
                position += entry.byteLength();
            }
            out.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at);
        }
    }
}

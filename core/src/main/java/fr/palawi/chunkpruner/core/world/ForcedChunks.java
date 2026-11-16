package fr.palawi.chunkpruner.core.world;

import fr.palawi.chunkpruner.core.nbt.NbtFieldReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The force-loaded chunks of one dimension (K3), read from its saved data. Checked in the
 * decompiled sources and in files the game wrote:
 *
 * <ul>
 *   <li>Before data version 4772 (1.20.1, 1.21.1): {@code <dimension>/data/chunks.dat}, written by
 *       {@code ForcedChunksSavedData}, with the chunks of {@code /forceload} in the long array
 *       {@code data.Forced}. Forge and NeoForge add the chunks forced by mods in
 *       {@code data.ForgeForced} and {@code data.ModForced}: lists of owners, each with a list of
 *       entries holding a {@code Chunk} long.
 *   <li>From data version 4772 (26.3): {@code <dimension>/data/minecraft/chunk_tickets.dat},
 *       written by {@code TicketStorage}, with every saved ticket in {@code data.tickets}: a
 *       {@code chunk_pos} int array of two and a {@code type} such as {@code minecraft:forced}.
 * </ul>
 *
 * <p>Chunk positions are packed as in {@code ChunkPos.pack}: x in the low 32 bits, z in the high
 * ones. When the file cannot be read, or holds data this version does not know (a mod's own
 * fields next to {@code tickets}), the forced chunks are unknown and every chunk of the dimension
 * counts as forced.
 */
public final class ForcedChunks {
    private static final String DATA = "data";

    private final Set<Long> chunks;
    private final String unknownReason;

    private ForcedChunks(Set<Long> chunks, String unknownReason) {
        this.chunks = chunks;
        this.unknownReason = unknownReason;
    }

    public static ForcedChunks none() {
        return new ForcedChunks(Set.of(), null);
    }

    public static Path file(Path dimensionFolder, WorldLayout layout) {
        Path data = dimensionFolder.resolve("data");
        return layout == WorldLayout.LEGACY
                ? data.resolve("chunks.dat")
                : data.resolve("minecraft").resolve("chunk_tickets.dat");
    }

    public static ForcedChunks read(Path dimensionFolder, WorldLayout layout) {
        Path file = file(dimensionFolder, layout);
        if (!Files.isRegularFile(file)) {
            return none();
        }
        Object data;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            data = NbtFieldReader.read(in, Set.of(DATA)).get(DATA);
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException | RuntimeException e) {
            return unknown(file.getFileName() + " cannot be read: " + e.getMessage());
        }
        if (!(data instanceof Map<?, ?> map)) {
            return unknown(file.getFileName() + " has no data compound");
        }
        Set<Long> found = new HashSet<>();
        if (layout == WorldLayout.LEGACY) {
            if (map.get("Forced") instanceof long[] forced) {
                for (long chunk : forced) {
                    found.add(chunk);
                }
            }
            collectChunkLongs(map.get("ForgeForced"), found);
            collectChunkLongs(map.get("ModForced"), found);
            return new ForcedChunks(Set.copyOf(found), null);
        }
        for (Object key : map.keySet()) {
            if (!"tickets".equals(key)) {
                return unknown(file.getFileName() + " has data this version does not read (" + key + ")");
            }
        }
        if (map.get("tickets") instanceof List<?> tickets) {
            for (Object ticket : tickets) {
                if (!(ticket instanceof Map<?, ?> t) || !(t.get("chunk_pos") instanceof int[] pos) || pos.length != 2) {
                    return unknown(file.getFileName() + " has a ticket without a chunk_pos");
                }
                found.add(pack(pos[0], pos[1]));
            }
        }
        return new ForcedChunks(Set.copyOf(found), null);
    }

    private static void collectChunkLongs(Object value, Set<Long> found) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, inner) -> {
                if ("Chunk".equals(key) && inner instanceof Long chunk) {
                    found.add(chunk);
                } else {
                    collectChunkLongs(inner, found);
                }
            });
        } else if (value instanceof List<?> list) {
            for (Object inner : list) {
                collectChunkLongs(inner, found);
            }
        }
    }

    private static ForcedChunks unknown(String reason) {
        return new ForcedChunks(Set.of(), reason);
    }

    public static long pack(int chunkX, int chunkZ) {
        return chunkX & 0xFFFFFFFFL | (chunkZ & 0xFFFFFFFFL) << 32;
    }

    /** Null when the forced chunks are known; otherwise why every chunk counts as forced. */
    public String unknownReason() {
        return unknownReason;
    }

    public boolean isForced(int chunkX, int chunkZ) {
        return unknownReason != null || chunks.contains(pack(chunkX, chunkZ));
    }

    public int size() {
        return chunks.size();
    }
}

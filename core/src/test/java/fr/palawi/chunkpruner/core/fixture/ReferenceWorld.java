package fr.palawi.chunkpruner.core.fixture;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.jpountz.lz4.LZ4BlockInputStream;

/**
 * Test reader for whole worlds, written apart from the engine. It finds the dimension folders
 * from what is on disk (not from the data version), reads every region entry with its raw
 * sectors, decodes it with the JDK and lz4-java directly, and parses the full NBT tree.
 */
public final class ReferenceWorld {
    public static final String[] STORAGES = {"region", "entities", "poi"};

    private final Path world;
    private final Path overworld;
    private final Map<String, Map<String, Object>> levelData;
    private final List<int[]> overworldPoints = new ArrayList<>();

    private ReferenceWorld(Path world) throws IOException {
        this.world = world;
        Path modern = world.resolve("dimensions/minecraft/overworld");
        this.overworld = Files.isDirectory(modern.resolve("region")) ? modern : world;
        this.levelData = Map.of("level", readGzip(world.resolve("level.dat")));
        readPoints();
    }

    public static ReferenceWorld read(Path world) throws IOException {
        return new ReferenceWorld(world);
    }

    public Path overworld() {
        return overworld;
    }

    /** Overworld K2 points as chunk coordinates: world spawn, player positions and respawns. */
    public List<int[]> overworldPoints() {
        return overworldPoints;
    }

    public Map<String, Object> level() {
        return ReferenceNbt.compound(levelData.get("level"), "Data");
    }

    /** One entry of a region file, as stored. {@code nbt} is null when it cannot be decoded. */
    public record Chunk(
            String storage,
            String file,
            int index,
            int x,
            int z,
            int sectorOffset,
            int sectorCount,
            int timestamp,
            byte[] sectors,
            int compression,
            boolean external,
            Map<String, Object> nbt) {
        public boolean readable() {
            return nbt != null;
        }

        public long inhabitedTime() {
            Object value = nbt == null ? null : nbt.get("InhabitedTime");
            return value instanceof Long l ? l : -1;
        }

        public long key() {
            return (long) x << 32 | (z & 0xFFFFFFFFL);
        }
    }

    public List<Path> files(String storage) throws IOException {
        Path folder = overworld.resolve(storage);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .sorted()
                    .toList();
        }
    }

    public List<Chunk> chunks(String storage, boolean lz4) throws IOException {
        List<Chunk> result = new ArrayList<>();
        for (Path file : files(storage)) {
            result.addAll(chunks(storage, file, lz4));
        }
        return result;
    }

    public static List<Chunk> chunks(String storage, Path file, boolean lz4) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<Chunk> result = new ArrayList<>();
        if (bytes.length < 8192) {
            return result;
        }
        String[] parts = file.getFileName().toString().split("\\.");
        int rx = Integer.parseInt(parts[1]);
        int rz = Integer.parseInt(parts[2]);
        DataInputStream header = new DataInputStream(new ByteArrayInputStream(bytes, 0, 8192));
        int[] locations = new int[1024];
        int[] timestamps = new int[1024];
        for (int i = 0; i < 1024; i++) {
            locations[i] = header.readInt();
        }
        for (int i = 0; i < 1024; i++) {
            timestamps[i] = header.readInt();
        }
        for (int i = 0; i < 1024; i++) {
            int location = locations[i];
            if (location == 0) {
                continue;
            }
            int offset = location >>> 8;
            int count = location & 0xFF;
            int x = rx * 32 + (i & 31);
            int z = rz * 32 + (i >> 5);
            long start = offset * 4096L;
            long end = start + count * 4096L;
            if (offset < 2 || count == 0 || end > bytes.length) {
                result.add(new Chunk(
                        storage,
                        file.getFileName().toString(),
                        i,
                        x,
                        z,
                        offset,
                        count,
                        timestamps[i],
                        null,
                        -1,
                        false,
                        null));
                continue;
            }
            byte[] sectors = new byte[count * 4096];
            System.arraycopy(bytes, (int) start, sectors, 0, sectors.length);
            int length = (sectors[0] & 0xFF) << 24
                    | (sectors[1] & 0xFF) << 16
                    | (sectors[2] & 0xFF) << 8
                    | sectors[3] & 0xFF;
            int type = sectors[4] & 0xFF;
            boolean external = (type & 0x80) != 0;
            int compression = type & 0x7F;
            Map<String, Object> nbt = decode(file, x, z, sectors, length, compression, external, lz4);
            result.add(new Chunk(
                    storage,
                    file.getFileName().toString(),
                    i,
                    x,
                    z,
                    offset,
                    count,
                    timestamps[i],
                    sectors,
                    compression,
                    external,
                    nbt));
        }
        return result;
    }

    private static Map<String, Object> decode(
            Path file, int x, int z, byte[] sectors, int length, int compression, boolean external, boolean lz4) {
        try {
            InputStream raw;
            if (external) {
                raw = Files.newInputStream(file.resolveSibling("c." + x + "." + z + ".mcc"));
            } else {
                if (length <= 0 || length - 1 > sectors.length - 5) {
                    return null;
                }
                raw = new ByteArrayInputStream(sectors, 5, length - 1);
            }
            InputStream data;
            switch (compression) {
                case 1 -> data = new GZIPInputStream(raw);
                case 2 -> data = new InflaterInputStream(raw);
                case 3 -> data = raw;
                case 4 -> {
                    if (!lz4) {
                        return null;
                    }
                    data = new LZ4BlockInputStream(raw);
                }
                default -> {
                    return null;
                }
            }
            try (InputStream in = data) {
                return ReferenceNbt.readRoot(in.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** True when any section palette of the chunk names the block. */
    public static boolean hasBlock(Chunk chunk, String block) {
        if (chunk.nbt() == null || !(chunk.nbt().get("sections") instanceof List<?> sections)) {
            return false;
        }
        for (Object section : sections) {
            if (section instanceof Map<?, ?> s
                    && s.get("block_states") instanceof Map<?, ?> states
                    && states.get("palette") instanceof List<?> palette) {
                for (Object entry : palette) {
                    // A compound with Name on 1.20.1 and 1.21.1, a plain string on 26.3.
                    Object name = entry instanceof Map<?, ?> e ? e.get("Name") : entry;
                    if (block.equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void readPoints() throws IOException {
        Map<String, Object> data = level();
        if (data.get("spawn") instanceof Map<?, ?> spawn && spawn.get("pos") instanceof int[] pos) {
            if ("minecraft:overworld".equals(spawn.get("dimension"))) {
                overworldPoints.add(chunkOf(pos[0], pos[2]));
            }
        } else if (data.get("SpawnX") instanceof Integer x && data.get("SpawnZ") instanceof Integer z) {
            overworldPoints.add(chunkOf(x, z));
        }
        if (data.get("Player") instanceof Map<?, ?> host) {
            playerPoints(host);
        }
        for (String folder : List.of("playerdata", "players/data")) {
            Path dir = world.resolve(folder);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file :
                            files.filter(p -> p.toString().endsWith(".dat")).toList()) {
                        playerPoints(readGzip(file));
                    }
                }
            }
        }
    }

    private void playerPoints(Map<?, ?> player) {
        Object dimension = player.get("Dimension");
        if (player.get("Pos") instanceof List<?> pos
                && (dimension == null || "minecraft:overworld".equals(dimension))) {
            overworldPoints.add(chunkOf((int) Math.floor((Double) pos.get(0)), (int) Math.floor((Double) pos.get(2))));
        }
        if (player.get("respawn") instanceof Map<?, ?> respawn && respawn.get("pos") instanceof int[] pos) {
            if ("minecraft:overworld".equals(respawn.get("dimension"))) {
                overworldPoints.add(chunkOf(pos[0], pos[2]));
            }
        } else if (player.get("SpawnX") instanceof Integer x && player.get("SpawnZ") instanceof Integer z) {
            Object spawnDimension = player.get("SpawnDimension");
            if (spawnDimension == null || "minecraft:overworld".equals(spawnDimension)) {
                overworldPoints.add(chunkOf(x, z));
            }
        }
    }

    private static int[] chunkOf(int blockX, int blockZ) {
        return new int[] {Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)};
    }

    private static Map<String, Object> readGzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return ReferenceNbt.readRoot(in.readAllBytes());
        }
    }

    /** Every file of the world with its SHA-256, by path relative to the world folder. */
    public static Map<String, String> checksums(Path world) throws IOException {
        Map<String, String> sums = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(world)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                sums.put(world.relativize(file).toString().replace('\\', '/'), sha256(Files.readAllBytes(file)));
            }
        }
        return sums;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

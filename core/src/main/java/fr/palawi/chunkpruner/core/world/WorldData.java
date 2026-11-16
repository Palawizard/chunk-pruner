package fr.palawi.chunkpruner.core.world;

import fr.palawi.chunkpruner.core.nbt.NbtFieldReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * What a run reads from {@code level.dat} and the player files: the data version (which gives the
 * folder layout), the version name, and the protected points of K2.
 *
 * <p>Keys checked in the decompiled sources and in files written by the game:
 *
 * <ul>
 *   <li>1.20.1 and 1.21.1: world spawn {@code Data.SpawnX/SpawnZ} (overworld), host player in
 *       {@code Data.Player}; player last position {@code Pos} (doubles) and {@code Dimension},
 *       respawn {@code SpawnX/SpawnZ} and {@code SpawnDimension} (overworld when absent).
 *   <li>26.3: world spawn {@code Data.spawn} with {@code dimension} and {@code pos} (int array),
 *       no host player in {@code level.dat}; player respawn {@code respawn} with {@code dimension}
 *       and {@code pos}.
 * </ul>
 */
public final class WorldData {
    private static final Set<String> LEVEL_KEYS = Set.of(
            "Data.DataVersion",
            "Data.Version.Name",
            "Data.SpawnX",
            "Data.SpawnZ",
            "Data.spawn.dimension",
            "Data.spawn.pos",
            "Data.Player.Pos",
            "Data.Player.Dimension",
            "Data.Player.SpawnX",
            "Data.Player.SpawnZ",
            "Data.Player.SpawnDimension",
            "Data.Player.respawn.dimension",
            "Data.Player.respawn.pos",
            "Data.Player.UUID");
    private static final Set<String> PLAYER_KEYS =
            Set.of("Pos", "Dimension", "SpawnX", "SpawnZ", "SpawnDimension", "respawn.dimension", "respawn.pos");

    private final int dataVersion;
    private final String versionName;
    private final WorldLayout layout;
    private final List<ProtectedPoint> points;
    private final Set<UUID> players;

    private WorldData(int dataVersion, String versionName, List<ProtectedPoint> points, Set<UUID> players) {
        this.dataVersion = dataVersion;
        this.versionName = versionName;
        this.layout = WorldLayout.forDataVersion(dataVersion);
        this.points = List.copyOf(points);
        this.players = Set.copyOf(players);
    }

    public static WorldData read(Path world) throws WorldException {
        Path levelDat = world.resolve("level.dat");
        Map<String, Object> level = readGzip(levelDat, LEVEL_KEYS);
        if (!(level.get("Data.DataVersion") instanceof Long dataVersion)) {
            throw new WorldException("level.dat has no Data.DataVersion: " + levelDat);
        }
        List<ProtectedPoint> points = new ArrayList<>();
        addSpawn(points, level);
        addPlayer(points, level, "Data.Player.", "host player in level.dat");
        Set<UUID> players = new HashSet<>();
        if (level.get("Data.Player.UUID") instanceof int[] uuid && uuid.length == 4) {
            players.add(new UUID(
                    (long) uuid[0] << 32 | uuid[1] & 0xFFFFFFFFL, (long) uuid[2] << 32 | uuid[3] & 0xFFFFFFFFL));
        }
        WorldLayout layout = WorldLayout.forDataVersion(dataVersion.intValue());
        Path playerFolder = layout.playerDataFolder(world);
        if (Files.isDirectory(playerFolder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(playerFolder, "*.dat")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    String id = name.substring(0, name.length() - 4);
                    if (isUuid(id)) {
                        addPlayer(points, readGzip(file, PLAYER_KEYS), "", "player " + name);
                        players.add(UUID.fromString(id));
                    }
                }
            } catch (IOException e) {
                throw new WorldException("cannot list the player files in " + playerFolder, e);
            }
        }
        Object name = level.get("Data.Version.Name");
        return new WorldData(dataVersion.intValue(), name instanceof String s ? s : null, points, players);
    }

    private static void addSpawn(List<ProtectedPoint> points, Map<String, Object> level) {
        if (level.get("Data.spawn.pos") instanceof int[] pos && pos.length == 3) {
            DimensionId dimension = dimension(level.get("Data.spawn.dimension"));
            points.add(new ProtectedPoint(dimension, pos[0], pos[2], "world spawn"));
        } else if (level.get("Data.SpawnX") instanceof Long x && level.get("Data.SpawnZ") instanceof Long z) {
            points.add(new ProtectedPoint(DimensionId.OVERWORLD, x.intValue(), z.intValue(), "world spawn"));
        }
    }

    private static void addPlayer(List<ProtectedPoint> points, Map<String, Object> data, String prefix, String who) {
        if (data.get(prefix + "Pos") instanceof List<?> pos
                && pos.size() == 3
                && pos.get(0) instanceof Double x
                && pos.get(2) instanceof Double z) {
            DimensionId dimension = dimension(data.get(prefix + "Dimension"));
            points.add(new ProtectedPoint(dimension, floor(x), floor(z), "last position of the " + who));
        }
        if (data.get(prefix + "respawn.pos") instanceof int[] pos && pos.length == 3) {
            DimensionId dimension = dimension(data.get(prefix + "respawn.dimension"));
            points.add(new ProtectedPoint(dimension, pos[0], pos[2], "respawn point of the " + who));
        } else if (data.get(prefix + "SpawnX") instanceof Long x && data.get(prefix + "SpawnZ") instanceof Long z) {
            DimensionId dimension = dimension(data.get(prefix + "SpawnDimension"));
            points.add(new ProtectedPoint(dimension, x.intValue(), z.intValue(), "respawn point of the " + who));
        }
    }

    /** The game uses the overworld when the dimension is absent or unknown, and so does K2. */
    private static DimensionId dimension(Object value) {
        if (value instanceof String id) {
            try {
                return DimensionId.parse(id);
            } catch (IllegalArgumentException e) {
                return DimensionId.OVERWORLD;
            }
        }
        return DimensionId.OVERWORLD;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static boolean isUuid(String text) {
        try {
            return UUID.fromString(text).toString().equalsIgnoreCase(text);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, Object> readGzip(Path file, Set<String> keys) throws WorldException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            Map<String, Object> values = NbtFieldReader.read(in, keys);
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return values;
        } catch (NoSuchFileException e) {
            throw new WorldException("no " + file.getFileName() + " in " + file.getParent());
        } catch (IOException e) {
            throw new WorldException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public int dataVersion() {
        return dataVersion;
    }

    /** The Minecraft version that last saved the world, or null. */
    public String versionName() {
        return versionName;
    }

    public WorldLayout layout() {
        return layout;
    }

    public List<ProtectedPoint> protectedPoints() {
        return points;
    }

    /** The UUIDs of the player files and of the host player in {@code level.dat}. */
    public Set<UUID> players() {
        return players;
    }
}

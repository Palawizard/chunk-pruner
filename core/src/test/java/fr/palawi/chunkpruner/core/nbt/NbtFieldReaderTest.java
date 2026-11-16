package fr.palawi.chunkpruner.core.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceNbt;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** On real files from the fixture worlds, checked against the full tree of the test reader. */
class NbtFieldReaderTest {
    private static byte[] levelDat(String world) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(
                Files.newInputStream(FixtureWorlds.committed(world).resolve("level.dat")))) {
            return in.readAllBytes();
        }
    }

    private static byte[] firstChunkPayload(String world) throws Exception {
        Chunk chunk = ReferenceWorld.read(FixtureWorlds.committed(world))
                .chunks("region", false)
                .get(0);
        int length = (chunk.sectors()[0] & 0xFF) << 24
                | (chunk.sectors()[1] & 0xFF) << 16
                | (chunk.sectors()[2] & 0xFF) << 8
                | chunk.sectors()[3] & 0xFF;
        try (InflaterInputStream in =
                new InflaterInputStream(new ByteArrayInputStream(chunk.sectors(), 5, length - 1))) {
            return in.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.20.1", "1.21.1", "26.3"})
    void readsTheRequestedLevelDatFieldsOnly(String world) throws Exception {
        byte[] data = levelDat(world);
        Map<String, Object> tree = ReferenceNbt.readRoot(data);
        Map<String, Object> dataTag = ReferenceNbt.compound(tree, "Data");
        Map<String, Object> found = NbtFieldReader.read(
                new ByteArrayInputStream(data),
                Set.of("Data.DataVersion", "Data.Version.Name", "Data.SpawnX", "Data.spawn.pos", "Data.Missing"));
        assertEquals(((Integer) dataTag.get("DataVersion")).longValue(), found.get("Data.DataVersion"));
        assertEquals(world, found.get("Data.Version.Name"));
        if (world.equals("26.3")) {
            assertArrayEquals(
                    (int[]) ((Map<?, ?>) dataTag.get("spawn")).get("pos"), (int[]) found.get("Data.spawn.pos"));
            assertTrue(!found.containsKey("Data.SpawnX"));
        } else {
            assertEquals(((Integer) dataTag.get("SpawnX")).longValue(), found.get("Data.SpawnX"));
        }
        assertTrue(!found.containsKey("Data.Missing"));
    }

    @Test
    void keepsAListOfDoubles() throws Exception {
        Path players = FixtureWorlds.committed("1.20.1").resolve("playerdata");
        Path file;
        try (var files = Files.list(players)) {
            file = files.findFirst().orElseThrow();
        }
        byte[] data;
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            data = in.readAllBytes();
        }
        Map<String, Object> tree = ReferenceNbt.readRoot(data);
        Map<String, Object> found = NbtFieldReader.read(new ByteArrayInputStream(data), Set.of("Pos", "Dimension"));
        assertEquals(tree.get("Pos"), found.get("Pos"));
        assertEquals("minecraft:overworld", found.get("Dimension"));
    }

    @Test
    void readsChunkFields() throws Exception {
        byte[] data = firstChunkPayload("1.21.1");
        Map<String, Object> tree = ReferenceNbt.readRoot(data);
        Map<String, Object> found =
                NbtFieldReader.read(new ByteArrayInputStream(data), Set.of("xPos", "zPos", "InhabitedTime", "Status"));
        assertEquals(((Integer) tree.get("xPos")).longValue(), found.get("xPos"));
        assertEquals(((Integer) tree.get("zPos")).longValue(), found.get("zPos"));
        assertEquals(tree.get("InhabitedTime"), found.get("InhabitedTime"));
        assertEquals(tree.get("Status"), found.get("Status"));
    }

    @Test
    void aTruncatedPayloadIsAnError() throws Exception {
        byte[] data = firstChunkPayload("26.3");
        for (int cut : new int[] {0, 1, 3, data.length / 2, data.length - 1}) {
            byte[] shorter = Arrays.copyOf(data, cut);
            assertThrows(
                    NbtFormatException.class,
                    () -> NbtFieldReader.read(new ByteArrayInputStream(shorter), Set.of("xPos")));
        }
    }

    @Test
    void aRootThatIsNotACompoundIsAnError() throws Exception {
        byte[] data = firstChunkPayload("1.20.1");
        data[0] = 9;
        assertThrows(
                NbtFormatException.class, () -> NbtFieldReader.read(new ByteArrayInputStream(data), Set.of("xPos")));
    }

    /** The list size of "sections", set to a negative number. */
    @Test
    void aNegativeListSizeIsAnError() throws Exception {
        byte[] data = firstChunkPayload("1.21.1");
        int at = indexOf(data, "sections".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(at > 0 && data[at - 3] == 9, "sections is a list tag");
        int sizeAt = at + "sections".length() + 1;
        data[sizeAt] = (byte) 0xFF;
        assertThrows(
                NbtFormatException.class, () -> NbtFieldReader.read(new ByteArrayInputStream(data), Set.of("xPos")));
    }

    @Test
    void anUnknownTagTypeIsAnError() throws Exception {
        byte[] data = firstChunkPayload("1.21.1");
        int at = indexOf(data, "Status".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        data[at - 3] = 13;
        assertThrows(
                NbtFormatException.class, () -> NbtFieldReader.read(new ByteArrayInputStream(data), Set.of("xPos")));
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

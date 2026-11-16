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

    /** A village start chunk of the 1.21.1 fixture: its structures compound, read whole. */
    @Test
    void keepsARequestedCompoundWhole() throws Exception {
        Chunk start = null;
        for (Chunk c : ReferenceWorld.read(FixtureWorlds.committed("1.21.1")).chunks("region", false)) {
            if (c.readable()
                    && c.nbt().get("structures") instanceof Map<?, ?> s
                    && s.get("starts") instanceof Map<?, ?> starts
                    && !starts.isEmpty()) {
                start = c;
                break;
            }
        }
        assertTrue(start != null, "the fixture has a structure start");
        byte[] data = payload(start);
        Map<String, Object> structures = ReferenceNbt.compound(ReferenceNbt.readRoot(data), "structures");
        Map<String, Object> found = NbtFieldReader.read(
                new ByteArrayInputStream(data), Set.of("structures.starts", "structures.References"));
        assertSameTree(structures.get("starts"), found.get("structures.starts"));
        assertSameTree(structures.get("References"), found.get("structures.References"));
    }

    /** An entities chunk with animals: the Entities list of compounds, read whole. */
    @Test
    void keepsARequestedListOfCompounds() throws Exception {
        Chunk withEntities = null;
        for (Chunk c : ReferenceWorld.read(FixtureWorlds.committed("26.3")).chunks("entities", false)) {
            if (c.readable() && c.nbt().get("Entities") instanceof java.util.List<?> list && !list.isEmpty()) {
                withEntities = c;
                break;
            }
        }
        assertTrue(withEntities != null, "the fixture has entities");
        byte[] data = payload(withEntities);
        Map<String, Object> found = NbtFieldReader.read(new ByteArrayInputStream(data), Set.of("Entities", "Position"));
        assertSameTree(ReferenceNbt.readRoot(data).get("Entities"), found.get("Entities"));
        assertArrayEquals((int[]) ReferenceNbt.readRoot(data).get("Position"), (int[]) found.get("Position"));
    }

    @Test
    void nestingUpTo512IsReadAndDeeperIsAnError() throws Exception {
        assertEquals(
                1L,
                NbtFieldReader.read(new ByteArrayInputStream(nested(511, false)), Set.of("x"))
                        .get("x"));
        assertEquals(
                1L,
                NbtFieldReader.read(new ByteArrayInputStream(nested(511, true)), Set.of("x"))
                        .get("x"));
        assertThrows(
                NbtFormatException.class,
                () -> NbtFieldReader.read(new ByteArrayInputStream(nested(512, false)), Set.of("x")));
        assertThrows(
                NbtFormatException.class,
                () -> NbtFieldReader.read(new ByteArrayInputStream(nested(512, true)), Set.of("x")));
        assertThrows(
                NbtFormatException.class,
                () -> NbtFieldReader.read(new ByteArrayInputStream(nested(512, false)), Set.of("a")));
    }

    /**
     * A root compound holding {@code x: 1b} and {@code a}, which nests {@code levels} more
     * compounds (or lists of one compound) inside it. The root is depth 1, so {@code levels} 511
     * reaches the limit of 512 exactly.
     */
    private static byte[] nested(int levels, boolean lists) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(1);
        out.writeUTF("x");
        out.writeByte(1);
        out.writeByte(lists ? 9 : 10);
        out.writeUTF("a");
        for (int i = 1; i < levels; i++) {
            if (lists) {
                out.writeByte(9);
                out.writeInt(1);
            } else {
                out.writeByte(10);
                out.writeUTF("a");
            }
        }
        if (lists) {
            out.writeByte(0);
            out.writeInt(0);
        } else {
            out.writeByte(0);
            for (int i = 1; i < levels; i++) {
                out.writeByte(0);
            }
        }
        out.writeByte(0);
        return bytes.toByteArray();
    }

    private static byte[] payload(Chunk chunk) throws Exception {
        int length = (chunk.sectors()[0] & 0xFF) << 24
                | (chunk.sectors()[1] & 0xFF) << 16
                | (chunk.sectors()[2] & 0xFF) << 8
                | chunk.sectors()[3] & 0xFF;
        try (InflaterInputStream in =
                new InflaterInputStream(new ByteArrayInputStream(chunk.sectors(), 5, length - 1))) {
            return in.readAllBytes();
        }
    }

    /** The test reader keeps narrow number types; the engine's reader widens them. */
    private static void assertSameTree(Object expected, Object actual) {
        if (expected instanceof Map<?, ?> e) {
            assertTrue(actual instanceof Map<?, ?>, "a compound");
            Map<?, ?> a = (Map<?, ?>) actual;
            assertEquals(e.keySet(), a.keySet());
            e.forEach((k, v) -> assertSameTree(v, a.get(k)));
        } else if (expected instanceof java.util.List<?> e) {
            assertTrue(actual instanceof java.util.List<?>, "a list");
            java.util.List<?> a = (java.util.List<?>) actual;
            assertEquals(e.size(), a.size());
            for (int i = 0; i < e.size(); i++) {
                assertSameTree(e.get(i), a.get(i));
            }
        } else if (expected instanceof Byte || expected instanceof Short || expected instanceof Integer) {
            assertEquals(((Number) expected).longValue(), actual);
        } else if (expected instanceof Float f) {
            assertEquals(f.doubleValue(), actual);
        } else if (expected instanceof long[] e) {
            assertArrayEquals(e, (long[]) actual);
        } else if (expected instanceof int[] e) {
            assertArrayEquals(e, (int[]) actual);
        } else if (expected instanceof byte[]) {
            assertTrue(actual instanceof byte[], "a byte array, not kept");
        } else {
            assertEquals(expected, actual);
        }
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

package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.assertMatches;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edge cases built from copies of real files written by the game: a region whose entities file
 * is cut inside its header, a region file cut inside its last chunk, and chunks moved to a
 * {@code .mcc} file the way {@code RegionFile.write} stores a chunk of 256 sectors or more (a
 * 5-byte stub with length 1 and the compression id plus {@code 0x80}, the compressed data in
 * {@code c.<x>.<z>.mcc}). None of the fixture worlds has such a large chunk.
 */
class RegionEdgeFixtureTest {
    @TempDir
    Path temp;

    private Path world() throws Exception {
        return FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("world"));
    }

    @Test
    void aRegionWhoseEntitiesFileIsCutKeepsAllItsChunks() throws Exception {
        Path world = world();
        ReferenceWorld reference = ReferenceWorld.read(world);
        Path entities = reference.files("entities").stream()
                .filter(f -> size(f) >= 8192)
                .findFirst()
                .orElseThrow();
        Path region = reference
                .overworld()
                .resolve("region")
                .resolve(entities.getFileName().toString());
        Expected clean = Expected.compute(reference, 0, 4, 1, true);
        assertTrue(
                ReferenceWorld.chunks("region", region, true).stream().anyMatch(c -> clean.pruned.contains(c.key())));
        Files.write(entities, Arrays.copyOf(Files.readAllBytes(entities), 5000));
        byte[] regionBefore = Files.readAllBytes(region);

        Expected expected = Expected.compute(ReferenceWorld.read(world), 0, 4, 1, true);
        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.PRUNE));

        assertMatches(expected, readJson(result.jsonReport()));
        assertTrue(expected.keptByRule.get("K7") > 0);
        assertArrayEquals(regionBefore, Files.readAllBytes(region), "the region file is left as it was");
        assertTrue(
                readJson(result.jsonReport()).get("warnings").toString().contains("cannot be rebuilt byte for byte"));
    }

    @Test
    void aRegionFileCutInsideItsLastChunkIsLeftAsItWas() throws Exception {
        Path world = world();
        ReferenceWorld reference = ReferenceWorld.read(world);
        Path region = reference.files("region").get(0);
        Chunk last = ReferenceWorld.chunks("region", region, true).stream()
                .max(Comparator.comparingInt(Chunk::sectorOffset))
                .orElseThrow();
        long cut = (last.sectorOffset() + last.sectorCount()) * 4096L - 100;
        Files.write(region, Arrays.copyOf(Files.readAllBytes(region), (int) cut));
        byte[] regionBefore = Files.readAllBytes(region);

        Expected expected = Expected.compute(ReferenceWorld.read(world), 0, 4, 1, true);
        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.PRUNE));

        assertMatches(expected, readJson(result.jsonReport()));
        assertArrayEquals(regionBefore, Files.readAllBytes(region));
        String warnings = readJson(result.jsonReport()).get("warnings").toString();
        assertTrue(warnings.contains("location entry points outside the file"), warnings);
    }

    @Test
    void chunksInMccFilesAreReadPrunedWithTheirFileOrKept() throws Exception {
        Path world = world();
        ReferenceWorld reference = ReferenceWorld.read(world);
        Expected clean = Expected.compute(reference, 0, 4, 1, true);
        List<Chunk> region = reference.chunks("region", true);
        Chunk toPrune = region.stream()
                .filter(c -> clean.pruned.contains(c.key()))
                .findFirst()
                .orElseThrow();
        Chunk toKeep = region.stream()
                .filter(c -> "K1".equals(clean.ruleByChunk.get(c.key())))
                .findFirst()
                .orElseThrow();
        Path regionFolder = reference.overworld().resolve("region");
        Path prunedMcc = externalize(regionFolder, toPrune);
        Path keptMcc = externalize(regionFolder, toKeep);
        byte[] keptMccBytes = Files.readAllBytes(keptMcc);

        ReferenceWorld moved = ReferenceWorld.read(world);
        Expected expected = Expected.compute(moved, 0, 4, 1, true);
        assertEquals(clean.pruned, expected.pruned, "the test reader reads the .mcc files");
        Chunk keptStub = moved.chunks("region", true).stream()
                .filter(c -> c.key() == toKeep.key())
                .findFirst()
                .orElseThrow();
        Engine.RunResult result = Engine.run(settings(world, Config.defaults(), RunMode.PRUNE));

        assertMatches(expected, readJson(result.jsonReport()));
        assertFalse(Files.exists(prunedMcc), "the .mcc file of a pruned chunk goes with it");
        assertArrayEquals(keptMccBytes, Files.readAllBytes(keptMcc));
        Chunk after = ReferenceWorld.read(world).chunks("region", true).stream()
                .filter(c -> c.key() == toKeep.key())
                .findFirst()
                .orElseThrow();
        assertTrue(after.external() && after.readable());
        assertArrayEquals(keptStub.sectors(), after.sectors());
        assertTrue(readJson(result.jsonReport()).get("warnings").toString().contains("external-chunk"));
    }

    private static Path externalize(Path regionFolder, Chunk chunk) throws Exception {
        byte[] s = chunk.sectors();
        int length = (s[0] & 0xFF) << 24 | (s[1] & 0xFF) << 16 | (s[2] & 0xFF) << 8 | s[3] & 0xFF;
        Path mcc = regionFolder.resolve("c." + chunk.x() + "." + chunk.z() + ".mcc");
        Files.write(mcc, Arrays.copyOfRange(s, 5, 5 + length - 1));
        try (RandomAccessFile file =
                new RandomAccessFile(regionFolder.resolve(chunk.file()).toFile(), "rw")) {
            file.seek(chunk.sectorOffset() * 4096L);
            file.writeInt(1);
            file.write(chunk.compression() | 0x80);
            file.seek(chunk.index() * 4L);
            file.writeInt(chunk.sectorOffset() << 8 | 1);
        }
        return mcc;
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

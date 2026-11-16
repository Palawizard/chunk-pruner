package fr.palawi.chunkpruner.core.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFileTest {
    @TempDir
    Path temp;

    @Test
    void namesGiveRegionCoordinates() {
        assertArrayEquals(new int[] {-1, 12}, RegionFile.coordinates("r.-1.12.mca"));
        assertNull(RegionFile.coordinates("r.1.mca"));
        assertNull(RegionFile.coordinates("r.0.0.mca.tmp"));
        assertNull(RegionFile.coordinates("r.0.0.linear"));
    }

    @Test
    void headerEntriesMatchTheTestReader() throws Exception {
        ReferenceWorld world = ReferenceWorld.read(FixtureWorlds.committed("26.3"));
        for (Path file : world.files("region")) {
            RegionFile region = RegionFile.read(file);
            List<Chunk> reference = ReferenceWorld.chunks("region", file, false);
            assertEquals(reference.size(), region.entries().size());
            assertTrue(region.rewritable());
            for (int i = 0; i < reference.size(); i++) {
                RegionFile.ChunkEntry entry = region.entries().get(i);
                assertEquals(reference.get(i).index(), entry.index());
                assertEquals(reference.get(i).sectorOffset(), entry.sectorOffset());
                assertEquals(reference.get(i).sectorCount(), entry.sectorCount());
                assertEquals(reference.get(i).x(), region.chunkX(entry));
                assertEquals(reference.get(i).z(), region.chunkZ(entry));
            }
        }
    }

    /** The game writes empty entities and poi files; they hold no chunk. */
    @Test
    void emptyFilesWrittenByTheGameHoldNoChunk() throws Exception {
        Path entities = FixtureWorlds.committed("1.20.1").resolve("entities");
        int empty = 0;
        try (var files = Files.list(entities)) {
            for (Path file : files.toList()) {
                if (Files.size(file) == 0) {
                    empty++;
                    RegionFile region = RegionFile.read(file);
                    assertTrue(region.entries().isEmpty());
                    assertTrue(region.rewritable());
                }
            }
        }
        assertTrue(empty > 0, "the 1.20.1 fixture has empty entities files");
    }

    /** A copy of a real region file cut inside its header. */
    @Test
    void aTruncatedHeaderIsNotRewritable() throws Exception {
        Path source = ReferenceWorld.read(FixtureWorlds.committed("1.21.1"))
                .files("region")
                .get(0);
        Path cut = temp.resolve(source.getFileName().toString());
        Files.write(cut, java.util.Arrays.copyOf(Files.readAllBytes(source), 5000));
        RegionFile region = RegionFile.read(cut);
        assertTrue(region.truncatedHeader());
        assertFalse(region.rewritable());
    }
}

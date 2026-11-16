package fr.palawi.chunkpruner.core.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks the test reader itself against what an independent script (Python, see each world's
 * NOTE.md) measured on the committed files, and against how the worlds were made.
 */
class ReferenceWorldTest {
    @ParameterizedTest
    @CsvSource({
        "1.20.1, 3912, 3706, 158, 48",
        "1.21.1, 3702, 3343, 311, 48",
        "26.3, 3691, 3113, 538, 40",
        "1.21.1-lz4, 1369, 1297, 72, 0"
    })
    void inhabitedTimeSpreadMatchesTheNote(String name, int chunks, int zero, int low, int high) throws Exception {
        List<Chunk> region = ReferenceWorld.read(FixtureWorlds.committed(name)).chunks("region", true);
        assertEquals(chunks, region.size());
        assertTrue(region.stream().allMatch(Chunk::readable));
        assertEquals(zero, region.stream().filter(c -> c.inhabitedTime() == 0).count());
        assertEquals(
                low,
                region.stream()
                        .filter(c -> c.inhabitedTime() > 0 && c.inhabitedTime() < 200)
                        .count());
        assertEquals(high, region.stream().filter(c -> c.inhabitedTime() >= 200).count());
    }

    /** The glass the bot placed in the explored strip made the game save those chunks again. */
    @ParameterizedTest
    @CsvSource({"1.20.1", "1.21.1", "26.3"})
    void chunksWithPlacedGlassHaveHighInhabitedTime(String name) throws Exception {
        List<Chunk> withGlass = ReferenceWorld.read(FixtureWorlds.committed(name)).chunks("region", false).stream()
                .filter(c -> ReferenceWorld.hasBlock(c, "minecraft:glass"))
                .toList();
        assertTrue(withGlass.size() >= 4, "glass chunks: " + withGlass.size());
        for (Chunk chunk : withGlass) {
            assertTrue(chunk.inhabitedTime() >= 200, chunk.x() + "," + chunk.z() + ": " + chunk.inhabitedTime());
        }
    }

    @ParameterizedTest
    @CsvSource({"1.20.1, 3465", "1.21.1, 3955", "26.3, 5023"})
    void levelDataHasTheVersionThatMadeIt(String name, int dataVersion) throws Exception {
        Map<String, Object> data =
                ReferenceWorld.read(FixtureWorlds.committed(name)).level();
        assertEquals(dataVersion, data.get("DataVersion"));
        assertEquals(name, ((Map<?, ?>) data.get("Version")).get("Name"));
    }

    /** World spawn, the player's last position and its respawn point (set with /spawnpoint). */
    @ParameterizedTest
    @CsvSource({"1.20.1", "1.21.1", "26.3"})
    void eachWorldHasThreeOverworldPoints(String name) throws Exception {
        assertEquals(
                3,
                ReferenceWorld.read(FixtureWorlds.committed(name))
                        .overworldPoints()
                        .size());
    }
}

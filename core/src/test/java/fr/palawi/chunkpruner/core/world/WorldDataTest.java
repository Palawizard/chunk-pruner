package fr.palawi.chunkpruner.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorldDataTest {
    @TempDir
    Path temp;

    @ParameterizedTest
    @CsvSource({"1.20.1, 3465, LEGACY", "1.21.1, 3955, LEGACY", "26.3, 5023, DIMENSION_FOLDERS"})
    void readsVersionLayoutAndPoints(String name, int dataVersion, WorldLayout layout) throws Exception {
        Path world = FixtureWorlds.committed(name);
        WorldData data = WorldData.read(world);
        assertEquals(dataVersion, data.dataVersion());
        assertEquals(name, data.versionName());
        assertEquals(layout, data.layout());
        assertTrue(Files.isDirectory(
                layout.dimensionFolder(world, DimensionId.OVERWORLD).resolve("region")));
        assertTrue(Files.isDirectory(layout.playerDataFolder(world)));
        List<ProtectedPoint> points = data.protectedPoints();
        List<int[]> reference = ReferenceWorld.read(world).overworldPoints();
        assertEquals(reference.size(), points.size());
        for (int i = 0; i < points.size(); i++) {
            assertEquals(DimensionId.OVERWORLD, points.get(i).dimension());
            assertEquals(
                    reference.get(i)[0], points.get(i).chunkX(), points.get(i).source());
            assertEquals(
                    reference.get(i)[1], points.get(i).chunkZ(), points.get(i).source());
        }
        assertEquals("world spawn", points.get(0).source());
    }

    @Test
    void legacyAndNewFolders() {
        Path world = Path.of("w");
        assertEquals(world, WorldLayout.LEGACY.dimensionFolder(world, DimensionId.OVERWORLD));
        assertEquals(world.resolve("DIM-1"), WorldLayout.LEGACY.dimensionFolder(world, DimensionId.NETHER));
        assertEquals(world.resolve("DIM1"), WorldLayout.LEGACY.dimensionFolder(world, DimensionId.END));
        DimensionId modded = DimensionId.parse("examplemod:mining");
        assertEquals(world.resolve("dimensions/examplemod/mining"), WorldLayout.LEGACY.dimensionFolder(world, modded));
        assertEquals(
                world.resolve("dimensions/minecraft/the_nether"),
                WorldLayout.DIMENSION_FOLDERS.dimensionFolder(world, DimensionId.NETHER));
        assertEquals(WorldLayout.LEGACY, WorldLayout.forDataVersion(4771));
        assertEquals(WorldLayout.DIMENSION_FOLDERS, WorldLayout.forDataVersion(4772));
    }

    @Test
    void aBrokenPlayerFileStopsTheRun() throws Exception {
        Path world = FixtureWorlds.copy(FixtureWorlds.committed("1.21.1"), temp.resolve("world"));
        Path player;
        try (var files = Files.list(world.resolve("playerdata"))) {
            player =
                    files.filter(p -> p.toString().endsWith(".dat")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(player);
        Files.write(player, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        WorldException e = assertThrows(WorldException.class, () -> WorldData.read(world));
        assertTrue(e.getMessage().contains(player.getFileName().toString()), e.getMessage());
    }

    @Test
    void aMissingLevelDatIsAnError() {
        WorldException e = assertThrows(WorldException.class, () -> WorldData.read(temp));
        assertTrue(e.getMessage().contains("no level.dat"), e.getMessage());
    }
}

package fr.palawi.chunkpruner.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.world.DimensionId;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {
    @Test
    void defaultsAreTheOnesOfTheSpec() {
        Config config = Config.defaults();
        assertEquals(0, config.inhabitedThresholdTicks());
        assertEquals(4, config.protectRadiusChunks());
        assertEquals(1, config.protectNeighborRadiusChunks());
        assertEquals(List.of(DimensionId.OVERWORLD, DimensionId.NETHER, DimensionId.END), config.dimensions());
        assertEquals(List.of(), config.protectedAreas());
        Map<String, Object> effective = config.effective();
        assertEquals(true, effective.get("backup"));
        assertEquals(3, effective.get("backupRetention"));
        assertEquals(Map.of("enabled", false, "intervalDays", 7, "mode", "dry-run"), effective.get("autoPrune"));
    }

    @Test
    void missingKeysTakeDefaultsAndUnknownKeysAreKept() throws Exception {
        Config config = Config.fromJson("{\"zFirst\": [1], \"protectRadiusChunks\": 2,"
                + " \"autoPrune\": {\"enabled\": true, \"extra\": \"x\"}}");
        assertEquals(2, config.protectRadiusChunks());
        assertEquals(1, config.protectNeighborRadiusChunks());
        Map<?, ?> written = (Map<?, ?>) Json.parse(config.toJson());
        assertEquals("zFirst", written.keySet().iterator().next(), "unknown keys stay where they were");
        assertEquals(List.of(new BigDecimal("1")), written.get("zFirst"));
        Map<?, ?> autoPrune = (Map<?, ?>) written.get("autoPrune");
        assertEquals("x", autoPrune.get("extra"));
        assertEquals(true, autoPrune.get("enabled"));
        assertEquals(new BigDecimal("7"), autoPrune.get("intervalDays"));
        assertEquals(new BigDecimal("1"), written.get("protectNeighborRadiusChunks"));
        assertEquals(config.toJson(), Config.fromJson(config.toJson()).toJson(), "writing twice changes nothing");
    }

    @Test
    void unknownKeysInsideAreaEntriesAreKept() throws Exception {
        Config config = Config.fromJson("{\"protectedAreas\": [{\"dimension\": \"overworld\", \"minX\": 0,"
                + " \"minZ\": 0, \"maxX\": 15, \"maxZ\": 15, \"label\": \"base\"}]}");
        Map<?, ?> written = (Map<?, ?>) Json.parse(config.toJson());
        Map<?, ?> area = (Map<?, ?>) ((List<?>) written.get("protectedAreas")).get(0);
        assertEquals("base", area.get("label"));
        assertEquals("overworld", area.get("dimension"));
    }

    @Test
    void readsAreasAndDimensions() throws Exception {
        Config config = Config.fromJson("\uFEFF{\"protectedAreas\": [{\"dimension\": \"minecraft:the_nether\","
                + " \"minX\": -10, \"minZ\": -20, \"maxX\": 30, \"maxZ\": 40}],"
                + " \"dimensions\": {\"include\": [\"overworld\", \"examplemod:mining\"]}}");
        Config.ProtectedArea area = config.protectedAreas().get(0);
        assertEquals(DimensionId.NETHER, area.dimension());
        assertTrue(area.overlapsChunk(DimensionId.NETHER, -1, -2));
        assertTrue(area.overlapsChunk(DimensionId.NETHER, 1, 2));
        assertEquals(false, area.overlapsChunk(DimensionId.NETHER, 2, 0));
        assertEquals(false, area.overlapsChunk(DimensionId.OVERWORLD, 0, 0));
        assertEquals(List.of(DimensionId.OVERWORLD, new DimensionId("examplemod", "mining")), config.dimensions());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "{\"protectRadiusChunks\": -1}",
                "{\"protectRadiusChunks\": 1.5}",
                "{\"protectNeighborRadiusChunks\": \"1\"}",
                "{\"protectNeighborRadiusChunks\": 17}",
                "{\"protectedAreas\": null}",
                "{\"dimensions\": {\"include\": null}}",
                "{\"inhabitedThresholdTicks\": null}",
                "{\"backup\": \"yes\"}",
                "{\"autoPrune\": {\"mode\": \"always\"}}",
                "{\"autoPrune\": 1}",
                "{\"dimensions\": {\"include\": [\"Bad Name\"]}}",
                "{\"dimensions\": {\"include\": [\"../escape\"]}}",
                "{\"protectedAreas\": [{\"dimension\": \"overworld\", \"minX\": 5, \"minZ\": 0, \"maxX\": 4, \"maxZ\": 1}]}",
                "{\"protectedAreas\": [{\"dimension\": \"overworld\", \"minX\": 0}]}"
            })
    void rejectsWrongValues(String text) {
        assertThrows(JsonException.class, () -> Config.fromJson(text));
    }

    @Test
    void aMissingConfigFileIsWrittenWithTheDefaultsAndAnExistingOneIsKept(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("config/chunkpruner.json");
        assertEquals(
                Config.defaults().toJson(), Config.loadOrWriteDefaults(file).toJson());
        assertEquals(Config.defaults().toJson(), Files.readString(file));
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList(), "no temporary file is left");
        }

        Files.writeString(file, "{\"backupRetention\": 5}");
        assertEquals(5, Config.loadOrWriteDefaults(file).backupRetention());
        assertEquals("{\"backupRetention\": 5}", Files.readString(file));
    }
}

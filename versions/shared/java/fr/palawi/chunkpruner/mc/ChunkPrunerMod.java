package fr.palawi.chunkpruner.mc;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.JsonException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What each loader's entry point gives the shared code: its name, the mod version from its
 * metadata (the JAR manifest is not visible to every loader's class loader) and its config folder.
 */
public final class ChunkPrunerMod {
    static final String CONFIG_FILE = "chunkpruner.json";

    private static volatile String loader = "unknown";
    private static volatile String version;
    private static volatile Path configDirectory = Path.of("config");

    private ChunkPrunerMod() {}

    public static void init(String loaderName, String modVersion, Path configFolder) {
        loader = loaderName;
        version = modVersion;
        configDirectory = configFolder;
    }

    static String loader() {
        return loader;
    }

    static String version() {
        return version;
    }

    static Path configFile() {
        return configDirectory.resolve(CONFIG_FILE);
    }

    /** The config file, written with the defaults when it does not exist yet (ADR-005). */
    static Config config() throws IOException, JsonException {
        Path file = configFile();
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Config.defaults().toJson(), StandardCharsets.UTF_8);
        }
        return Config.load(file);
    }
}

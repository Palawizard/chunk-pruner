package fr.palawi.chunkpruner.fabric;

import fr.palawi.chunkpruner.core.ModInfo;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChunkPrunerFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info(ModInfo.loadedLine("fabric", "1.21-1.21.4"));
    }
}

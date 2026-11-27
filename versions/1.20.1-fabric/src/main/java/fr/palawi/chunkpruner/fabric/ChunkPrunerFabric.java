package fr.palawi.chunkpruner.fabric;

import fr.palawi.chunkpruner.core.ModInfo;
import fr.palawi.chunkpruner.mc.ChunkPrunerMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChunkPrunerFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    @Override
    public void onInitialize() {
        FabricLoader fabric = FabricLoader.getInstance();
        String version = fabric.getModContainer(ModInfo.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        ChunkPrunerMod.init("fabric", version, fabric.getConfigDir());
        LOGGER.info(ModInfo.loadedLine("fabric", "1.20-1.20.1"));
    }
}

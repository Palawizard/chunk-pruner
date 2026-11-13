package fr.palawi.chunkpruner.forge;

import fr.palawi.chunkpruner.core.ModInfo;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class ChunkPrunerForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public ChunkPrunerForge() {
        LOGGER.info(ModInfo.loadedLine("forge", "1.20-1.20.1"));
    }
}

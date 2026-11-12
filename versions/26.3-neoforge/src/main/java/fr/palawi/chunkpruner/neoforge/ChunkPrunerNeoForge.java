package fr.palawi.chunkpruner.neoforge;

import fr.palawi.chunkpruner.core.ModInfo;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class ChunkPrunerNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public ChunkPrunerNeoForge() {
        LOGGER.info(ModInfo.loadedLine("neoforge", "26.3"));
    }
}

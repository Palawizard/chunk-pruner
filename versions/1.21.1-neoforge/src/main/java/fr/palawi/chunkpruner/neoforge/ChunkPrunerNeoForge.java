package fr.palawi.chunkpruner.neoforge;

import fr.palawi.chunkpruner.core.ModInfo;
import fr.palawi.chunkpruner.mc.ChunkPrunerMod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class ChunkPrunerNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public ChunkPrunerNeoForge(ModContainer container) {
        ChunkPrunerMod.init(
                "neoforge", container.getModInfo().getVersion().toString(), FMLPaths.CONFIGDIR.get());
        LOGGER.info(ModInfo.loadedLine("neoforge", "1.21-1.21.4"));
    }
}

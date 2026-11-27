package fr.palawi.chunkpruner.forge;

import fr.palawi.chunkpruner.core.ModInfo;
import fr.palawi.chunkpruner.mc.ChunkPrunerMod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModInfo.MOD_ID)
public final class ChunkPrunerForge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);

    public ChunkPrunerForge() {
        String version = ModList.get()
                .getModContainerById(ModInfo.MOD_ID)
                .map(mod -> mod.getModInfo().getVersion().toString())
                .orElse(null);
        ChunkPrunerMod.init("forge", version, FMLPaths.CONFIGDIR.get());
        LOGGER.info(ModInfo.loadedLine("forge", "1.20-1.20.1"));
    }
}

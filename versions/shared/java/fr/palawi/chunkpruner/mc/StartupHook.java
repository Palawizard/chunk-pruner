package fr.palawi.chunkpruner.mc;

import fr.palawi.chunkpruner.core.ModInfo;
import fr.palawi.chunkpruner.core.anvil.Compression;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.FreeSpace;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunListener;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import fr.palawi.chunkpruner.core.engine.StartupRun;
import fr.palawi.chunkpruner.core.engine.StartupRun.Done;
import fr.palawi.chunkpruner.core.engine.StartupRun.NotRun;
import fr.palawi.chunkpruner.core.engine.StartupRun.Outcome;
import fr.palawi.chunkpruner.core.engine.StartupRun.Plan;
import fr.palawi.chunkpruner.core.engine.StartupRun.StartupRefusedException;
import java.nio.file.Path;
import java.time.Clock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E1 (SPEC sections 3 and 4): at server startup, before any level exists, runs what
 * {@link StartupRun#atStartup} finds due and logs what happened. The server already holds the
 * world's session lock, so the engine does not take it. Startup waits for the run. A world with an
 * unfinished run that cannot be finished does not load.
 */
public final class StartupHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);
    private static final long LOG_EVERY_MILLIS = 10_000;

    private StartupHook() {}

    public static void beforeLevels(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Compression compression = Compression.detect(MinecraftServer.class.getClassLoader());
        Outcome outcome;
        try {
            outcome = StartupRun.atStartup(world, ChunkPrunerMod::config, Clock.systemUTC(), (config, plan) -> {
                LOGGER.info("Chunk Pruner: {} before the world loads. The server starts when it is done.", what(plan));
                return new RunSettings(
                        world,
                        config,
                        plan.mode(),
                        RunContext.E1,
                        false,
                        FreeSpace.SYSTEM,
                        Clock.systemUTC(),
                        compression,
                        ChunkPrunerMod.loader(),
                        ChunkPrunerMod.version(),
                        progress());
            });
        } catch (StartupRefusedException e) {
            throw new IllegalStateException("Chunk Pruner: " + e.getMessage(), e);
        }
        if (outcome instanceof NotRun notRun) {
            String where = notRun.cause() instanceof JsonException ? " in " + ChunkPrunerMod.configFile() : "";
            LOGGER.error("Chunk Pruner: nothing ran at this startup: {}{}.", notRun.reason(), where, notRun.cause());
        } else if (outcome instanceof Done done) {
            LOGGER.info(
                    "Chunk Pruner: {} Report: {}",
                    done.result().summary(),
                    done.result().textReport());
            for (String problem : done.problems()) {
                LOGGER.error("Chunk Pruner: {}.", problem);
            }
        }
    }

    private static String what(Plan plan) {
        if (plan.trigger() == StartupRun.Trigger.JOURNAL) {
            return "finishing an unfinished run";
        }
        return (plan.mode() == RunMode.PRUNE ? "pruning" : "a dry run") + " for "
                + plan.trigger().description();
    }

    /** Logs the regions rewritten so far, at most every 10 seconds. */
    private static RunListener progress() {
        long[] state = {System.currentTimeMillis(), 0};
        return (event, path) -> {
            if (RunListener.REGION_DONE.equals(event)) {
                state[1]++;
                long now = System.currentTimeMillis();
                if (now - state[0] >= LOG_EVERY_MILLIS) {
                    state[0] = now;
                    LOGGER.info("Chunk Pruner: {} regions rewritten so far.", state[1]);
                }
            }
        };
    }
}

package fr.palawi.chunkpruner.mc;

import fr.palawi.chunkpruner.core.ModInfo;
import fr.palawi.chunkpruner.core.anvil.Compression;
import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.Engine.RunResult;
import fr.palawi.chunkpruner.core.engine.FreeSpace;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunListener;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import fr.palawi.chunkpruner.core.engine.StartupRun;
import fr.palawi.chunkpruner.core.engine.StartupRun.Plan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E1 (SPEC sections 3 and 4): at server startup, before any level exists, finishes an unfinished
 * run, or runs the pending schedule or auto-prune. The server already holds the world's session
 * lock, so the engine does not take it. Startup waits for the run. A run that fails before it
 * changed anything lets the server start; a run that leaves its journal behind stops the startup,
 * since the world would mix old and new region files.
 */
public final class StartupHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);
    private static final long LOG_EVERY_MILLIS = 10_000;

    private StartupHook() {}

    public static void beforeLevels(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Config config;
        try {
            config = ChunkPrunerMod.config();
        } catch (IOException | JsonException e) {
            LOGGER.error(
                    "Chunk Pruner: {} is not valid ({}). Nothing runs at this startup.",
                    ChunkPrunerMod.configFile(),
                    e.getMessage());
            return;
        }
        Optional<Plan> plan;
        try {
            plan = StartupRun.decide(world, config, Instant.now());
        } catch (IOException e) {
            LOGGER.error("Chunk Pruner: {}. Nothing runs at this startup.", e.getMessage());
            return;
        }
        if (plan.isEmpty()) {
            return;
        }
        run(world, config, plan.get());
    }

    private static void run(Path world, Config config, Plan plan) {
        String what = plan.trigger() == StartupRun.Trigger.JOURNAL
                ? "finishing an unfinished run"
                : (plan.mode() == RunMode.PRUNE ? "pruning" : "a dry run") + " for "
                        + plan.trigger().description();
        LOGGER.info("Chunk Pruner: {} before the world loads. The server starts when it is done.", what);
        RunSettings settings = new RunSettings(
                world,
                config,
                plan.mode(),
                RunContext.E1,
                false,
                FreeSpace.SYSTEM,
                Clock.systemUTC(),
                Compression.detect(MinecraftServer.class.getClassLoader()),
                ChunkPrunerMod.loader(),
                ChunkPrunerMod.version(),
                progress());
        try {
            RunResult result = Engine.run(settings);
            StartupRun.finished(world, plan, Instant.now());
            LOGGER.info("Chunk Pruner: {} Report: {}", result.summary(), result.textReport());
        } catch (Exception e) {
            if (Files.exists(world.resolve("chunkpruner").resolve("journal.json"))) {
                throw new IllegalStateException(
                        "Chunk Pruner could not finish its run, so the world is not started: " + e.getMessage(), e);
            }
            LOGGER.error("Chunk Pruner: the run did not happen, nothing was changed: {}", e.getMessage());
        }
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

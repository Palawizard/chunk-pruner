package fr.palawi.chunkpruner.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.palawi.chunkpruner.core.ModInfo;
import fr.palawi.chunkpruner.core.command.ServerCommands;
import fr.palawi.chunkpruner.core.command.ServerCommands.Reply;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.RunMode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /chunkpruner} (SPEC section 7), permission level 4. The answers come from the core
 * ({@link ServerCommands}); no command touches a region file.
 */
public final class PrunerCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModInfo.MOD_ID);
    private static final int PERMISSION_LEVEL = 4;

    private PrunerCommands() {}

    private interface Action {
        Reply apply(Path world) throws IOException, JsonException;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chunkpruner")
                .requires(source -> Game.allowed(source, PERMISSION_LEVEL))
                .then(Commands.literal("schedule")
                        .then(Commands.literal("dry-run")
                                .executes(c -> reply(
                                        c, world -> ServerCommands.schedule(world, RunMode.DRY_RUN, Instant.now()))))
                        .then(Commands.literal("prune")
                                .executes(c -> reply(
                                        c, world -> ServerCommands.schedule(world, RunMode.PRUNE, Instant.now())))))
                .then(Commands.literal("cancel").executes(c -> reply(c, ServerCommands::cancel)))
                .then(Commands.literal("status")
                        .executes(c -> reply(
                                c, world -> ServerCommands.status(world, ChunkPrunerMod.config(), Instant.now()))))
                .then(Commands.literal("report")
                        .executes(c -> reply(c, world -> ServerCommands.report(world, null)))
                        .then(Commands.argument("run-id", StringArgumentType.word())
                                .executes(c -> reply(
                                        c,
                                        world -> ServerCommands.report(
                                                world, StringArgumentType.getString(c, "run-id")))))));
    }

    private static int reply(CommandContext<CommandSourceStack> context, Action action) {
        CommandSourceStack source = context.getSource();
        Path world = source.getServer()
                .getWorldPath(LevelResource.ROOT)
                .toAbsolutePath()
                .normalize();
        try {
            Reply reply = action.apply(world);
            for (String line : reply.lines()) {
                if (reply.success()) {
                    source.sendSuccess(() -> Component.literal(line), false);
                } else {
                    source.sendFailure(Component.literal(line));
                }
            }
            return reply.success() ? 1 : 0;
        } catch (IOException | JsonException e) {
            LOGGER.error("Chunk Pruner: /chunkpruner failed", e);
            source.sendFailure(Component.literal("Chunk Pruner: " + e.getMessage()));
            return 0;
        }
    }
}

package fr.palawi.chunkpruner.mc;

import net.minecraft.commands.CommandSourceStack;

/** The parts of the game's API that differ between versions. */
final class Game {
    private Game() {}

    static boolean allowed(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }
}

package fr.palawi.chunkpruner.mc;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;

/** The parts of the game's API that differ between versions. */
final class Game {
    private Game() {}

    /** 26.3 checks permissions through permission sets; the levels map to the game's own checks. */
    static boolean allowed(CommandSourceStack source, int level) {
        PermissionCheck check = switch (Math.max(0, Math.min(4, level))) {
            case 0 -> Commands.LEVEL_ALL;
            case 1 -> Commands.LEVEL_MODERATORS;
            case 2 -> Commands.LEVEL_GAMEMASTERS;
            case 3 -> Commands.LEVEL_ADMINS;
            default -> Commands.LEVEL_OWNERS;
        };
        return check.check(source.permissions());
    }
}

package fr.palawi.chunkpruner.core.cli;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.NotEnoughSpaceException;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import fr.palawi.chunkpruner.core.world.WorldException;
import fr.palawi.chunkpruner.core.world.WorldInUseException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The command line tool (E3): a dry run with {@code --dry-run}, a prune without it. A prune backs
 * the changed files up and keeps a journal (SPEC section 4); when the world has the journal of an
 * interrupted run, that run is finished first and no new one starts.
 */
public final class CommandLine {
    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_REFUSED = 3;

    private CommandLine() {}

    public static int run(String[] args, String version, String loader, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            out.print(help(version));
            return EXIT_OK;
        }
        if (args.length == 0) {
            err.print(help(version));
            return EXIT_USAGE;
        }
        String world = null;
        String config = null;
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--dry-run" -> dryRun = true;
                case "--world", "--config" -> {
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        err.println(arg + " needs a path.");
                        err.println("Run with --help to see the options.");
                        return EXIT_USAGE;
                    }
                    if (arg.equals("--world")) {
                        world = args[++i];
                    } else {
                        config = args[++i];
                    }
                }
                default -> {
                    err.println("Unknown option: " + arg);
                    err.println("Run with --help to see the options.");
                    return EXIT_USAGE;
                }
            }
        }
        if (world == null) {
            err.println("--world <path> is required.");
            err.println("Run with --help to see the options.");
            return EXIT_USAGE;
        }
        return run(world, config, dryRun ? RunMode.DRY_RUN : RunMode.PRUNE, version, loader, out, err);
    }

    private static int run(
            String world,
            String configFile,
            RunMode mode,
            String version,
            String loader,
            PrintStream out,
            PrintStream err) {
        try {
            Config config = configFile == null ? Config.defaults() : Config.load(Path.of(configFile));
            RunSettings settings =
                    RunSettings.of(Path.of(world), config, mode, RunContext.E3).withVersions(loader, version);
            Engine.RunResult result = Engine.run(settings);
            if (result.resumed()) {
                out.println("This world had an interrupted prune run. It was finished from its journal:");
                out.println(result.summary());
                out.println("No new run was started. Run the command again to start one.");
            } else {
                out.println(result.summary());
                if (mode == RunMode.DRY_RUN) {
                    out.println("This was a dry run: no file was changed.");
                } else if (result.report().backup() != null) {
                    Path backup = Path.of(world).resolve(result.report().backup());
                    out.println("The changed files were backed up to " + backup);
                    out.println("To undo the run, follow " + backup.resolve("restore.txt"));
                }
            }
            out.println("Report: " + result.textReport());
            out.println("JSON report: " + result.jsonReport());
            return EXIT_OK;
        } catch (WorldInUseException e) {
            err.println(e.getMessage());
            return EXIT_REFUSED;
        } catch (JsonException e) {
            err.println("The config file " + configFile + " is not valid: " + e.getMessage());
            return EXIT_ERROR;
        } catch (NotEnoughSpaceException e) {
            err.println(e.getMessage());
            return EXIT_REFUSED;
        } catch (WorldException e) {
            err.println(e.getMessage());
            return EXIT_ERROR;
        } catch (IOException | RuntimeException e) {
            err.println("The run failed: " + e.getMessage());
            if (hasJournal(world) && !String.valueOf(e.getMessage()).contains("journal.json")) {
                err.println("The world has an unfinished prune run (chunkpruner/journal.json): the next run tries to"
                        + " finish it before anything else.");
            }
            return EXIT_ERROR;
        }
    }

    private static boolean hasJournal(String world) {
        try {
            return Files.exists(Path.of(world).resolve("chunkpruner").resolve("journal.json"));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    static String help(String version) {
        String title = version == null ? "Chunk Pruner" : "Chunk Pruner " + version;
        String nl = System.lineSeparator();
        return title + nl
                + nl
                + "Removes chunks that nobody ever played in from a Minecraft world that is not running." + nl
                + "The game generates them again from the seed when a player goes there." + nl
                + nl
                + "Usage: java -jar <chunk-pruner JAR> --world <path> [--dry-run] [--config <file>]" + nl
                + nl
                + "Options:" + nl
                + "  --world <path>   World folder (the one with level.dat). The world must be closed." + nl
                + "  --dry-run        Only scan the world and write a report to <world>/chunkpruner/reports." + nl
                + "                   No other file is changed. Start with this." + nl
                + "  --config <file>  Config file (config/chunkpruner.json of the server or game folder)." + nl
                + "                   Without it, the default config is used." + nl
                + "  -h, --help       Show this help and exit." + nl
                + nl
                + "Without --dry-run the chunks are removed. Every changed file is first copied to" + nl
                + "<world>/chunkpruner/backups/<run>, and chunkpruner/journal.json lets an interrupted" + nl
                + "run finish: while it exists, any run, a dry run too, finishes that run first and stops." + nl
                + "To undo a run, follow the restore.txt of its backup." + nl
                + nl
                + "Exit codes: 0 done, 1 error, 2 bad options, 3 refused (world in use, or not enough" + nl
                + "free disk space)." + nl;
    }
}

package fr.palawi.chunkpruner.core.cli;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import fr.palawi.chunkpruner.core.world.WorldException;
import fr.palawi.chunkpruner.core.world.WorldInUseException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The command line tool (E3). This version only runs dry runs: pruning stays off until backups
 * and the resume journal exist, so a run without {@code --dry-run} is refused.
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
        if (!dryRun) {
            err.println(
                    "Pruning is not available in this version. Only a dry run works: add --dry-run to get a report.");
            err.println("Pruning comes once every changed file is backed up and an interrupted run can resume.");
            return EXIT_REFUSED;
        }
        return dryRun(world, config, version, loader, out, err);
    }

    private static int dryRun(
            String world, String configFile, String version, String loader, PrintStream out, PrintStream err) {
        try {
            Config config = configFile == null ? Config.defaults() : Config.load(Path.of(configFile));
            RunSettings settings = RunSettings.of(Path.of(world), config, RunMode.DRY_RUN, RunContext.E3)
                    .withVersions(loader, version);
            Engine.RunResult result = Engine.run(settings);
            out.println(result.summary());
            out.println("This was a dry run: no file was changed.");
            out.println("Report: " + result.textReport());
            out.println("JSON report: " + result.jsonReport());
            return EXIT_OK;
        } catch (WorldInUseException e) {
            err.println(e.getMessage());
            return EXIT_REFUSED;
        } catch (JsonException e) {
            err.println("The config file " + configFile + " is not valid: " + e.getMessage());
            return EXIT_ERROR;
        } catch (WorldException e) {
            err.println(e.getMessage());
            return EXIT_ERROR;
        } catch (IOException | InvalidPathException e) {
            err.println("The dry run failed: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    static String help(String version) {
        String title = version == null ? "Chunk Pruner" : "Chunk Pruner " + version;
        String nl = System.lineSeparator();
        return title + nl
                + nl
                + "Removes chunks that nobody ever played in from a Minecraft world that is not running." + nl
                + "This version only reports what would be removed (dry run)." + nl
                + nl
                + "Usage: java -jar <chunk-pruner JAR> --world <path> --dry-run [--config <file>]" + nl
                + nl
                + "Options:" + nl
                + "  --world <path>   World folder (the one with level.dat). The world must be closed." + nl
                + "  --dry-run        Scan the world and write a report to <world>/chunkpruner/reports." + nl
                + "                   No other file is changed." + nl
                + "  --config <file>  Config file (config/chunkpruner.json of the server or game folder)." + nl
                + "                   Without it, the default config is used." + nl
                + "  -h, --help       Show this help and exit." + nl
                + nl
                + "Exit codes: 0 done, 1 error, 2 bad options, 3 refused (world in use, or no --dry-run)." + nl;
    }
}

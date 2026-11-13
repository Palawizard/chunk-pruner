package fr.palawi.chunkpruner.core.cli;

import java.io.PrintStream;

public final class CommandLine {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;

    private CommandLine() {}

    public static int run(String[] args, String version, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            out.print(help(version));
            return EXIT_OK;
        }
        if (args.length == 0) {
            err.print(help(version));
        } else {
            err.println("Unknown option: " + args[0]);
            err.println("Run with --help to see the options.");
        }
        return EXIT_USAGE;
    }

    static String help(String version) {
        String title = version == null ? "Chunk Pruner" : "Chunk Pruner " + version;
        String nl = System.lineSeparator();
        return title + nl
                + nl
                + "Removes chunks that nobody ever played in from a Minecraft world that is not running." + nl
                + nl
                + "Usage: java -jar <chunk-pruner JAR> [options]" + nl
                + nl
                + "Options:" + nl
                + "  -h, --help    Show this help and exit." + nl;
    }
}

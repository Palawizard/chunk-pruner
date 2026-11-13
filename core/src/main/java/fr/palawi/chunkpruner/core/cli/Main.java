package fr.palawi.chunkpruner.core.cli;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        String version = Main.class.getPackage().getImplementationVersion();
        System.exit(CommandLine.run(args, version, System.out, System.err));
    }
}

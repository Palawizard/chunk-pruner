package fr.palawi.chunkpruner.core.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class Main {
    /** Manifest attribute set by each band build (versions/shared/band.gradle). */
    static final String LOADER_ATTRIBUTE = "Loader-Name";

    private Main() {}

    public static void main(String[] args) {
        String version = Main.class.getPackage().getImplementationVersion();
        System.exit(CommandLine.run(args, version, loader(), System.out, System.err));
    }

    private static String loader() {
        CodeSource source = Main.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            return null;
        }
        try {
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(location)) {
                return null;
            }
            try (JarFile jar = new JarFile(location.toFile())) {
                Manifest manifest = jar.getManifest();
                return manifest == null ? null : manifest.getMainAttributes().getValue(LOADER_ATTRIBUTE);
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException | SecurityException e) {
            return null;
        }
    }
}

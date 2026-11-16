package fr.palawi.chunkpruner.core.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The fixture worlds: the committed superflat worlds (always), and the normal-terrain worlds
 * generated in CI when {@code chunkpruner.generatedWorlds} names their folder.
 */
public final class FixtureWorlds {
    public static final List<String> COMMITTED = List.of("1.20.1", "1.21.1", "26.3");
    /** The worlds of the keep plan: a village, entities, a forced chunk and a datapack dimension. */
    public static final List<String> KEEP = List.of("1.20.1-keep", "1.21.1-keep", "26.3-keep");

    public static final String LZ4 = "1.21.1-lz4";

    private FixtureWorlds() {}

    public static Path committed(String name) {
        String root = System.getProperty("chunkpruner.fixtures");
        if (root == null) {
            throw new IllegalStateException(
                    "the chunkpruner.fixtures system property is not set (see core/build.gradle)");
        }
        return Path.of(root, "worlds", name, "world");
    }

    /** Committed worlds first, then generated ones, as "label=path" for parameterized tests. */
    public static List<Fixture> all() throws IOException {
        List<Fixture> result = new ArrayList<>();
        for (String name : COMMITTED) {
            result.add(new Fixture(name, committed(name), true));
        }
        for (String name : KEEP) {
            result.add(new Fixture(name, committed(name), true));
        }
        String generated = System.getProperty("chunkpruner.generatedWorlds");
        if (generated != null && !generated.isBlank()) {
            try (Stream<Path> folders = Files.list(Path.of(generated))) {
                for (Path folder : folders.sorted().toList()) {
                    if (Files.isRegularFile(folder.resolve("world/level.dat"))) {
                        result.add(new Fixture("generated " + folder.getFileName(), folder.resolve("world"), false));
                    }
                }
            }
            if (result.size() == COMMITTED.size() + KEEP.size()) {
                throw new IllegalStateException("no generated world with a level.dat in " + generated);
            }
        }
        return result;
    }

    /** Copies a fixture world to {@code target}, which must not exist yet. */
    public static Path copy(Path world, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(world)) {
            for (Path source : files.sorted().toList()) {
                Path destination = target.resolve(world.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return target;
    }

    public record Fixture(String label, Path world, boolean committed) {
        @Override
        public String toString() {
            return label;
        }
    }
}

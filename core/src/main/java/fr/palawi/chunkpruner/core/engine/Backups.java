package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.engine.Journal.FilePlan;
import fr.palawi.chunkpruner.core.engine.Journal.RegionPlan;
import fr.palawi.chunkpruner.core.world.DimensionId;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Backups of the files a prune run changes (SPEC section 4, step 5), in
 * {@code <world>/chunkpruner/backups/<run-id>/<namespace>/<path>/<storage>/}, and their retention
 * (step 8). Copying the files back over the world restores it as it was before the run.
 */
final class Backups {
    private static final Pattern RUN_FOLDER = Pattern.compile("(\\d{8}-\\d{6})-prune(?:-(\\d+))?");

    private Backups() {}

    static Path root(Path world) {
        return world.resolve("chunkpruner").resolve("backups");
    }

    /**
     * Copies every file of the plan, with the external {@code .mcc} files of the chunks that go,
     * and checks each copy's size. Returns the folder, relative to the world. Any failure stops the
     * run before the first rewrite.
     */
    static String create(Path world, String runId, List<RegionPlan> regions) throws IOException {
        Path folder = root(world).resolve(runId);
        for (RegionPlan region : regions) {
            DimensionId dimension = DimensionId.parse(region.dimension());
            Path target = folder.resolve(dimension.namespace()).resolve(dimension.path());
            Path source = world.resolve(region.folder());
            for (FilePlan file : region.files()) {
                Path storageSource = source.resolve(file.storage().folder());
                Path storageTarget = target.resolve(file.storage().folder());
                AtomicFiles.copyChecked(storageSource.resolve(region.file()), storageTarget.resolve(region.file()));
                for (String external : file.externalFiles()) {
                    if (Files.isRegularFile(storageSource.resolve(external))) {
                        AtomicFiles.copyChecked(storageSource.resolve(external), storageTarget.resolve(external));
                    }
                }
            }
        }
        return world.relativize(folder).toString().replace('\\', '/');
    }

    /** Keeps the {@code keep} newest run backups and deletes the older ones. */
    static List<String> applyRetention(Path world, int keep) throws IOException {
        Path root = root(world);
        List<String> deleted = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return deleted;
        }
        List<String> runs = new ArrayList<>();
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                if (Files.isDirectory(folder) && RUN_FOLDER.matcher(name).matches()) {
                    runs.add(name);
                }
            }
        }
        runs.sort(Comparator.comparing((String name) -> part(name, 1)).thenComparingInt(name -> {
            String n = part(name, 2);
            return n == null ? 1 : Integer.parseInt(n);
        }));
        for (int i = 0; i < runs.size() - keep; i++) {
            deleteTree(root.resolve(runs.get(i)));
            deleted.add(runs.get(i));
        }
        return deleted;
    }

    private static String part(String runFolder, int group) {
        Matcher matcher = RUN_FOLDER.matcher(runFolder);
        matcher.matches();
        return matcher.group(group);
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}

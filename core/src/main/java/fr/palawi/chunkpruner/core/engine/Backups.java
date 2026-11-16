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
 * (step 8). The {@code restore.txt} of a backup says which of its folders goes back into which
 * folder of the world.
 */
final class Backups {
    /** Written last: the instructions to restore, and the mark of a complete backup. */
    static final String RESTORE_FILE = "restore.txt";

    private static final Pattern RUN_FOLDER = Pattern.compile("(\\d{8}-\\d{6})-prune(?:-(\\d+))?");

    private Backups() {}

    static Path root(Path world) {
        return world.resolve("chunkpruner").resolve("backups");
    }

    /**
     * Copies every file of the plan, with the external {@code .mcc} files of the chunks that go,
     * and checks each copy's size. Returns the folder, relative to the world. Any failure stops the
     * run before the first rewrite, and the partial copy is deleted.
     */
    static String create(Path world, String runId, List<RegionPlan> regions) throws IOException {
        Path folder = root(world).resolve(runId);
        try {
            copyAll(world, folder, regions);
            AtomicFiles.writeString(folder.resolve(RESTORE_FILE), restoreText(world, folder, regions));
        } catch (IOException | RuntimeException e) {
            if (Files.isDirectory(folder)) {
                try {
                    deleteTree(folder);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
        return world.relativize(folder).toString().replace('\\', '/');
    }

    /**
     * Which folder of the backup goes back where: the dimensions keep their id in the backup, while
     * the world keeps the overworld at its root and the nether and the End in {@code DIM-1} and
     * {@code DIM1} before data version 4772.
     */
    static String restoreText(Path world, Path folder, List<RegionPlan> regions) {
        java.util.Set<String> lines = new java.util.LinkedHashSet<>();
        for (RegionPlan region : regions) {
            DimensionId dimension = DimensionId.parse(region.dimension());
            for (FilePlan file : region.files()) {
                String from = world.relativize(folder.resolve(dimension.namespace())
                                .resolve(dimension.path())
                                .resolve(file.storage().folder()))
                        .toString()
                        .replace('\\', '/');
                String to = world.relativize(world.resolve(region.folder())
                                .resolve(file.storage().folder())
                                .normalize())
                        .toString()
                        .replace('\\', '/');
                lines.add("Copy the files of " + from + " into " + to);
            }
        }
        return "To undo this run, with the world closed, copy these folders back into the world folder,"
                + " replacing the files there:\n" + String.join("\n", lines) + "\n"
                + "Only the files the run changed are here. If the world was played after the run, what the game"
                + " wrote since in other files stays.\n";
    }

    private static void copyAll(Path world, Path folder, List<RegionPlan> regions) throws IOException {
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
    }

    /**
     * Keeps the backup of the run that just finished ({@code current}, or null when it made none)
     * and the newest others up to {@code keep} backups in all, and deletes the rest. The current
     * backup is never deleted, even with {@code keep} 0 or a clock set back.
     */
    static List<String> applyRetention(Path world, int keep, String current) throws IOException {
        Path root = root(world);
        List<String> deleted = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return deleted;
        }
        List<String> runs = new ArrayList<>();
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                if (Files.isDirectory(folder) && RUN_FOLDER.matcher(name).matches() && !name.equals(current)) {
                    if (Files.isRegularFile(folder.resolve(RESTORE_FILE))) {
                        runs.add(name);
                    } else {
                        // Left by a run killed during its backup, which changed nothing.
                        deleteTree(folder);
                        deleted.add(name);
                    }
                }
            }
        }
        runs.sort(Comparator.comparing((String name) -> part(name, 1)).thenComparingInt(name -> {
            String n = part(name, 2);
            return n == null ? 1 : Integer.parseInt(n);
        }));
        boolean currentKept = current != null && Files.isDirectory(root.resolve(current));
        int keepOthers = Math.max(0, keep - (currentKept ? 1 : 0));
        for (int i = 0; i < runs.size() - keepOthers; i++) {
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

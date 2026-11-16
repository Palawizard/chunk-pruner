package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.anvil.ChunkReader;
import fr.palawi.chunkpruner.core.anvil.RegionFile;
import fr.palawi.chunkpruner.core.anvil.RegionWriter;
import fr.palawi.chunkpruner.core.engine.DimensionScan.RegionPlan;
import fr.palawi.chunkpruner.core.report.DimensionReport;
import fr.palawi.chunkpruner.core.report.ReportFiles;
import fr.palawi.chunkpruner.core.report.ReportText;
import fr.palawi.chunkpruner.core.report.RunReport;
import fr.palawi.chunkpruner.core.report.Storage;
import fr.palawi.chunkpruner.core.report.Warning;
import fr.palawi.chunkpruner.core.rules.KeepRules;
import fr.palawi.chunkpruner.core.world.DimensionId;
import fr.palawi.chunkpruner.core.world.SessionLock;
import fr.palawi.chunkpruner.core.world.WorldData;
import fr.palawi.chunkpruner.core.world.WorldException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One run over a world that is not loaded (SPEC section 4): preflight, scan, decide, then either
 * the report (dry run) or the rewrite and the report (prune).
 *
 * <p>Until backups and the journal exist, a prune run is only reachable from the tests: the
 * command line refuses it and no entry point of the mod calls it.
 */
public final class Engine {
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final long REQUIRED_SPACE_PERCENT = 110;

    private Engine() {}

    public static RunResult run(RunSettings settings) throws WorldException, IOException {
        Path world = settings.world().toAbsolutePath().normalize();
        if (!Files.isDirectory(world)) {
            throw new WorldException("no world folder at " + world);
        }
        Instant started = settings.clock().instant();
        long startNanos = System.nanoTime();
        try (SessionLock lock = settings.takeSessionLock() ? SessionLock.acquire(world) : null) {
            WorldData data = WorldData.read(world);
            Warnings warnings = new Warnings();
            warnings.add(new Warning(
                    Warning.RULES_NOT_CHECKED,
                    null,
                    null,
                    null,
                    null,
                    "this version does not check K3 (forced chunks), K4 (structures) and K5 (entities) yet."));
            ChunkReader reader = new ChunkReader(settings.compression());
            List<DimensionReport> dimensions = new ArrayList<>();
            List<Path> folders = new ArrayList<>();
            List<List<RegionPlan>> plans = new ArrayList<>();
            for (DimensionId id : settings.config().dimensions()) {
                Path folder = data.layout().dimensionFolder(world, id);
                KeepRules rules = new KeepRules(settings.config(), id, data.protectedPoints());
                DimensionScan scan = new DimensionScan(id, world, folder, rules, reader, warnings);
                scan.run();
                dimensions.add(scan.report());
                folders.add(folder);
                plans.add(scan.plans());
            }
            checkSpace(settings, world, folders, plans, warnings);
            if (settings.mode() == RunMode.PRUNE) {
                for (int i = 0; i < folders.size(); i++) {
                    rewrite(folders.get(i), plans.get(i));
                }
            }
            RunReport report = new RunReport(
                    runId(world, started, settings.mode()),
                    settings.mode().id(),
                    settings.context().description(),
                    started,
                    (System.nanoTime() - startNanos) / 1_000_000,
                    data.versionName(),
                    data.dataVersion(),
                    settings.loader(),
                    settings.modVersion(),
                    settings.compression().lz4Available(),
                    settings.config().effective(),
                    dimensions,
                    warnings.all());
            Path json = ReportFiles.write(world, report);
            return new RunResult(
                    report, json, json.resolveSibling(report.runId() + ".txt"), ReportText.summary(report));
        }
    }

    /** At least the size of the files to modify, plus 10 percent (SPEC section 4). */
    private static void checkSpace(
            RunSettings settings, Path world, List<Path> folders, List<List<RegionPlan>> plans, Warnings warnings)
            throws IOException, NotEnoughSpaceException {
        long toModify = 0;
        for (int i = 0; i < folders.size(); i++) {
            for (RegionPlan plan : plans.get(i)) {
                for (Storage storage : plan.removals().keySet()) {
                    toModify +=
                            Files.size(folders.get(i).resolve(storage.folder()).resolve(plan.fileName()));
                }
            }
        }
        long required = (Math.multiplyExact(toModify, REQUIRED_SPACE_PERCENT) + 99) / 100;
        long usable = settings.freeSpace().usableBytes(world);
        if (usable >= required) {
            return;
        }
        String message = "the files to change take " + toModify + " bytes, so " + required + " bytes must be free, and "
                + usable + " are.";
        if (settings.mode() == RunMode.PRUNE) {
            throw new NotEnoughSpaceException(
                    "Not enough free disk space to prune: " + message + " Nothing was changed.");
        }
        warnings.add(new Warning(
                Warning.LOW_DISK_SPACE,
                null,
                null,
                null,
                null,
                "a prune run would stop before any change: " + message));
    }

    private static void rewrite(Path folder, List<RegionPlan> plans) throws IOException {
        for (RegionPlan plan : plans) {
            for (Map.Entry<Storage, Set<Integer>> removal : plan.removals().entrySet()) {
                Path file = folder.resolve(removal.getKey().folder()).resolve(plan.fileName());
                RegionWriter.remove(RegionFile.read(file), removal.getValue());
            }
        }
    }

    private static String runId(Path world, Instant started, RunMode mode) {
        String base = RUN_ID.format(started) + "-" + mode.id();
        Path folder = ReportFiles.folder(world);
        String id = base;
        for (int n = 2; Files.exists(folder.resolve(id + ".json")); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /** The report and where it was written. {@code summary} is its first sentence. */
    public record RunResult(RunReport report, Path jsonReport, Path textReport, String summary) {}
}

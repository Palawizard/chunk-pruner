package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.anvil.ChunkReader;
import fr.palawi.chunkpruner.core.anvil.RegionFile;
import fr.palawi.chunkpruner.core.anvil.RegionFile.ChunkEntry;
import fr.palawi.chunkpruner.core.config.Json;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.engine.DimensionScan.RegionPlan;
import fr.palawi.chunkpruner.core.engine.Journal.FilePlan;
import fr.palawi.chunkpruner.core.report.DimensionReport;
import fr.palawi.chunkpruner.core.report.ReportFiles;
import fr.palawi.chunkpruner.core.report.ReportJson;
import fr.palawi.chunkpruner.core.report.ReportText;
import fr.palawi.chunkpruner.core.report.RunReport;
import fr.palawi.chunkpruner.core.report.Storage;
import fr.palawi.chunkpruner.core.report.Warning;
import fr.palawi.chunkpruner.core.rules.KeepRules;
import fr.palawi.chunkpruner.core.rules.PlayerEntities;
import fr.palawi.chunkpruner.core.world.DimensionId;
import fr.palawi.chunkpruner.core.world.ForcedChunks;
import fr.palawi.chunkpruner.core.world.SessionLock;
import fr.palawi.chunkpruner.core.world.WorldData;
import fr.palawi.chunkpruner.core.world.WorldException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One run over a world that is not loaded (SPEC section 4): preflight, scan, decide, then either
 * the report (dry run) or the backup, the journal, the rewrite, the report and the backup
 * retention (prune). When the world has a journal, the interrupted run is finished instead, and
 * no new run starts.
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
        boolean rewrites = Journal.exists(world)
                || settings.mode() == RunMode.PRUNE && Files.isRegularFile(world.resolve("level.dat"));
        try (SessionLock lock = settings.takeSessionLock() ? SessionLock.acquire(world, rewrites) : null) {
            if (Journal.exists(world)) {
                return resume(world, settings);
            }
            Journal.deleteStalePlan(world);
            WorldData data = WorldData.read(world);
            Warnings warnings = new Warnings();
            ChunkReader reader = new ChunkReader(settings.compression());
            List<DimensionReport> dimensions = new ArrayList<>();
            List<Journal.RegionPlan> regions = new ArrayList<>();
            for (DimensionId id : settings.config().dimensions()) {
                Path folder = data.layout().dimensionFolder(world, id);
                KeepRules rules = new KeepRules(settings.config(), id, data.protectedPoints());
                ForcedChunks forced =
                        rules.checksForcedChunks() ? ForcedChunks.read(folder, data.layout()) : ForcedChunks.none();
                PlayerEntities entities = new PlayerEntities(data.players());
                DimensionScan scan = new DimensionScan(id, world, folder, rules, forced, entities, reader, warnings);
                scan.run();
                dimensions.add(scan.report());
                String relative = world.relativize(folder).toString().replace('\\', '/');
                for (RegionPlan plan : scan.plans()) {
                    regions.add(plan(id, relative.isEmpty() ? "." : relative, folder, plan));
                }
            }
            checkSpace(settings, world, regions, warnings);
            String runId = runId(world, started, settings.mode());
            if (settings.mode() == RunMode.DRY_RUN || regions.isEmpty()) {
                RunReport report = report(runId, settings, started, startNanos, data, null, dimensions, warnings);
                RunResult result = written(world, report);
                if (settings.mode() == RunMode.PRUNE) {
                    Backups.applyRetention(world, settings.config().backupRetention(), null);
                }
                return result;
            }
            String backup = null;
            if (settings.config().backup()) {
                backup = Backups.create(world, runId, regions);
                settings.listener().event(RunListener.BACKUP_WRITTEN, world.resolve(backup));
            }
            RunReport planned = report(runId, settings, started, startNanos, data, backup, dimensions, warnings);
            Journal.Plan plan = new Journal.Plan(runId, backup, regions, asMap(planned), ReportText.render(planned));
            Journal.start(world, plan);
            settings.listener().event(RunListener.JOURNAL_WRITTEN, Journal.file(world));
            applyAll(world, plan, new HashSet<>(), settings.listener());
            RunReport report = report(runId, settings, started, startNanos, data, backup, dimensions, warnings);
            RunResult result = written(world, report);
            Journal.finish(world);
            Backups.applyRetention(world, settings.config().backupRetention(), runId);
            return result;
        }
    }

    /** SPEC section 4: the pending regions are finished with the recorded decisions, then reported. */
    private static RunResult resume(Path world, RunSettings settings) throws IOException {
        Journal.Loaded loaded;
        try {
            loaded = Journal.read(world);
        } catch (IOException e) {
            throw new UnfinishedRunException(
                    e.getMessage() + " The next run tries again. To undo the run, follow the"
                            + " restore.txt of its folder in chunkpruner/backups, if it has one. To give up the run, delete"
                            + " chunkpruner/journal.json and chunkpruner/journal-plan.json.",
                    e);
        }
        Journal.Plan plan = loaded.plan();
        Set<Integer> done = new HashSet<>(loaded.done());
        applyAll(world, plan, done, settings.listener());
        String finishedAt = settings.clock().instant().toString();
        Map<String, Object> report = new LinkedHashMap<>(plan.report());
        report.put("resumedAt", finishedAt);
        List<Object> warnings = new ArrayList<>((List<?>) report.get("warnings"));
        Map<String, Object> resumed = new LinkedHashMap<>();
        resumed.put("type", Warning.RESUMED);
        resumed.put("dimension", null);
        resumed.put("file", null);
        resumed.put("chunkX", null);
        resumed.put("chunkZ", null);
        resumed.put(
                "message",
                "this run was interrupted after " + loaded.done().size() + " of "
                        + plan.regions().size() + " regions, and finished from its journal on " + finishedAt + ".");
        warnings.add(resumed);
        report.put("warnings", warnings);
        String text = plan.reportText() + "\nThis run was interrupted after "
                + loaded.done().size() + " of " + plan.regions().size() + " regions, and finished from its journal on "
                + finishedAt + ".\n";
        Path json = ReportFiles.folder(world).resolve(plan.runId() + ".json");
        if (loaded.done().size() < plan.regions().size()
                || !Files.exists(json)
                || !Files.exists(json.resolveSibling(plan.runId() + ".txt"))) {
            json = ReportFiles.write(world, plan.runId(), Json.write(report), text);
        }
        Journal.finish(world);
        Backups.applyRetention(world, settings.config().backupRetention(), plan.runId());
        String summary = text.lines().findFirst().orElse("");
        return new RunResult(null, json, json.resolveSibling(plan.runId() + ".txt"), summary, true);
    }

    private static void applyAll(Path world, Journal.Plan plan, Set<Integer> done, RunListener listener)
            throws IOException {
        for (int i = 0; i < plan.regions().size(); i++) {
            if (done.contains(i)) {
                continue;
            }
            Journal.RegionPlan region = plan.regions().get(i);
            try {
                Rewrite.apply(world, region, listener);
            } catch (IOException e) {
                throw new UnfinishedRunException(e.getMessage() + " " + way(plan), e);
            }
            done.add(i);
            Journal.markDone(world, plan, done);
            listener.event(
                    RunListener.REGION_DONE, world.resolve(region.folder()).resolve(region.file()));
        }
    }

    /** How to leave an unfinished run that cannot go on (SPEC section 4). */
    private static String way(Journal.Plan plan) {
        String restore = plan.backup() == null
                ? "This run made no backup (backup is false in the config)."
                : "To undo the run, follow " + plan.backup() + "/" + Backups.RESTORE_FILE + ".";
        return "The next run tries again to finish this one. " + restore + " To give up the run, delete"
                + " chunkpruner/journal.json and chunkpruner/journal-plan.json; the regions already rewritten stay"
                + " rewritten.";
    }

    private static Journal.RegionPlan plan(DimensionId id, String relative, Path folder, RegionPlan plan)
            throws IOException {
        List<FilePlan> files = new ArrayList<>();
        for (Map.Entry<Storage, Set<Integer>> removal : plan.removals().entrySet()) {
            Path file = folder.resolve(removal.getKey().folder()).resolve(plan.fileName());
            RegionFile region = RegionFile.read(file);
            List<String> external = new ArrayList<>();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                for (ChunkEntry entry : region.entries()) {
                    if (removal.getValue().contains(entry.index()) && ChunkReader.isExternal(channel, entry)) {
                        external.add(ChunkReader.externalFile(region, entry)
                                .getFileName()
                                .toString());
                    }
                }
            }
            files.add(new FilePlan(
                    removal.getKey(),
                    Set.copyOf(removal.getValue()),
                    Files.size(file),
                    Files.getLastModifiedTime(file).toMillis(),
                    RegionWriter.sizeAfter(region, removal.getValue()),
                    List.copyOf(external)));
        }
        return new Journal.RegionPlan(id.toString(), relative, plan.fileName(), List.copyOf(files));
    }

    /**
     * SPEC section 4: the backup copies the files to change, and the rewrite writes the largest of
     * them again next to the old one before it moves it over; plus 10 percent.
     */
    private static void checkSpace(
            RunSettings settings, Path world, List<Journal.RegionPlan> regions, Warnings warnings)
            throws IOException, NotEnoughSpaceException {
        long toModify = 0;
        long largest = 0;
        for (Journal.RegionPlan region : regions) {
            for (FilePlan file : region.files()) {
                toModify += file.sizeBefore();
                largest = Math.max(largest, file.sizeBefore());
                Path folder =
                        world.resolve(region.folder()).resolve(file.storage().folder());
                for (String external : file.externalFiles()) {
                    Path mcc = folder.resolve(external);
                    if (Files.isRegularFile(mcc)) {
                        toModify += Files.size(mcc);
                    }
                }
            }
        }
        boolean backup = settings.config().backup();
        long needed = (backup ? toModify : 0) + largest;
        long required = (Math.multiplyExact(needed, REQUIRED_SPACE_PERCENT) + 99) / 100;
        long usable = settings.freeSpace().usableBytes(world);
        if (usable >= required) {
            return;
        }
        String message = "the files to change take " + toModify + " bytes and the largest " + largest + " bytes, so "
                + required + " bytes must be free" + (backup ? " (backup included)" : "") + ", and " + usable
                + " are.";
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

    private static RunReport report(
            String runId,
            RunSettings settings,
            Instant started,
            long startNanos,
            WorldData data,
            String backup,
            List<DimensionReport> dimensions,
            Warnings warnings) {
        return new RunReport(
                runId,
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
                backup,
                dimensions,
                warnings.all());
    }

    private static RunResult written(Path world, RunReport report) throws IOException {
        Path json = ReportFiles.write(world, report);
        return new RunResult(
                report, json, json.resolveSibling(report.runId() + ".txt"), ReportText.summary(report), false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(RunReport report) throws IOException {
        try {
            return (Map<String, Object>) Json.parse(ReportJson.render(report));
        } catch (JsonException e) {
            throw new IOException("cannot keep the report in the journal: " + e.getMessage(), e);
        }
    }

    private static String runId(Path world, Instant started, RunMode mode) {
        String base = RUN_ID.format(started) + "-" + mode.id();
        Path reports = ReportFiles.folder(world);
        Path backups = Backups.root(world);
        String id = base;
        for (int n = 2; Files.exists(reports.resolve(id + ".json")) || Files.exists(backups.resolve(id)); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /**
     * The report and where it was written. {@code summary} is its first sentence. {@code report}
     * is null when {@code resumed} is true: the run was an interrupted one, finished from its
     * journal, and its report was written as the journal kept it.
     */
    public record RunResult(RunReport report, Path jsonReport, Path textReport, String summary, boolean resumed) {}
}

package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.anvil.ChunkReader;
import fr.palawi.chunkpruner.core.anvil.ChunkReader.ChunkFields;
import fr.palawi.chunkpruner.core.anvil.RegionFile;
import fr.palawi.chunkpruner.core.anvil.RegionFile.ChunkEntry;
import fr.palawi.chunkpruner.core.anvil.RegionWriter;
import fr.palawi.chunkpruner.core.report.DimensionReport;
import fr.palawi.chunkpruner.core.report.Storage;
import fr.palawi.chunkpruner.core.report.StorageReport;
import fr.palawi.chunkpruner.core.report.Warning;
import fr.palawi.chunkpruner.core.rules.KeepRule;
import fr.palawi.chunkpruner.core.rules.KeepRules;
import fr.palawi.chunkpruner.core.rules.PlayedChunks;
import fr.palawi.chunkpruner.core.world.DimensionId;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans and decides one dimension in two passes. The first reads every chunk of {@code region/}
 * and records the chunks kept by K1 (K8 needs all of them). The second applies the rules and
 * plans which entries leave each storage. Nothing is written here.
 */
final class DimensionScan {
    private final DimensionId dimension;
    private final Path folder;
    private final KeepRules rules;
    private final ChunkReader reader;
    private final Warnings warnings;
    private final DimensionReport report;
    private final PlayedChunks played = new PlayedChunks();
    private final Map<String, BitSet> unreadable = new HashMap<>();
    private final Map<String, BitSet> playedBits = new HashMap<>();
    private final Map<String, BitSet> externalBits = new HashMap<>();
    private final Set<String> unreadableFiles = new HashSet<>();
    private final List<RegionPlan> plans = new ArrayList<>();

    DimensionScan(
            DimensionId dimension, Path world, Path folder, KeepRules rules, ChunkReader reader, Warnings warnings) {
        this.dimension = dimension;
        this.folder = folder;
        this.rules = rules;
        this.reader = reader;
        this.warnings = warnings;
        String relative = world.relativize(folder).toString().replace('\\', '/');
        this.report = new DimensionReport(dimension.toString(), relative.isEmpty() ? "." : relative);
    }

    DimensionReport report() {
        return report;
    }

    List<RegionPlan> plans() {
        return plans;
    }

    void run() throws IOException {
        Path regionFolder = folder.resolve(Storage.REGION.folder());
        if (!Files.isDirectory(regionFolder)) {
            report.status(DimensionReport.Status.NOT_GENERATED);
            return;
        }
        List<Path> regionFiles = regionFiles(regionFolder);
        if (regionFiles.isEmpty()) {
            if (hasOtherFiles(regionFolder)) {
                report.status(DimensionReport.Status.SKIPPED);
                warnings.add(new Warning(
                        Warning.SKIPPED_DIMENSION,
                        dimension.toString(),
                        null,
                        null,
                        null,
                        "its region folder has files but no .mca file: another storage format, left untouched."));
            } else {
                report.status(DimensionReport.Status.NOT_GENERATED);
            }
            return;
        }
        for (Path file : regionFiles) {
            scan(file);
        }
        Map<Storage, long[]> totals = new EnumMap<>(Storage.class);
        for (Storage storage : Storage.values()) {
            totals.put(storage, storageBefore(folder.resolve(storage.folder())));
        }
        for (Path file : regionFiles) {
            decide(file, totals);
        }
        totals.forEach(
                (storage, t) -> report.addStorage(storage, new StorageReport(t[0], t[1], t[1] - t[2], t[3], t[4])));
    }

    private void scan(Path file) {
        String name = file.getFileName().toString();
        RegionFile region;
        try {
            region = RegionFile.read(file);
        } catch (IOException e) {
            unreadableRegion(name, "cannot read its header: " + e.getMessage());
            return;
        }
        if (region.truncatedHeader()) {
            unreadableRegion(name, "the file is shorter than its 8 KiB header; it is left untouched.");
            return;
        }
        BitSet bad = new BitSet(RegionFile.CHUNKS);
        BitSet k1 = new BitSet(RegionFile.CHUNKS);
        BitSet external = new BitSet(RegionFile.CHUNKS);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            for (ChunkEntry entry : region.entries()) {
                ChunkFields fields = reader.read(channel, region, entry);
                int x = region.chunkX(entry);
                int z = region.chunkZ(entry);
                if (fields.external()) {
                    external.set(entry.index());
                    warnings.add(new Warning(
                            Warning.EXTERNAL_CHUNK,
                            dimension.toString(),
                            name,
                            x,
                            z,
                            "its data is in an external .mcc file."));
                }
                if (!fields.readable()) {
                    bad.set(entry.index());
                    warnings.add(new Warning(
                            Warning.UNREADABLE_CHUNK,
                            dimension.toString(),
                            name,
                            x,
                            z,
                            "kept (K7): " + fields.problem() + "."));
                } else if (rules.played(fields.inhabitedTime())) {
                    k1.set(entry.index());
                    played.add(x, z);
                }
            }
        } catch (IOException e) {
            unreadableRegion(name, "cannot read the file: " + e.getMessage());
            return;
        }
        unreadable.put(name, bad);
        playedBits.put(name, k1);
        externalBits.put(name, external);
    }

    private void unreadableRegion(String name, String message) {
        unreadableFiles.add(name);
        warnings.add(new Warning(Warning.UNREADABLE_REGION, dimension.toString(), name, null, null, message));
    }

    /** totals per storage: files, bytes before, bytes removed, files changed, files deleted. */
    private void decide(Path file, Map<Storage, long[]> totals) throws IOException {
        String name = file.getFileName().toString();
        if (unreadableFiles.contains(name)) {
            return;
        }
        RegionFile region = RegionFile.read(file);
        Map<Storage, RegionFile> counterparts = new EnumMap<>(Storage.class);
        boolean rewritable = region.rewritable();
        for (Storage storage : List.of(Storage.ENTITIES, Storage.POI)) {
            Path other = folder.resolve(storage.folder()).resolve(name);
            if (Files.isRegularFile(other)) {
                try {
                    RegionFile counterpart = RegionFile.read(other);
                    counterparts.put(storage, counterpart);
                    rewritable &= counterpart.rewritable();
                } catch (IOException e) {
                    rewritable = false;
                }
            }
        }
        Set<Integer> removed = new HashSet<>();
        long forced = 0;
        BitSet bad = unreadable.get(name);
        BitSet k1 = playedBits.get(name);
        for (ChunkEntry entry : region.entries()) {
            int index = entry.index();
            KeepRule rule =
                    rules.decide(region.chunkX(entry), region.chunkZ(entry), !bad.get(index), k1.get(index), played);
            if (rule == null && !rewritable) {
                rule = KeepRule.K7;
                forced++;
            }
            if (rule == null) {
                removed.add(index);
                report.countPruned();
            } else {
                report.countKept(rule);
            }
        }
        if (forced > 0) {
            warnings.add(new Warning(
                    Warning.UNREADABLE_REGION,
                    dimension.toString(),
                    name,
                    null,
                    null,
                    "this file or its entities or poi counterpart cannot be rebuilt byte for byte (truncated header"
                            + " or a chunk outside the file): " + forced + " chunks that could go are kept (K7)."));
        }
        if (removed.isEmpty()) {
            return;
        }
        Map<Storage, Set<Integer>> removals = new EnumMap<>(Storage.class);
        removals.put(Storage.REGION, removed);
        account(totals.get(Storage.REGION), region, removed, externalBits.get(name));
        for (Map.Entry<Storage, RegionFile> entry : counterparts.entrySet()) {
            RegionFile counterpart = entry.getValue();
            Set<Integer> there = new HashSet<>();
            for (ChunkEntry e : counterpart.entries()) {
                if (removed.contains(e.index())) {
                    there.add(e.index());
                }
            }
            if (!there.isEmpty()) {
                removals.put(entry.getKey(), there);
                account(totals.get(entry.getKey()), counterpart, there, externalOf(counterpart, there));
            }
        }
        plans.add(new RegionPlan(name, removals));
    }

    private static void account(long[] totals, RegionFile region, Set<Integer> removed, BitSet external)
            throws IOException {
        long after = RegionWriter.sizeAfter(region, removed);
        totals[2] += region.size() - after;
        if (after == 0) {
            totals[4]++;
        } else {
            totals[3]++;
        }
        for (ChunkEntry entry : region.entries()) {
            if (removed.contains(entry.index()) && external.get(entry.index())) {
                Path mcc = ChunkReader.externalFile(region, entry);
                if (Files.isRegularFile(mcc)) {
                    totals[2] += Files.size(mcc);
                }
            }
        }
    }

    private static BitSet externalOf(RegionFile region, Set<Integer> indices) throws IOException {
        BitSet external = new BitSet(RegionFile.CHUNKS);
        try (FileChannel channel = FileChannel.open(region.path(), StandardOpenOption.READ)) {
            for (ChunkEntry entry : region.entries()) {
                if (indices.contains(entry.index()) && ChunkReader.isExternal(channel, entry)) {
                    external.set(entry.index());
                }
            }
        }
        return external;
    }

    private static long[] storageBefore(Path storageFolder) throws IOException {
        long[] totals = new long[5];
        if (!Files.isDirectory(storageFolder)) {
            return totals;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(storageFolder)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean region = RegionFile.coordinates(name) != null;
                if (Files.isRegularFile(file) && (region || name.endsWith(".mcc"))) {
                    totals[1] += Files.size(file);
                    if (region) {
                        totals[0]++;
                    }
                }
            }
        }
        return totals;
    }

    private static List<Path> regionFiles(Path regionFolder) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)
                        && RegionFile.coordinates(file.getFileName().toString()) != null) {
                    files.add(file);
                }
            }
        }
        files.sort(null);
        return files;
    }

    private static boolean hasOtherFiles(Path regionFolder) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Entries that leave each storage file of one region. */
    record RegionPlan(String fileName, Map<Storage, Set<Integer>> removals) {}
}

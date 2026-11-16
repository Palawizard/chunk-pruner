package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.anvil.ChunkReader;
import fr.palawi.chunkpruner.core.anvil.ChunkReader.ChunkFields;
import fr.palawi.chunkpruner.core.anvil.ChunkReader.EntityFields;
import fr.palawi.chunkpruner.core.anvil.RegionFile;
import fr.palawi.chunkpruner.core.anvil.RegionFile.ChunkEntry;
import fr.palawi.chunkpruner.core.report.DimensionReport;
import fr.palawi.chunkpruner.core.report.Storage;
import fr.palawi.chunkpruner.core.report.StorageReport;
import fr.palawi.chunkpruner.core.report.Warning;
import fr.palawi.chunkpruner.core.rules.KeepRule;
import fr.palawi.chunkpruner.core.rules.KeepRules;
import fr.palawi.chunkpruner.core.rules.PlayedChunks;
import fr.palawi.chunkpruner.core.rules.PlayerEntities;
import fr.palawi.chunkpruner.core.rules.StructureGraph;
import fr.palawi.chunkpruner.core.world.DimensionId;
import fr.palawi.chunkpruner.core.world.ForcedChunks;
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
 * (K1, K7 and the structure graph) and, when K5 is on, of {@code entities/}. Between the passes
 * the structure graph is resolved against the chunks the other rules keep (K4, and K8 needs every
 * K1 chunk). The second pass applies the rules and plans which entries leave each storage.
 * Nothing is written here.
 */
final class DimensionScan {
    private final DimensionId dimension;
    private final Path folder;
    private final KeepRules rules;
    private final ChunkReader reader;
    private final Warnings warnings;
    private final DimensionReport report;
    private final ForcedChunks forced;
    private final PlayerEntities playerEntities;
    private final PlayedChunks played = new PlayedChunks();
    private final StructureGraph structures = new StructureGraph();
    private final Map<Long, RegionScan> regions = new HashMap<>();
    private final Set<String> unreadableFiles = new HashSet<>();
    private final Set<Long> unreadableRegions = new HashSet<>();
    private final List<RegionPlan> plans = new ArrayList<>();

    /** What the first pass found in one region: one bit per chunk index. */
    private static final class RegionScan {
        final BitSet present = new BitSet(RegionFile.CHUNKS);
        final BitSet unreadable = new BitSet(RegionFile.CHUNKS);
        final BitSet regionUnreadable = new BitSet(RegionFile.CHUNKS);
        final BitSet played = new BitSet(RegionFile.CHUNKS);
        final BitSet external = new BitSet(RegionFile.CHUNKS);
        final BitSet playerEntity = new BitSet(RegionFile.CHUNKS);
        boolean rewritable;
    }

    DimensionScan(
            DimensionId dimension,
            Path world,
            Path folder,
            KeepRules rules,
            ForcedChunks forced,
            PlayerEntities playerEntities,
            ChunkReader reader,
            Warnings warnings) {
        this.dimension = dimension;
        this.folder = folder;
        this.rules = rules;
        this.forced = forced;
        this.playerEntities = playerEntities;
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
        if (forced.unknownReason() != null) {
            warnings.add(new Warning(
                    Warning.FORCED_CHUNKS_UNKNOWN,
                    dimension.toString(),
                    null,
                    null,
                    null,
                    forced.unknownReason() + "; every chunk of the dimension is kept as force-loaded (K3)."));
        }
        for (Path file : regionFiles) {
            scan(file);
        }
        if (rules.checksStructures()) {
            structures.resolve(this::keptWithoutStructures, this::referencesUnknown);
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
        RegionScan scan = new RegionScan();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            for (ChunkEntry entry : region.entries()) {
                ChunkFields fields = reader.read(channel, region, entry);
                int index = entry.index();
                int x = region.chunkX(entry);
                int z = region.chunkZ(entry);
                scan.present.set(index);
                if (fields.external()) {
                    scan.external.set(index);
                    warnings.add(new Warning(
                            Warning.EXTERNAL_CHUNK,
                            dimension.toString(),
                            name,
                            x,
                            z,
                            "its data is in an external .mcc file."));
                }
                if (!fields.readable()) {
                    scan.unreadable.set(index);
                    scan.regionUnreadable.set(index);
                    warnings.add(new Warning(
                            Warning.UNREADABLE_CHUNK,
                            dimension.toString(),
                            name,
                            x,
                            z,
                            "kept (K7): " + fields.problem() + "."));
                    continue;
                }
                structures.add(x, z, fields.starts(), fields.references());
                if (rules.played(fields.inhabitedTime())) {
                    scan.played.set(index);
                    played.add(x, z);
                }
            }
        } catch (IOException e) {
            unreadableRegion(name, "cannot read the file: " + e.getMessage());
            return;
        }
        scan.rewritable = region.rewritable() && counterpartsRewritable(name);
        if (rules.checksPlayerEntities()) {
            scanEntities(name, scan);
        }
        regions.put(regionKey(region.regionX(), region.regionZ()), scan);
    }

    private boolean counterpartsRewritable(String name) {
        for (Storage storage : List.of(Storage.ENTITIES, Storage.POI)) {
            Path other = folder.resolve(storage.folder()).resolve(name);
            if (Files.isRegularFile(other)) {
                try {
                    if (!RegionFile.read(other).rewritable()) {
                        return false;
                    }
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /** K5 on the {@code entities/} counterpart; an entry that cannot be read is kept (K7). */
    private void scanEntities(String name, RegionScan scan) {
        Path file = folder.resolve(Storage.ENTITIES.folder()).resolve(name);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            RegionFile region = RegionFile.read(file);
            if (region.truncatedHeader()) {
                return;
            }
            for (ChunkEntry entry : region.entries()) {
                int index = entry.index();
                if (!scan.present.get(index) || scan.unreadable.get(index)) {
                    continue;
                }
                int x = region.chunkX(entry);
                int z = region.chunkZ(entry);
                EntityFields fields = reader.readEntities(channel, region, entry);
                if (fields.entities() == null) {
                    scan.unreadable.set(index);
                    warnings.add(new Warning(
                            Warning.UNREADABLE_CHUNK,
                            dimension.toString(),
                            "entities/" + name,
                            x,
                            z,
                            "kept (K7): its entities cannot be checked for K5: " + fields.problem() + "."));
                } else if (playerEntities.keeper(fields.entities(), structures.structuresAt(x, z)) != null) {
                    scan.playerEntity.set(index);
                }
            }
        } catch (IOException e) {
            // The file was checked by counterpartsRewritable: nothing in this region is pruned.
            scan.rewritable = false;
        }
    }

    private KeepRules.Facts facts(RegionScan scan, int index, int x, int z) {
        return new KeepRules.Facts(
                x,
                z,
                !scan.unreadable.get(index),
                scan.played.get(index),
                forced.isForced(x, z),
                structures.isReferencedStart(x, z),
                scan.playerEntity.get(index));
    }

    private boolean keptWithoutStructures(long chunk) {
        int x = (int) chunk;
        int z = (int) (chunk >> 32);
        RegionScan scan = regions.get(regionKey(x >> 5, z >> 5));
        int index = (x & 31) + (z & 31) * 32;
        if (scan == null || !scan.present.get(index)) {
            return false;
        }
        return !scan.rewritable || rules.keptWithoutStructures(facts(scan, index, x, z), played);
    }

    /** The chunk's {@code region/} entry, or its whole file, could not be read. */
    private boolean referencesUnknown(long chunk) {
        int x = (int) chunk;
        int z = (int) (chunk >> 32);
        if (unreadableRegions.contains(regionKey(x >> 5, z >> 5))) {
            return true;
        }
        RegionScan scan = regions.get(regionKey(x >> 5, z >> 5));
        return scan != null && scan.regionUnreadable.get((x & 31) + (z & 31) * 32);
    }

    private static long regionKey(int regionX, int regionZ) {
        return (long) regionX << 32 | (regionZ & 0xFFFFFFFFL);
    }

    private void unreadableRegion(String name, String message) {
        unreadableFiles.add(name);
        int[] coordinates = RegionFile.coordinates(name);
        if (coordinates != null) {
            unreadableRegions.add(regionKey(coordinates[0], coordinates[1]));
        }
        warnings.add(new Warning(Warning.UNREADABLE_REGION, dimension.toString(), name, null, null, message));
    }

    /** totals per storage: files, bytes before, bytes removed, files changed, files deleted. */
    private void decide(Path file, Map<Storage, long[]> totals) throws IOException {
        String name = file.getFileName().toString();
        if (unreadableFiles.contains(name)) {
            return;
        }
        RegionFile region = RegionFile.read(file);
        RegionScan scan = regions.get(regionKey(region.regionX(), region.regionZ()));
        Map<Storage, RegionFile> counterparts = new EnumMap<>(Storage.class);
        if (scan.rewritable) {
            for (Storage storage : List.of(Storage.ENTITIES, Storage.POI)) {
                Path other = folder.resolve(storage.folder()).resolve(name);
                if (Files.isRegularFile(other)) {
                    counterparts.put(storage, RegionFile.read(other));
                }
            }
        }
        Set<Integer> removed = new HashSet<>();
        long notRewritable = 0;
        for (ChunkEntry entry : region.entries()) {
            int index = entry.index();
            KeepRule rule = rules.decide(facts(scan, index, region.chunkX(entry), region.chunkZ(entry)), played);
            if (rule == null && !scan.rewritable) {
                rule = KeepRule.K7;
                notRewritable++;
            }
            if (rule == null) {
                removed.add(index);
                report.countPruned();
            } else {
                report.countKept(rule);
            }
        }
        if (notRewritable > 0) {
            warnings.add(new Warning(
                    Warning.UNREADABLE_REGION,
                    dimension.toString(),
                    name,
                    null,
                    null,
                    "this file or its entities or poi counterpart cannot be rebuilt byte for byte (truncated header"
                            + " or a chunk outside the file): " + notRewritable
                            + " chunks that could go are kept (K7)."));
        }
        if (removed.isEmpty()) {
            return;
        }
        Map<Storage, Set<Integer>> removals = new EnumMap<>(Storage.class);
        removals.put(Storage.REGION, removed);
        account(totals.get(Storage.REGION), region, removed, scan.external);
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

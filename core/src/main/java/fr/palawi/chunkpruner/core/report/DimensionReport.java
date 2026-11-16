package fr.palawi.chunkpruner.core.report;

import fr.palawi.chunkpruner.core.rules.KeepRule;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Counts for one dimension. */
public final class DimensionReport {
    /** {@code processed}, {@code not-generated} (no region folder) or {@code skipped} (see warnings). */
    public enum Status {
        PROCESSED("processed"),
        NOT_GENERATED("not-generated"),
        SKIPPED("skipped");

        private final String id;

        Status(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final String dimension;
    private final String folder;
    private Status status = Status.PROCESSED;
    private long scanned;
    private long pruned;
    private final Map<KeepRule, Long> kept = new EnumMap<>(KeepRule.class);
    private final Map<Storage, StorageReport> storages = new EnumMap<>(Storage.class);

    public DimensionReport(String dimension, String folder) {
        this.dimension = dimension;
        this.folder = folder;
        for (KeepRule rule : KeepRule.values()) {
            kept.put(rule, 0L);
        }
        for (Storage storage : Storage.values()) {
            storages.put(storage, StorageReport.EMPTY);
        }
    }

    public void status(Status value) {
        status = value;
    }

    public void countKept(KeepRule rule) {
        scanned++;
        kept.merge(rule, 1L, Long::sum);
    }

    public void countPruned() {
        scanned++;
        pruned++;
    }

    public void addStorage(Storage storage, StorageReport report) {
        storages.merge(storage, report, StorageReport::plus);
    }

    public String dimension() {
        return dimension;
    }

    public String folder() {
        return folder;
    }

    public Status status() {
        return status;
    }

    public long scanned() {
        return scanned;
    }

    public long pruned() {
        return pruned;
    }

    public long keptTotal() {
        return scanned - pruned;
    }

    public Map<KeepRule, Long> kept() {
        return Collections.unmodifiableMap(kept);
    }

    public Map<Storage, StorageReport> storages() {
        return Collections.unmodifiableMap(storages);
    }

    public long bytesBefore() {
        return storages.values().stream().mapToLong(StorageReport::bytesBefore).sum();
    }

    public long bytesAfter() {
        return storages.values().stream().mapToLong(StorageReport::bytesAfter).sum();
    }
}

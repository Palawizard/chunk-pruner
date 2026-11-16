package fr.palawi.chunkpruner.core.report;

import fr.palawi.chunkpruner.core.rules.KeepRule;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of one run (SPEC section 6). */
public record RunReport(
        String runId,
        String mode,
        String context,
        Instant startedAt,
        long durationMillis,
        String minecraftVersion,
        int dataVersion,
        String loader,
        String modVersion,
        boolean lz4Available,
        Map<String, Object> config,
        List<DimensionReport> dimensions,
        List<Warning> warnings) {
    public RunReport {
        config = Collections.unmodifiableMap(new LinkedHashMap<>(config));
        dimensions = List.copyOf(dimensions);
        warnings = List.copyOf(warnings);
    }

    public long scanned() {
        return dimensions.stream().mapToLong(DimensionReport::scanned).sum();
    }

    public long pruned() {
        return dimensions.stream().mapToLong(DimensionReport::pruned).sum();
    }

    public long kept() {
        return scanned() - pruned();
    }

    public Map<KeepRule, Long> keptByRule() {
        Map<KeepRule, Long> totals = new EnumMap<>(KeepRule.class);
        for (KeepRule rule : KeepRule.values()) {
            totals.put(
                    rule, dimensions.stream().mapToLong(d -> d.kept().get(rule)).sum());
        }
        return totals;
    }

    public long bytesBefore() {
        return dimensions.stream().mapToLong(DimensionReport::bytesBefore).sum();
    }

    public long bytesAfter() {
        return dimensions.stream().mapToLong(DimensionReport::bytesAfter).sum();
    }

    /** Dimensions with at least one pruned chunk. */
    public long dimensionsChanged() {
        return dimensions.stream().filter(d -> d.pruned() > 0).count();
    }
}

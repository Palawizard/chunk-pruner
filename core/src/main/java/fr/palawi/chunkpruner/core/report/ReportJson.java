package fr.palawi.chunkpruner.core.report;

import fr.palawi.chunkpruner.core.config.Json;
import fr.palawi.chunkpruner.core.rules.KeepRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON report, {@code chunkpruner/reports/<run-id>.json}. */
public final class ReportJson {
    public static final int FORMAT = 1;

    private ReportJson() {}

    public static String render(RunReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("runId", report.runId());
        root.put("mode", report.mode());
        root.put("context", report.context());
        root.put("startedAt", report.startedAt().toString());
        root.put("durationMillis", report.durationMillis());
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("minecraft", report.minecraftVersion());
        versions.put("dataVersion", report.dataVersion());
        versions.put("loader", report.loader());
        versions.put("mod", report.modVersion());
        root.put("versions", versions);
        root.put("lz4Available", report.lz4Available());
        root.put("config", report.config());
        root.put("totals", totals(report));
        List<Object> dimensions = new ArrayList<>();
        for (DimensionReport dimension : report.dimensions()) {
            dimensions.add(dimension(dimension));
        }
        root.put("dimensions", dimensions);
        List<Object> warnings = new ArrayList<>();
        for (Warning warning : report.warnings()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("type", warning.type());
            w.put("dimension", warning.dimension());
            w.put("file", warning.file());
            w.put("chunkX", warning.chunkX());
            w.put("chunkZ", warning.chunkZ());
            w.put("message", warning.message());
            warnings.add(w);
        }
        root.put("warnings", warnings);
        return Json.write(root);
    }

    private static Map<String, Object> totals(RunReport report) {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("chunksScanned", report.scanned());
        totals.put("chunksPruned", report.pruned());
        totals.put("chunksKept", report.kept());
        totals.put("keptByRule", rules(report.keptByRule()));
        totals.put("bytesBefore", report.bytesBefore());
        totals.put("bytesAfter", report.bytesAfter());
        return totals;
    }

    private static Map<String, Object> dimension(DimensionReport dimension) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", dimension.dimension());
        d.put("folder", dimension.folder());
        d.put("status", dimension.status().id());
        d.put("chunksScanned", dimension.scanned());
        d.put("chunksPruned", dimension.pruned());
        d.put("chunksKept", dimension.keptTotal());
        d.put("keptByRule", rules(dimension.kept()));
        Map<String, Object> storages = new LinkedHashMap<>();
        dimension.storages().forEach((storage, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("files", s.files());
            m.put("filesChanged", s.filesChanged());
            m.put("filesDeleted", s.filesDeleted());
            m.put("bytesBefore", s.bytesBefore());
            m.put("bytesAfter", s.bytesAfter());
            storages.put(storage.folder(), m);
        });
        d.put("storages", storages);
        return d;
    }

    private static Map<String, Object> rules(Map<KeepRule, Long> counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        counts.forEach((rule, count) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("chunks", count);
            r.put("reason", rule.reason());
            out.put(rule.name(), r);
        });
        return out;
    }
}

package fr.palawi.chunkpruner.core.report;

import fr.palawi.chunkpruner.core.rules.KeepRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The text report, {@code chunkpruner/reports/<run-id>.txt}. It starts with a short summary. */
public final class ReportText {
    private ReportText() {}

    public static String render(RunReport report) {
        boolean dryRun = "dry-run".equals(report.mode());
        StringBuilder out = new StringBuilder();
        out.append(summary(report)).append("\n\n");
        if (dryRun) {
            out.append("This was a dry run: no file was changed.\n\n");
        }
        out.append("Run ")
                .append(report.runId())
                .append(", ")
                .append(dryRun ? "dry run" : "prune")
                .append(" from the ")
                .append(report.context())
                .append(", ")
                .append(String.format(Locale.ROOT, "%.1f s", report.durationMillis() / 1000.0))
                .append(".\n");
        out.append("World saved by Minecraft ")
                .append(report.minecraftVersion() == null ? "(unknown)" : report.minecraftVersion())
                .append(" (data version ")
                .append(report.dataVersion())
                .append("). Chunk Pruner ")
                .append(report.modVersion() == null ? "(development build)" : report.modVersion());
        if (report.loader() != null) {
            out.append(", ").append(report.loader()).append(" build");
        }
        out.append(".\n");
        if (report.backup() != null) {
            out.append("Backup of the changed files: ")
                    .append(report.backup())
                    .append(". To undo the run, follow ")
                    .append(report.backup())
                    .append("/restore.txt.\n");
        } else if (!dryRun && report.pruned() > 0) {
            out.append("No backup: backup is false in the config.\n");
        }
        for (DimensionReport dimension : report.dimensions()) {
            out.append('\n').append(dimension(dimension));
        }
        if (!report.warnings().isEmpty()) {
            out.append("\nWarnings (").append(report.warnings().size()).append("):\n");
            for (Warning warning : report.warnings()) {
                out.append("- ").append(warningLine(warning)).append('\n');
            }
        }
        return out.toString();
    }

    /** The first sentence, as in SPEC section 6. */
    public static String summary(RunReport report) {
        boolean dryRun = "dry-run".equals(report.mode());
        long saved = Math.max(0, report.bytesBefore() - report.bytesAfter());
        String first;
        if (report.pruned() == 0) {
            first = dryRun ? "No unvisited chunk would be removed." : "No unvisited chunk was removed.";
        } else {
            first = (dryRun ? "Would remove " : "Removed ")
                    + count(report.pruned()) + " unvisited " + plural(report.pruned(), "chunk")
                    + " (" + bytes(saved) + ") from " + count(report.dimensionsChanged()) + " "
                    + plural(report.dimensionsChanged(), "dimension") + ".";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<KeepRule, Long> entry : report.keptByRule().entrySet()) {
            if (entry.getValue() > 0) {
                parts.add(count(entry.getValue()) + " " + entry.getKey().summary());
            }
        }
        String kept =
                (dryRun ? " Would keep " : " Kept ") + count(report.kept()) + " " + plural(report.kept(), "chunk");
        return first + kept + (parts.isEmpty() ? "." : ": " + String.join(", ", parts) + ".");
    }

    private static String dimension(DimensionReport d) {
        StringBuilder out = new StringBuilder();
        out.append("Dimension ")
                .append(d.dimension())
                .append(" (")
                .append(d.folder())
                .append("): ");
        if (d.status() != DimensionReport.Status.PROCESSED) {
            out.append(d.status() == DimensionReport.Status.NOT_GENERATED ? "no region files yet.\n" : "skipped.\n");
            return out.toString();
        }
        out.append(count(d.scanned()))
                .append(" chunks scanned, ")
                .append(count(d.pruned()))
                .append(" pruned, ")
                .append(count(d.keptTotal()))
                .append(" kept.\n");
        List<String> rules = new ArrayList<>();
        d.kept().forEach((rule, n) -> rules.add(rule.name() + " " + rule.summary() + " " + count(n)));
        out.append("  Kept by rule: ").append(String.join(", ", rules)).append(".\n");
        d.storages()
                .forEach((storage, s) -> out.append("  ")
                        .append(storage.folder())
                        .append(": ")
                        .append(count(s.files()))
                        .append(" files, ")
                        .append(bytes(s.bytesBefore()))
                        .append(" before, ")
                        .append(bytes(s.bytesAfter()))
                        .append(" after, ")
                        .append(count(s.filesChanged()))
                        .append(" changed, ")
                        .append(count(s.filesDeleted()))
                        .append(" deleted.\n"));
        return out.toString();
    }

    private static String warningLine(Warning w) {
        StringBuilder line = new StringBuilder("[").append(w.type()).append("] ");
        if (w.dimension() != null) {
            line.append(w.dimension()).append(' ');
        }
        if (w.file() != null) {
            line.append(w.file()).append(' ');
        }
        if (w.chunkX() != null) {
            line.append("chunk ")
                    .append(w.chunkX())
                    .append(", ")
                    .append(w.chunkZ())
                    .append(' ');
        }
        return line.append(w.message()).toString();
    }

    static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String plural(long n, String word) {
        return n == 1 ? word : word + "s";
    }

    /** Decimal units, as file managers on most systems show them. */
    static String bytes(long n) {
        if (n < 1000) {
            return n + " B";
        }
        String[] units = {"kB", "MB", "GB", "TB"};
        double value = n;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}

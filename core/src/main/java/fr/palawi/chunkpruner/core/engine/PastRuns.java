package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.config.Json;
import fr.palawi.chunkpruner.core.config.JsonException;
import fr.palawi.chunkpruner.core.report.ReportFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runs whose reports are in {@code <world>/chunkpruner/reports/}, newest first. A report that
 * cannot be read is left out.
 */
public final class PastRuns {
    private PastRuns() {}

    /** One report: its run id, mode, context and start time, and its text summary. */
    public record Run(String runId, String mode, String context, Instant startedAt, Path json, Path text) {
        /** The summary that opens the text report, or an empty string when it cannot be read. */
        public String summary() {
            try {
                String content = Files.readString(text, StandardCharsets.UTF_8);
                int end = content.indexOf("\n\n");
                return (end < 0 ? content : content.substring(0, end)).strip();
            } catch (IOException e) {
                return "";
            }
        }
    }

    public static List<Run> list(Path world) throws IOException {
        Path folder = ReportFiles.folder(world);
        List<Run> runs = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return runs;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path json : files) {
                read(json).ifPresent(runs::add);
            }
        }
        runs.sort(Comparator.comparing(Run::startedAt).thenComparing(Run::runId).reversed());
        return runs;
    }

    public static Optional<Run> latest(Path world) throws IOException {
        return list(world).stream().findFirst();
    }

    public static Optional<Run> latestDryRun(Path world) throws IOException {
        return list(world).stream()
                .filter(r -> RunMode.DRY_RUN.id().equals(r.mode()))
                .findFirst();
    }

    public static Optional<Run> find(Path world, String runId) throws IOException {
        return list(world).stream().filter(r -> r.runId().equals(runId)).findFirst();
    }

    private static Optional<Run> read(Path json) {
        try {
            if (Json.parse(Files.readString(json, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("runId") instanceof String runId
                    && root.get("mode") instanceof String mode
                    && root.get("startedAt") instanceof String startedAt) {
                String context = root.get("context") instanceof String c ? c : "";
                String name = json.getFileName().toString();
                Path text = json.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".txt");
                return Optional.of(new Run(runId, mode, context, Instant.parse(startedAt), json, text));
            }
        } catch (IOException | JsonException | DateTimeException e) {
            // A damaged or foreign file in the reports folder: not a run.
        }
        return Optional.empty();
    }
}

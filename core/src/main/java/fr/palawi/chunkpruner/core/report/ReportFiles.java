package fr.palawi.chunkpruner.core.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes {@code <world>/chunkpruner/reports/<run-id>.json} and {@code .txt}. */
public final class ReportFiles {
    private ReportFiles() {}

    public static Path folder(Path world) {
        return world.resolve("chunkpruner").resolve("reports");
    }

    /** Returns the JSON file; the text file is next to it. */
    public static Path write(Path world, RunReport report) throws IOException {
        Path folder = folder(world);
        Files.createDirectories(folder);
        Path json = folder.resolve(report.runId() + ".json");
        writeAtomically(json, ReportJson.render(report));
        writeAtomically(folder.resolve(report.runId() + ".txt"), ReportText.render(report));
        return json;
    }

    private static void writeAtomically(Path target, String text) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

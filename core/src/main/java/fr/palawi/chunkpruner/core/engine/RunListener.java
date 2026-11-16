package fr.palawi.chunkpruner.core.engine;

import java.nio.file.Path;

/**
 * Hears the steps of a prune run, for progress output. {@code path} is the file the step is about,
 * or null.
 */
@FunctionalInterface
public interface RunListener {
    String BACKUP_WRITTEN = "backup-written";
    String JOURNAL_WRITTEN = "journal-written";
    /** The new content of a file is on disk next to it, and not moved over it yet. */
    String TEMP_WRITTEN = "temp-written";

    String FILE_REWRITTEN = "file-rewritten";
    String REGION_DONE = "region-done";

    RunListener NONE = (event, path) -> {};

    void event(String event, Path path);
}

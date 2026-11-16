package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.anvil.RegionFile;
import fr.palawi.chunkpruner.core.anvil.RegionFile.ChunkEntry;
import fr.palawi.chunkpruner.core.engine.Journal.FilePlan;
import fr.palawi.chunkpruner.core.engine.Journal.RegionPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies the plan of one region (SPEC section 4, step 7), the same way in a run and when an
 * interrupted run resumes. Each file is first told apart: as it was when planned (rewritten), as
 * the plan leaves it (already done before an interruption), or neither (changed since: the run
 * stops and keeps its journal and backup).
 */
final class Rewrite {
    private Rewrite() {}

    enum State {
        BEFORE,
        AFTER,
        CHANGED
    }

    static void apply(Path world, RegionPlan region, RunListener listener) throws IOException {
        for (FilePlan file : region.files()) {
            Path folder = world.resolve(region.folder()).resolve(file.storage().folder());
            Path path = folder.resolve(region.file());
            switch (state(path, file)) {
                case BEFORE -> {
                    RegionWriter.remove(RegionFile.read(path), file.removed(), listener);
                    listener.event(RunListener.FILE_REWRITTEN, path);
                }
                case AFTER -> {
                    // Rewritten before the run was interrupted.
                }
                case CHANGED ->
                    throw new IOException(path + " changed since the run planned it (size "
                            + (Files.exists(path) ? Files.size(path) : 0) + " bytes, planned " + file.sizeBefore()
                            + " before and " + file.sizeAfter()
                            + " after). Nothing more is changed; the journal and the"
                            + " backup are kept.");
            }
            for (String external : file.externalFiles()) {
                Files.deleteIfExists(folder.resolve(external));
            }
            Files.deleteIfExists(AtomicFiles.temp(path));
        }
    }

    static State state(Path path, FilePlan file) throws IOException {
        if (!Files.exists(path)) {
            return file.sizeAfter() == 0 ? State.AFTER : State.CHANGED;
        }
        long size = Files.size(path);
        if (size == file.sizeBefore() && Files.getLastModifiedTime(path).toMillis() == file.modifiedBefore()) {
            return State.BEFORE;
        }
        if (size != file.sizeAfter()) {
            return State.CHANGED;
        }
        for (ChunkEntry entry : RegionFile.read(path).entries()) {
            if (file.removed().contains(entry.index())) {
                return State.CHANGED;
            }
        }
        return State.AFTER;
    }
}

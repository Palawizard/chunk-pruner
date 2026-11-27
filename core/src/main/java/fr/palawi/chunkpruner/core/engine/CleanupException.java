package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.engine.Engine.RunResult;
import java.io.IOException;

/**
 * A prune that is done, report written and journal deleted, but whose last steps failed: clearing
 * the schedule or the backup retention (SPEC section 4, step 8). The world is as the report says.
 */
public final class CleanupException extends IOException {
    private final transient RunResult result;

    CleanupException(RunResult result, IOException cause) {
        super(
                "the run is done (" + result.summary() + "), but its last step failed: " + cause.getMessage()
                        + " Report: " + result.textReport(),
                cause);
        this.result = result;
    }

    public RunResult result() {
        return result;
    }
}

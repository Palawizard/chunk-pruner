package fr.palawi.chunkpruner.core.engine;

import java.io.IOException;

/**
 * A prune run with a journal cannot go on: a file changed since the plan, or the journal cannot
 * be read. The message says how to leave that state (SPEC section 4).
 */
public final class UnfinishedRunException extends IOException {
    UnfinishedRunException(String message, Throwable cause) {
        super(message, cause);
    }
}

package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.world.WorldException;

/** A prune run stopped before any change because the disk is too full (SPEC section 4). */
public final class NotEnoughSpaceException extends WorldException {
    private static final long serialVersionUID = 1L;

    public NotEnoughSpaceException(String message) {
        super(message);
    }
}

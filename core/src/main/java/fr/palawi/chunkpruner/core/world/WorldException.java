package fr.palawi.chunkpruner.core.world;

/** The world cannot be processed: the run stops before any change. */
public class WorldException extends Exception {
    private static final long serialVersionUID = 1L;

    public WorldException(String message) {
        super(message);
    }

    public WorldException(String message, Throwable cause) {
        super(message, cause);
    }
}

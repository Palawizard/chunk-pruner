package fr.palawi.chunkpruner.core.world;

/** Another program holds the world's session lock. */
public final class WorldInUseException extends WorldException {
    private static final long serialVersionUID = 1L;

    public WorldInUseException(String message) {
        super(message);
    }
}

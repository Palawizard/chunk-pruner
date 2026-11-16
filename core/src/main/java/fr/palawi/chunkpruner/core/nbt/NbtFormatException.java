package fr.palawi.chunkpruner.core.nbt;

import java.io.IOException;

/** The data is not a well-formed NBT compound, or breaks one of the reader's limits. */
public final class NbtFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    public NbtFormatException(String message) {
        super(message);
    }

    public NbtFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}

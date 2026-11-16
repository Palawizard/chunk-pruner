package fr.palawi.chunkpruner.core.config;

/** The text is not valid JSON, or a config value has the wrong type. */
public final class JsonException extends Exception {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}

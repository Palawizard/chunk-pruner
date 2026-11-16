package fr.palawi.chunkpruner.core.report;

/**
 * One report warning. {@code dimension}, {@code file} and the chunk coordinates are null when
 * they do not apply.
 */
public record Warning(String type, String dimension, String file, Integer chunkX, Integer chunkZ, String message) {
    public static final String SKIPPED_DIMENSION = "skipped-dimension";
    public static final String UNREADABLE_CHUNK = "unreadable-chunk";
    public static final String UNREADABLE_REGION = "unreadable-region";
    public static final String EXTERNAL_CHUNK = "external-chunk";
    public static final String LOW_DISK_SPACE = "low-disk-space";
    public static final String FORCED_CHUNKS_UNKNOWN = "forced-chunks-unknown";
    public static final String RESUMED = "resumed";
    public static final String MORE_WARNINGS = "more-warnings";
}

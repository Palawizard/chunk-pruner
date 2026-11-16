package fr.palawi.chunkpruner.core.engine;

/** A dry run only reports; a prune run also rewrites the region files. */
public enum RunMode {
    DRY_RUN("dry-run"),
    PRUNE("prune");

    private final String id;

    RunMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}

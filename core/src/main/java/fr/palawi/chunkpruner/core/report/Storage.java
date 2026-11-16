package fr.palawi.chunkpruner.core.report;

/** The three Anvil storages of a dimension folder, with the same chunk coordinates. */
public enum Storage {
    REGION("region"),
    ENTITIES("entities"),
    POI("poi");

    private final String folder;

    Storage(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}

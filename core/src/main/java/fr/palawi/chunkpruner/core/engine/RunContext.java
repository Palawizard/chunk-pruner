package fr.palawi.chunkpruner.core.engine;

/** Where a run happens (SPEC section 3). */
public enum RunContext {
    E1("server startup"),
    E2("singleplayer screen"),
    E3("command line");

    private final String description;

    RunContext(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}

package fr.palawi.chunkpruner.core.rules;

/**
 * The keep rules of SPEC section 2. A chunk is kept when any rule matches; the first match, in
 * the order of this enum, is its recorded reason.
 */
public enum KeepRule {
    K1("played in", "InhabitedTime is above the threshold"),
    K2("near players or spawn", "within protectRadiusChunks of the world spawn or a player position"),
    K3("force-loaded", "listed in the dimension's forced chunks or chunk tickets"),
    K4("holding structure starts", "holds a structure start referenced by another kept chunk"),
    K5("with player entities", "holds an entity a player cares about, placed or moved"),
    K6("in protected areas", "inside a protectedAreas box"),
    K7("unreadable", "cannot be read or understood"),
    K8("next to played chunks", "within protectNeighborRadiusChunks of a chunk kept by K1");

    private final String summary;
    private final String reason;

    KeepRule(String summary, String reason) {
        this.summary = summary;
        this.reason = reason;
    }

    /** Short phrase for the text summary, such as "played in". */
    public String summary() {
        return summary;
    }

    /** The reason recorded in reports. */
    public String reason() {
        return reason;
    }
}

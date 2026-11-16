package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.report.Warning;
import java.util.ArrayList;
import java.util.List;

/** Collects report warnings, keeping the first {@link #LIMIT} and counting the rest. */
final class Warnings {
    static final int LIMIT = 1000;

    private final List<Warning> kept = new ArrayList<>();
    private long dropped;

    void add(Warning warning) {
        if (kept.size() < LIMIT) {
            kept.add(warning);
        } else {
            dropped++;
        }
    }

    List<Warning> all() {
        List<Warning> result = new ArrayList<>(kept);
        if (dropped > 0) {
            result.add(new Warning(
                    Warning.MORE_WARNINGS, null, null, null, null, dropped + " more warnings are not listed."));
        }
        return result;
    }
}

package fr.palawi.chunkpruner.core.fixture;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Second process for the S5 tests: prunes a world with the default config and prints each step of
 * the run as {@code event <name> <file>}. With {@code <event> <n>}, it prints {@code paused} at
 * the n-th time that step happens and waits there, so the test can kill it at that moment.
 * Usage: {@code <world> [<event> <n>]}.
 */
public final class PruneChild {
    private PruneChild() {}

    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        String pauseAt = args.length > 2 ? args[1] : null;
        int pauseCount = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        AtomicInteger seen = new AtomicInteger();
        RunSettings settings = RunSettings.of(world, Config.defaults(), RunMode.PRUNE, RunContext.E3)
                .withListener((event, path) -> {
                    System.out.println("event " + event + " " + path);
                    if (event.equals(pauseAt) && seen.incrementAndGet() == pauseCount) {
                        System.out.println("paused");
                        System.out.flush();
                        try {
                            Thread.sleep(600_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    System.out.flush();
                });
        Engine.RunResult result = Engine.run(settings);
        System.out.println("finished " + result.summary());
    }
}

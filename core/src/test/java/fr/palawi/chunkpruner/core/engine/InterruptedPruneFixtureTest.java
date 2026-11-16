package fr.palawi.chunkpruner.core.engine;

import static fr.palawi.chunkpruner.core.engine.EngineFixtures.readJson;
import static fr.palawi.chunkpruner.core.engine.EngineFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.fixture.Expected;
import fr.palawi.chunkpruner.core.fixture.FixtureWorlds;
import fr.palawi.chunkpruner.core.fixture.PruneChild;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S5: a second Java process prunes a copy of a fixture world and is killed (destroyForcibly, a
 * SIGKILL on Linux) at a chosen step or after a delay. After the kill, every storage file is
 * either byte for byte what it was or byte for byte what an uninterrupted prune makes of it; that
 * uninterrupted prune is checked against the test reader's expected chunks first. The
 * next run finishes the interrupted one from its journal, and the world then matches the
 * uninterrupted prune exactly. {@code scripts/check-pruned-world.ps1 -Interrupt} opens such a
 * world on the real server.
 */
class InterruptedPruneFixtureTest {
    private static final String NEVER = "none";

    @TempDir
    Path temp;

    static Stream<Arguments> cases() {
        List<Object[]> moments = List.of(
                new Object[] {RunListener.BACKUP_WRITTEN, 1, 0},
                new Object[] {RunListener.JOURNAL_WRITTEN, 1, 0},
                new Object[] {RunListener.TEMP_WRITTEN, 1, 0},
                new Object[] {RunListener.FILE_REWRITTEN, 1, 0},
                new Object[] {RunListener.TEMP_WRITTEN, 3, 0},
                new Object[] {RunListener.REGION_DONE, 1, 0},
                new Object[] {RunListener.REGION_DONE, 2, 0},
                new Object[] {NEVER, 0, 0},
                new Object[] {NEVER, 0, 2},
                new Object[] {NEVER, 0, 10},
                new Object[] {NEVER, 0, 40});
        return Stream.of("1.20.1", "1.21.1", "26.3")
                .flatMap(world -> moments.stream().map(m -> Arguments.of(world, m[0], m[1], m[2])));
    }

    @ParameterizedTest(name = "{0}, killed at {1} #{2}, delay {3} ms")
    @MethodSource("cases")
    void anInterruptedPruneLeavesOldOrNewFilesAndResumes(String name, String event, int count, int delayMillis)
            throws Exception {
        Path original = FixtureWorlds.committed(name);
        Map<String, String> before = storageFiles(original);
        Path clean = FixtureWorlds.copy(original, temp.resolve("clean"));
        Engine.run(settings(clean, Config.defaults(), RunMode.PRUNE));
        Map<String, String> after = storageFiles(clean);
        assertTrue(!before.equals(after), "the prune changes the world");
        Set<Long> gone = keys(original);
        gone.removeAll(keys(clean));
        assertEquals(
                Expected.compute(ReferenceWorld.read(original), Expected.Rules.defaults(), true).pruned,
                gone,
                "the uninterrupted prune removes exactly the chunks the test reader expects");

        Path world = FixtureWorlds.copy(original, temp.resolve("world"));
        boolean killed = runAndKill(world, event, count, delayMillis);

        Map<String, String> now = storageFiles(world);
        Set<String> paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        int rewritten = 0;
        for (String path : paths) {
            String state = now.get(path);
            boolean old = Objects.equals(state, before.get(path));
            boolean fresh = Objects.equals(state, after.get(path));
            assertTrue(old || fresh, path + " is neither the old file nor the pruned one");
            rewritten += fresh && !old ? 1 : 0;
        }
        boolean journal = Files.exists(world.resolve("chunkpruner/journal.json"));
        if (event.equals(RunListener.BACKUP_WRITTEN)) {
            assertTrue(killed && !journal && rewritten == 0, "killed before the journal: nothing changed");
        }
        if (event.equals(RunListener.JOURNAL_WRITTEN) || event.equals(RunListener.TEMP_WRITTEN) && count == 1) {
            assertTrue(killed && journal && rewritten == 0, "killed before the first file moved");
        }

        boolean reported = hasReport(world);
        // With a journal, even a dry run finishes the interrupted prune first (SPEC section 4);
        // without one nothing changed, and a new prune starts from the start.
        Engine.RunResult next =
                Engine.run(settings(world, Config.defaults(), journal ? RunMode.DRY_RUN : RunMode.PRUNE));
        assertEquals(after, storageFiles(world), "the next run leaves the world as an uninterrupted prune");
        assertFalse(Files.exists(world.resolve("chunkpruner/journal.json")), "the journal is gone");
        assertFalse(Files.exists(world.resolve("chunkpruner/journal-plan.json")), "the plan is gone");
        assertFalse(hasTempFiles(world), "no .tmp file is left");
        if (journal) {
            assertTrue(next.resumed(), "a journal makes the next run finish the interrupted one");
            // Killed after its report was written, the run keeps that report as it was.
            assertEquals(
                    !reported,
                    readJson(next.jsonReport()).get("warnings").toString().contains("resumed"));
        }
    }

    /** Returns true when the child was killed before it finished. */
    private static boolean runAndKill(Path world, String event, int count, int delayMillis) throws Exception {
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(
                javaExe.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                PruneChild.class.getName(),
                world.toString()));
        if (!event.equals(NEVER)) {
            command.add(event);
            command.add(Integer.toString(count));
        }
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader out =
                new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith("finished")) {
                    child.waitFor(60, TimeUnit.SECONDS);
                    return false;
                }
                boolean now = event.equals(NEVER)
                        ? line.startsWith("event " + RunListener.JOURNAL_WRITTEN)
                        : line.equals("paused");
                if (now) {
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                    child.destroyForcibly();
                    child.waitFor(60, TimeUnit.SECONDS);
                    return true;
                }
            }
        } finally {
            child.destroyForcibly();
        }
        throw new AssertionError("the child ended without finishing (exit code " + child.waitFor() + ")");
    }

    private static boolean hasReport(Path world) throws IOException {
        Path reports = world.resolve("chunkpruner/reports");
        if (!Files.isDirectory(reports)) {
            return false;
        }
        try (Stream<Path> files = Files.list(reports)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".json"));
        }
    }

    private static Set<Long> keys(Path world) throws IOException {
        Set<Long> keys = new HashSet<>();
        for (ReferenceWorld.Chunk c : ReferenceWorld.read(world).chunks("region", true)) {
            keys.add(c.key());
        }
        return keys;
    }

    /** SHA-256 of every file in a region, entities or poi folder, .tmp files left out. */
    private static Map<String, String> storageFiles(Path world) throws IOException {
        Map<String, String> sums = new HashMap<>(ReferenceWorld.checksums(world));
        sums.keySet()
                .removeIf(p -> !p.matches("(.*/)?(region|entities|poi)/[^/]+")
                        || p.endsWith(".tmp")
                        || p.startsWith("chunkpruner/"));
        return sums;
    }

    private static boolean hasTempFiles(Path world) throws IOException {
        try (Stream<Path> files = Files.walk(world)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        }
    }
}

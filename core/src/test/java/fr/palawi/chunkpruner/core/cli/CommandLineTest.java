package fr.palawi.chunkpruner.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CommandLineTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return CommandLine.run(
                args,
                "0.1.0",
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpPrintsUsageAndExitsWithZero() {
        assertEquals(CommandLine.EXIT_OK, run("--help"));
        assertTrue(stdout().startsWith("Chunk Pruner 0.1.0"));
        assertTrue(stdout().contains("Usage: java -jar"));
        assertTrue(stdout().contains("--help"));
        assertEquals("", stderr());
    }

    @Test
    void shortHelpFlagWorksToo() {
        assertEquals(CommandLine.EXIT_OK, run("-h"));
        assertTrue(stdout().contains("Usage: java -jar"));
    }

    @Test
    void noArgumentsPrintsUsageToStderr() {
        assertEquals(CommandLine.EXIT_USAGE, run());
        assertEquals("", stdout());
        assertTrue(stderr().contains("Usage: java -jar"));
    }

    @Test
    void unknownOptionIsRejected() {
        assertEquals(CommandLine.EXIT_USAGE, run("--world"));
        assertTrue(stderr().contains("Unknown option: --world"));
    }

    @Test
    void helpWithoutVersionStillHasATitle() {
        assertTrue(CommandLine.help(null).startsWith("Chunk Pruner" + System.lineSeparator()));
    }
}

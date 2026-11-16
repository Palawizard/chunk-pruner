package fr.palawi.chunkpruner.core.fixture;

import fr.palawi.chunkpruner.core.config.Config;
import fr.palawi.chunkpruner.core.engine.Engine;
import fr.palawi.chunkpruner.core.engine.RunContext;
import fr.palawi.chunkpruner.core.engine.RunMode;
import fr.palawi.chunkpruner.core.engine.RunSettings;
import fr.palawi.chunkpruner.core.fixture.ReferenceWorld.Chunk;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only entry point for {@code scripts/check-pruned-world.ps1}, which opens a pruned copy on a
 * real server. It lives in the test sources, so it is never in a mod JAR, and
 * {@code check-release-artifacts.ps1} refuses any JAR that contains it.
 *
 * <ul>
 *   <li>{@code prune <world>}: prunes with the default config and prints {@code target <x> <z>},
 *       a pruned chunk whose neighbours were all pruned too.
 *   <li>{@code absent <world> <x> <z>} and {@code generated <world> <x> <z>}: exit 0 when the
 *       overworld chunk is absent, or present and fully generated, as the test reader sees it.
 * </ul>
 */
public final class FixturePruneTool {
    private FixturePruneTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: prune <world> | absent <world> <x> <z> | generated <world> <x> <z>");
            System.exit(2);
        }
        Path world = Path.of(args[1]);
        switch (args[0]) {
            case "prune" -> System.exit(prune(world));
            case "absent" -> System.exit(find(world, args) == null ? 0 : 1);
            case "generated" -> {
                Chunk chunk = find(world, args);
                boolean ok = chunk != null
                        && chunk.readable()
                        && "minecraft:full".equals(chunk.nbt().get("Status"));
                System.out.println(
                        chunk == null
                                ? "missing"
                                : "status " + chunk.nbt().get("Status") + ", InhabitedTime " + chunk.inhabitedTime());
                System.exit(ok ? 0 : 1);
            }
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }

    private static int prune(Path world) throws Exception {
        List<Chunk> before = ReferenceWorld.read(world).chunks("region", true);
        Engine.RunResult result = Engine.run(RunSettings.of(world, Config.defaults(), RunMode.PRUNE, RunContext.E3));
        System.out.println(result.summary());
        Set<Long> remaining = new HashSet<>();
        for (Chunk c : ReferenceWorld.read(world).chunks("region", true)) {
            remaining.add(c.key());
        }
        Set<Long> existed = new HashSet<>();
        before.forEach(c -> existed.add(c.key()));
        for (Chunk c : before) {
            if (remaining.contains(c.key())) {
                continue;
            }
            boolean surrounded = true;
            for (int dx = -1; dx <= 1 && surrounded; dx++) {
                for (int dz = -1; dz <= 1 && surrounded; dz++) {
                    long key = (long) (c.x() + dx) << 32 | ((c.z() + dz) & 0xFFFFFFFFL);
                    surrounded = existed.contains(key) && !remaining.contains(key);
                }
            }
            if (surrounded) {
                System.out.println("target " + c.x() + " " + c.z());
                return 0;
            }
        }
        System.err.println("no pruned chunk with pruned neighbours");
        return 1;
    }

    private static Chunk find(Path world, String[] args) throws Exception {
        int x = Integer.parseInt(args[2]);
        int z = Integer.parseInt(args[3]);
        for (Chunk c : ReferenceWorld.read(world).chunks("region", true)) {
            if (c.x() == x && c.z() == z) {
                return c;
            }
        }
        return null;
    }
}

package fr.palawi.chunkpruner.core.engine;

import fr.palawi.chunkpruner.core.anvil.Compression;
import fr.palawi.chunkpruner.core.config.Config;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * Everything a run needs. {@code takeSessionLock} is true when the engine itself must make sure
 * the world is not open (E2, E3); on a server (E1) the server already holds the lock.
 * {@code loader} and {@code modVersion} only go into the report and may be null; {@code listener}
 * hears the steps of a prune run. {@code scanThreads} is the number of threads that read region
 * files during the scan; writes always happen on the calling thread, one file after the other.
 */
public record RunSettings(
        Path world,
        Config config,
        RunMode mode,
        RunContext context,
        boolean takeSessionLock,
        FreeSpace freeSpace,
        Clock clock,
        Compression compression,
        String loader,
        String modVersion,
        RunListener listener,
        int scanThreads) {
    public RunSettings {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(freeSpace, "freeSpace");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(listener, "listener");
        if (scanThreads < 1 || scanThreads > ParallelReads.MAX_THREADS) {
            throw new IllegalArgumentException("scanThreads must be from 1 to " + ParallelReads.MAX_THREADS);
        }
    }

    public static RunSettings of(Path world, Config config, RunMode mode, RunContext context) {
        return new RunSettings(
                world,
                config,
                mode,
                context,
                true,
                FreeSpace.SYSTEM,
                Clock.systemUTC(),
                Compression.detect(),
                null,
                null,
                RunListener.NONE,
                ParallelReads.defaultThreads());
    }

    public RunSettings withFreeSpace(FreeSpace value) {
        return new RunSettings(
                world,
                config,
                mode,
                context,
                takeSessionLock,
                value,
                clock,
                compression,
                loader,
                modVersion,
                listener,
                scanThreads);
    }

    public RunSettings withCompression(Compression value) {
        return new RunSettings(
                world,
                config,
                mode,
                context,
                takeSessionLock,
                freeSpace,
                clock,
                value,
                loader,
                modVersion,
                listener,
                scanThreads);
    }

    public RunSettings withClock(Clock value) {
        return new RunSettings(
                world,
                config,
                mode,
                context,
                takeSessionLock,
                freeSpace,
                value,
                compression,
                loader,
                modVersion,
                listener,
                scanThreads);
    }

    public RunSettings withListener(RunListener value) {
        return new RunSettings(
                world,
                config,
                mode,
                context,
                takeSessionLock,
                freeSpace,
                clock,
                compression,
                loader,
                modVersion,
                value,
                scanThreads);
    }

    public RunSettings withVersions(String loaderName, String version) {
        return new RunSettings(
                world,
                config,
                mode,
                context,
                takeSessionLock,
                freeSpace,
                clock,
                compression,
                loaderName,
                version,
                listener,
                scanThreads);
    }

    public RunSettings withScanThreads(int value) {
        return new RunSettings(
                world,
                config,
                mode,
                context,
                takeSessionLock,
                freeSpace,
                clock,
                compression,
                loader,
                modVersion,
                listener,
                value);
    }
}

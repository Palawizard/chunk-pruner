package fr.palawi.chunkpruner.core.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Free disk space for the world folder. Tests give their own value (S11). */
@FunctionalInterface
public interface FreeSpace {
    FreeSpace SYSTEM = folder -> Files.getFileStore(folder).getUsableSpace();

    long usableBytes(Path folder) throws IOException;
}

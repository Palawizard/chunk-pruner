package fr.palawi.chunkpruner.core.fixture;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Second process for the S7 test: takes the world's {@code session.lock} the way the game does
 * (an exclusive lock on the file), prints {@code locked}, and holds it until its input closes.
 */
public final class LockHolder {
    private LockHolder() {}

    public static void main(String[] args) throws Exception {
        Path lock = Path.of(args[0]).resolve("session.lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock held = channel.tryLock()) {
            if (held == null) {
                System.out.println("busy");
                return;
            }
            System.out.println("locked");
            System.out.flush();
            System.in.read();
        }
    }
}

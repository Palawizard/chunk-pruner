package fr.palawi.chunkpruner.core.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The world's {@code session.lock}, taken the way the game takes it ({@code DirectoryLock}: an
 * exclusive {@link FileChannel#tryLock()} on the file). While a server or a client has the world
 * open, the lock is held and {@link #acquire} refuses. Taking it does not change the file. When
 * the file does not exist, nothing is locked and nothing is created.
 */
public final class SessionLock implements AutoCloseable {
    public static final String FILE_NAME = "session.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private SessionLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Holds the lock until {@link #close()}; throws when another process holds it. */
    public static SessionLock acquire(Path world) throws WorldException {
        Path file = world.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new SessionLock(null, null);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw inUse(world);
            }
            return new SessionLock(channel, lock);
        } catch (AccessDeniedException | OverlappingFileLockException e) {
            closeQuietly(channel);
            throw inUse(world);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new WorldException("cannot check " + file + ": " + e.getMessage(), e);
        }
    }

    private static WorldInUseException inUse(Path world) {
        return new WorldInUseException("The world " + world
                + " is open in another program (its session.lock is held). Close the game or stop the server first.");
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The lock was not taken; there is nothing else to release.
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (channel == null) {
            return;
        }
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}

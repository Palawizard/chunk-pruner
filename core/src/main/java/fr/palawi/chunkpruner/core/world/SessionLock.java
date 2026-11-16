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
 * the file does not exist, a dry run locks nothing and creates nothing; a run that rewrites files
 * creates it the way the game does (the snowman character, then the lock), so the game cannot open
 * the world while the files change.
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
        return acquire(world, false);
    }

    /** {@code create}: create a missing file, as the game does, and lock it. */
    public static SessionLock acquire(Path world, boolean create) throws WorldException {
        Path file = world.resolve(FILE_NAME);
        boolean exists = Files.exists(file);
        if (!exists && !create) {
            return new SessionLock(null, null);
        }
        FileChannel channel = null;
        try {
            channel = exists
                    ? FileChannel.open(file, StandardOpenOption.WRITE)
                    : FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (!exists) {
                channel.write(java.nio.ByteBuffer.wrap("\u2603".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw inUse(world);
            }
            return new SessionLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            throw inUse(world);
        } catch (AccessDeniedException e) {
            closeQuietly(channel);
            throw new WorldInUseException("Cannot lock " + file + ": access denied. The world is open in another"
                    + " program, or the file is read-only for this user.");
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

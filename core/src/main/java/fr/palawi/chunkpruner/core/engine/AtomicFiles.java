package fr.palawi.chunkpruner.core.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Atomic writes: the new content goes to {@code <name>.tmp} next to the target, is forced to disk,
 * then moved over the target in one step. A process killed at any point leaves either the old
 * file or the new one, and at most a {@code .tmp} file next to it.
 */
final class AtomicFiles {
    private AtomicFiles() {}

    static void writeString(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = temp(target);
        try (FileChannel out = FileChannel.open(
                temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                out.write(bytes);
            }
            out.force(true);
        }
        move(temp, target);
    }

    /** Copies a file through a forced {@code .tmp}, then checks that the copy has the source's size. */
    static void copyChecked(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = temp(target);
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            out.force(true);
        }
        move(temp, target);
        long expected = Files.size(source);
        long actual = Files.size(target);
        if (expected != actual) {
            throw new IOException("the backup of " + source + " has " + actual + " bytes instead of " + expected);
        }
    }

    static Path temp(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        syncFolder(target.getParent());
    }

    /**
     * Forces the folder entry of a moved file to disk where the platform allows it (Linux and
     * macOS). Windows cannot open a folder this way; NTFS journals the rename itself.
     */
    private static void syncFolder(Path folder) {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Best effort, see above.
        }
    }
}

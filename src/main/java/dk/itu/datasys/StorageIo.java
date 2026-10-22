package dk.itu.datasys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Supplies atomic catalog publication and best-effort deletion operations.
 *
 * <p>Package-private overrides allow deterministic filesystem failure tests.
 *
 * @author Team 1
 * @version 0.7
 * @since 0.7
 */
class StorageIo {
    /** Creates the filesystem adapter. */
    StorageIo() { }

    /**
     * Replaces a catalog atomically, with no non-atomic fallback.
     *
     * @param source the completed temporary catalog
     * @param target the published catalog path
     * @throws IOException if the move fails, including when atomic moves are unsupported
     */
    void publish(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Removes a file if present.
     *
     * @param path the file to remove
     * @throws IOException if deletion fails
     */
    void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}

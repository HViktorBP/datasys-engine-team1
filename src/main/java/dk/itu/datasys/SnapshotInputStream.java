package dk.itu.datasys;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads at most the number of bytes present in a file when it is opened.
 *
 * <p>Appends are excluded so a live-log import cannot consume its own new log records.
 * Existing bytes are not copied or protected against modification or truncation.
 *
 * @author Team 1
 * @version 0.5
 * @since 0.5
 */
final class SnapshotInputStream extends InputStream {
    /** Open source file whose size established the snapshot boundary. */
    private final FileInputStream input;
    /** Bytes still available before the captured boundary. */
    private long remaining;

    /**
     * Opens a file and captures its byte length through the same file handle.
     *
     * @param path the source file
     * @throws IOException if the file cannot be opened or its size cannot be obtained
     */
    SnapshotInputStream(Path path) throws IOException {
        var opened = new FileInputStream(path.toFile());
        try {
            remaining = opened.getChannel().size();
        } catch (IOException error) {
            opened.close();
            throw error;
        }
        input = opened;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns end-of-input at the captured boundary even if the source has grown.
     */
    @Override
    public int read() throws IOException {
        if (remaining == 0) return -1;
        int value = input.read();
        if (value != -1) remaining--;
        return value;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Limits the requested read to bytes remaining inside the captured boundary.
     */
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int count = input.read(bytes, offset, (int) Math.min(length, remaining));
        if (count > 0) remaining -= count;
        return count;
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
        input.close();
    }
}

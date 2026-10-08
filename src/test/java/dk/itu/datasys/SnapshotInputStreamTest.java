package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the byte boundary used when importing a growing CSV file. */
class SnapshotInputStreamTest {
    @TempDir Path dir;

    @Test
    void excludesAppendedBytesForSingleByteAndBulkReads() throws Exception {
        Path csv = dir.resolve("growing.csv");
        Files.writeString(csv, "abc");
        try (var input = new SnapshotInputStream(csv)) {
            Files.writeString(csv, "def", StandardOpenOption.APPEND);
            assertEquals('a', input.read());
            assertArrayEquals(new byte[]{'b', 'c'}, input.readAllBytes());
            assertEquals(-1, input.read());
            assertEquals(-1, input.read(new byte[2], 0, 2));
            assertEquals(0, input.read(new byte[2], 0, 0));
        }
    }

    @Test
    void emptySnapshotStaysEmptyWhenTheFileGrows() throws Exception {
        Path csv = Files.createFile(dir.resolve("empty.csv"));
        try (var input = new SnapshotInputStream(csv)) {
            Files.writeString(csv, "new row\n", StandardOpenOption.APPEND);
            assertEquals(-1, input.read());
            assertEquals(-1, input.read(new byte[10]));
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(new byte[1], 0, 2));
        }
    }
}

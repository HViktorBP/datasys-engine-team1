package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageHelpersTest {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));

    @Test void codecRoundTripsExactValuesAndChecksChunkBoundaries() throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(dir.resolve("test.bin").toFile(), "rw")) {
            BinaryColumnCodec.writeHeader(file);
            for (Object[] test : new Object[][]{{ColumnType.LONG, Long.MIN_VALUE},
                    {ColumnType.LONG, Long.MAX_VALUE}, {ColumnType.DOUBLE, -123.125},
                    {ColumnType.DOUBLE, -0.0}, {ColumnType.STRING, ""}, {ColumnType.STRING, "hello"}}) {
                var type = (ColumnType) test[0];
                long offset = file.getFilePointer();
                BinaryColumnCodec.writeValue(file, type, test[1]);
                long end = file.getFilePointer();
                assertArrayEquals(new Object[]{test[1]}, BinaryColumnCodec.readChunk(file, type, offset, end-offset, 1));
                assertThrows(IOException.class, () -> BinaryColumnCodec.readChunk(file, type, offset, end-offset+1, 1));
                file.seek(end);
            }
            BinaryColumnCodec.checkHeader(file);
            file.seek(0);
            assertEquals(0x48544244, file.readInt());
            assertEquals(1, file.readInt());
            assertEquals(Long.MIN_VALUE, file.readLong());
            assertThrows(IllegalArgumentException.class, () -> BinaryColumnCodec.writeValue(file, ColumnType.STRING, "ø"));
        }
    }

    @Test void rejectsBadHeadersAndStringLengths() throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(dir.resolve("bad.bin").toFile(), "rw")) {
            f.writeInt(0); f.writeInt(1);
            assertThrows(IOException.class, () -> BinaryColumnCodec.checkHeader(f));
            f.seek(0); f.writeInt(0x48544244); f.writeInt(2);
            assertThrows(IOException.class, () -> BinaryColumnCodec.checkHeader(f));
            f.seek(8); f.writeInt(-1);
            assertThrows(IOException.class, () -> BinaryColumnCodec.readChunk(f, ColumnType.STRING, 8, 4, 1));
            f.seek(8); f.writeInt(Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> BinaryColumnCodec.readChunk(f, ColumnType.STRING, 8, 4, 1));
            f.seek(8); f.writeInt(1); f.writeByte(128);
            assertThrows(IOException.class, () -> BinaryColumnCodec.readChunk(f, ColumnType.STRING, 8, 5, 1));
            assertThrows(IOException.class, () -> BinaryColumnCodec.readChunk(f, ColumnType.LONG, -1, 8, 1));
        }
    }

    @Test void computesTypedStatistics() {
        assertEquals(new PartitionStatistics("-9", "3"), PartitionStatistics.compute(ColumnType.LONG, List.of(-9L, 3L, -2L)));
        assertEquals(new PartitionStatistics("-2.5", "-2.5"), PartitionStatistics.compute(ColumnType.DOUBLE, List.of(-2.5)));
        assertEquals(new PartitionStatistics("A", "z"), PartitionStatistics.compute(ColumnType.STRING, List.of("z", "A", "a")));
    }

    @Test void prunesOnlyImpossibleRangesAtStrictBoundaries() {
        var stats = new PartitionStatistics("10", "20");
        assertTrue(PartitionPruner.canPrune(ColumnType.LONG, stats, Comparison.EQUALS, 9L));
        assertFalse(PartitionPruner.canPrune(ColumnType.LONG, stats, Comparison.EQUALS, 10L));
        assertTrue(PartitionPruner.canPrune(ColumnType.LONG, stats, Comparison.LESS_THAN, 10L));
        assertFalse(PartitionPruner.canPrune(ColumnType.LONG, stats, Comparison.LESS_THAN, 11L));
        assertTrue(PartitionPruner.canPrune(ColumnType.LONG, stats, Comparison.GREATER_THAN, 20L));
        assertFalse(PartitionPruner.canPrune(ColumnType.LONG, stats, Comparison.GREATER_THAN, 19L));
    }

    @Test void parsesPositionallyAndReportsBadInput() {
        assertArrayEquals(new Object[]{"A", -3L, 2.5}, CsvRowParser.parse("A,-3,2.5", schema, "input.csv", 1));
        assertArrayEquals(new Object[]{""}, CsvRowParser.parse("", List.of(schema.getFirst()), "input.csv", 1));
        assertArrayEquals(new Object[]{2L, ""}, CsvRowParser.parse("2,", List.of(schema.get(1), schema.get(0)), "input.csv", 1));
        for (String bad : List.of("A,3", "A,no,2", "ø,3,2", "A,9223372036854775808,2")) {
            var error = assertThrows(IllegalArgumentException.class, () -> CsvRowParser.parse(bad, schema, "input.csv", 7));
            assertTrue(error.getMessage().contains("input.csv"));
            assertTrue(error.getMessage().contains("7"));
        }
    }
}

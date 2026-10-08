package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

class ScanOperatorTest {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));
    String session;
    Map<String, String> previousContext;

    @BeforeEach
    void isolateLogContext() {
        previousContext = MDC.getCopyOfContextMap();
        session = UUID.randomUUID().toString();
        MDC.put("sessionId", session);
        MDC.put("statementNumber", "9");
    }

    @AfterEach
    void restoreLogContext() {
        if (previousContext == null) MDC.clear();
        else MDC.setContextMap(previousContext);
    }

    @Test
    void summarizesRowsBeforeFilteringAndOnlyHandedPartitions() throws Exception {
        var engine = loadedEngine();
        var partitions = engine.requireTable("trips").files().getFirst().partitions();
        Path dataFile = engine.publishedDataFile("trips");
        var scan = new ScanOperator(dataFile, schema,
                List.of(partitions.getFirst(), partitions.getLast()));
        var filtered = new FilterOperator(scan, 1, ColumnType.LONG, Comparison.GREATER_THAN, 100L);

        var rows = drain(filtered);
        assertEquals(List.of(187L, 299L), rows.stream().map(row -> row[1]).toList());
        var records = scanRecords();
        assertEquals(1, records.size());
        assertSummary(records.getFirst(), dataFile, 2, 4);
    }

    @Test
    void summarizesEarlyCloseOnceAndResetsCountersOnReopen() throws Exception {
        var engine = loadedEngine();
        var partitions = engine.requireTable("trips").files().getFirst().partitions();
        Path dataFile = engine.publishedDataFile("trips");
        var scan = new ScanOperator(dataFile, schema, partitions);
        scan.close();
        assertTrue(scanRecords().isEmpty());
        scan.open();
        try {
            assertEquals(12L, scan.next()[1]);
        } finally {
            scan.close();
        }
        scan.close();
        assertEquals(1, scanRecords().size());
        assertSummary(scanRecords().getFirst(), dataFile, 1, 1);

        assertEquals(8, drain(scan).size());
        scan.close();
        var records = scanRecords();
        assertEquals(2, records.size());
        assertSummary(records.getLast(), dataFile, 4, 8);
    }

    @Test
    void summarizesFailedOpenWithoutClaimingPartitionsRead() throws Exception {
        var engine = loadedEngine();
        var partitions = engine.requireTable("trips").files().getFirst().partitions();
        Path dataFile = engine.publishedDataFile("trips");
        Files.writeString(dataFile, "invalid");
        var scan = new ScanOperator(dataFile, schema, partitions);
        try {
            assertThrows(UncheckedIOException.class, scan::open);
        } finally {
            scan.close();
        }
        scan.close();
        var records = scanRecords();
        assertEquals(1, records.size());
        assertSummary(records.getFirst(), dataFile, 0, 0);
    }

    @Test
    void summarizesProgressBeforeAChunkReadFails() throws Exception {
        var engine = loadedEngine();
        var partitions = engine.requireTable("trips").files().getFirst().partitions();
        Path dataFile = engine.publishedDataFile("trips");
        try (var file = new RandomAccessFile(dataFile.toFile(), "rw")) {
            file.setLength(partitions.get(1).chunks().getFirst().offset());
        }
        var scan = new ScanOperator(dataFile, schema, partitions);
        scan.open();
        try {
            assertEquals(12L, scan.next()[1]);
            assertEquals(187L, scan.next()[1]);
            assertThrows(UncheckedIOException.class, scan::next);
        } finally {
            scan.close();
        }
        var records = scanRecords();
        assertEquals(1, records.size());
        assertSummary(records.getFirst(), dataFile, 1, 2);
    }

    @Test
    void returnsExactlyTheRowsOfHandedPartitions() {
        var engine = new StorageEngine(dir, 2);
        engine.createTable("trips", schema);
        engine.copyFile("trips", "src/test/resources/trips.csv");
        var table = engine.requireTable("trips");
        var partitions = table.files().getFirst().partitions();
        var dataFile = engine.publishedDataFile("trips");

        var first = drain(new ScanOperator(dataFile, schema, List.of(partitions.getFirst())));
        assertEquals(List.of(12L, 187L), first.stream().map(row -> row[1]).toList());

        var last = drain(new ScanOperator(dataFile, schema, List.of(partitions.getLast())));
        assertEquals(List.of(88L, 299L), last.stream().map(row -> row[1]).toList());
    }

    @Test
    void emptyPartitionListReadsNothingWithoutOpeningAFile() throws Exception {
        Path dataFile = dir.resolve("missing,\"é\n.bin");
        var scan = new ScanOperator(dataFile, schema, List.of());
        scan.open();
        assertNull(scan.next());
        scan.close();
        scan.close();
        assertTrue(Files.notExists(dataFile));
        var records = scanRecords();
        assertEquals(1, records.size());
        assertSummary(records.getFirst(), dataFile, 0, 0);
    }

    private StorageEngine loadedEngine() {
        var engine = new StorageEngine(dir, 2);
        engine.createTable("trips", schema);
        engine.copyFile("trips", "src/test/resources/trips.csv");
        return engine;
    }

    private List<String[]> scanRecords() throws Exception {
        return Files.readAllLines(Path.of("logs/engine.log")).stream()
                .map(line -> line.split(",", -1))
                .filter(fields -> fields.length >= 6 && fields[1].equals(session)
                        && fields[5].equals("ScanOperator"))
                .toList();
    }

    private void assertSummary(String[] fields, Path dataFile, int partitionsRead, long rowsOut) {
        assertEquals(7, fields.length);
        assertEquals("9", fields[2]);
        assertEquals("DEBUG", fields[4]);
        var message = fields[6].split(" durationMs=", -1);
        assertEquals(2, message.length);
        assertEquals("operation=scan file=" + StorageEngine.clean(dataFile)
                + " partitionsRead=" + partitionsRead + " rowsOut=" + rowsOut, message[0]);
        assertTrue(Long.parseLong(message[1]) >= 0);
        assertTrue(fields[6].chars().allMatch(character -> character < 128));
    }

    private static List<Object[]> drain(Operator operator) {
        operator.open();
        try {
            var rows = new ArrayList<Object[]>();
            Object[] row;
            while ((row = operator.next()) != null) {
                rows.add(row);
            }
            return rows;
        } finally {
            operator.close();
        }
    }
}

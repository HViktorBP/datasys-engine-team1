package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanOperatorTest {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

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
        var rows = drain(new ScanOperator(dir.resolve("missing.bin"), schema, List.of()));
        assertTrue(rows.isEmpty());
        assertTrue(Files.notExists(dir.resolve("missing.bin")));
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

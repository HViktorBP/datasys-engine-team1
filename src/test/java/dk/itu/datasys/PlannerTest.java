package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

class PlannerTest {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

    @Test
    void logsEveryUnfilteredPartitionBeforeOpeningData() throws Exception {
        var engine = new StorageEngine(dir, 2);
        engine.createTable("unfiltered,\"table\n", schema);
        engine.copyFile("unfiltered,\"table\n", "src/test/resources/trips.csv");
        Files.delete(engine.publishedDataFile("unfiltered,\"table\n"));
        String session = UUID.randomUUID().toString();
        var previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("sessionId", session);
            MDC.put("statementNumber", "7");
            var plan = new Planner(engine).plan(new SelectStatement("unfiltered,\"table\n", Optional.empty()));
            assertEquals(new ScanStats(4, 4, 0), plan.stats());
            var records = Files.readAllLines(Path.of("logs/engine.log")).stream()
                    .map(line -> line.split(",", -1))
                    .filter(fields -> fields[1].equals(session)).toList();
            assertEquals(4, records.size());
            for (int index = 0; index < records.size(); index++) {
                var fields = records.get(index);
                assertEquals(7, fields.length);
                assertEquals("7", fields[2]);
                assertEquals("DEBUG", fields[4]);
                assertEquals("Planner", fields[5]);
                assertEquals("table=unfiltered  table  partition=" + index
                        + " decision=READ reason=noPredicate", fields[6]);
            }
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    @Test
    void unfilteredEmptyTableHasNoPartitionDecisions() throws Exception {
        var engine = new StorageEngine(dir);
        engine.createTable("empty", schema);
        String session = UUID.randomUUID().toString();
        var previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("sessionId", session);
            MDC.put("statementNumber", "1");
            var plan = new Planner(engine).plan(new SelectStatement("empty", Optional.empty()));
            assertEquals(new ScanStats(0, 0, 0), plan.stats());
            assertTrue(Files.readAllLines(Path.of("logs/engine.log")).stream()
                    .noneMatch(line -> line.split(",", -1)[1].equals(session)));
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    @Test
    void prunesImpossiblePartitionsUsingCatalogStatistics() throws Exception {
        Path csv = dir.resolve("sorted.csv");
        Files.writeString(csv, """
                Copenhagen,12,23.5
                Roskilde,31,45.0
                Copenhagen,88,99.99
                Odense,95,120.75
                Copenhagen,140,210.0
                Aarhus,187,301.0
                Aalborg,210,340.5
                Esbjerg,299,450.25
                """);
        var engine = new StorageEngine(dir, 2);
        engine.createTable("trips", schema);
        engine.copyFile("trips", csv.toString());
        var planner = new Planner(engine);
        var plan = planner.plan(new SelectStatement("trips", Optional.of(
                new Predicate("distance", Comparison.GREATER_THAN, 200L))));
        assertEquals(new ScanStats(4, 1, 3), plan.stats());
    }

    @Test
    void buildsFilterOverScanForWhereAndBareScanWithoutWhere() {
        var engine = new StorageEngine(dir, 2);
        engine.createTable("trips", schema);
        engine.copyFile("trips", "src/test/resources/trips.csv");
        var planner = new Planner(engine);

        var filtered = planner.plan(new SelectStatement("trips", Optional.of(
                new Predicate("distance", Comparison.GREATER_THAN, 100L))));
        assertInstanceOf(FilterOperator.class, filtered.root());
        var unfiltered = planner.plan(new SelectStatement("trips", Optional.empty()));
        assertInstanceOf(ScanOperator.class, unfiltered.root());
        assertEquals(new ScanStats(4, 4, 0), unfiltered.stats());
    }
}

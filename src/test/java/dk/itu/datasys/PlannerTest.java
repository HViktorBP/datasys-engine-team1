package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
            var plan = new Planner(engine).plan(TestStatements.select("unfiltered,\"table\n", Optional.empty()));
            assertEquals(List.of(new ScanStats(4, 4, 0)), plan.stats());
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
            var plan = new Planner(engine).plan(TestStatements.select("empty", Optional.empty()));
            assertEquals(List.of(new ScanStats(0, 0, 0)), plan.stats());
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
        var plan = planner.plan(TestStatements.select("trips", Optional.of(
                new Predicate("distance", Comparison.GREATER_THAN, 200L))));
        assertEquals(List.of(new ScanStats(4, 1, 3)), plan.stats());

        var twoFilters = planner.plan((SelectStatement) new SqlParser().parse(
                "SELECT * FROM trips WHERE distance > 50 AND trips.distance < 200;").getFirst());
        assertEquals(List.of(new ScanStats(4, 2, 2)), twoFilters.stats());
        assertEquals(List.of(88L, 95L, 140L, 187L), distances(twoFilters.root()));
    }

    StorageEngine testbed(int maxRowsPerPartition) {
        var engine = new StorageEngine(dir, maxRowsPerPartition);
        engine.createTable("orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        engine.createTable("products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        engine.createTable("customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        engine.createTable("cities", List.of(
                new ColumnSpec("ct_city", ColumnType.STRING),
                new ColumnSpec("ct_country", ColumnType.STRING),
                new ColumnSpec("ct_population", ColumnType.LONG)));
        for (var table : List.of("orders", "products", "customers", "cities")) {
            engine.copyFile(table, "src/test/resources/joins/" + table + ".csv");
        }
        return engine;
    }

    /** Plans and drains a query, returning its rows and the join summaries it logged. */
    record Run(QueryPlan plan, List<Object[]> rows, List<String> joinLogs) { }

    Run run(StorageEngine engine, String sql) throws Exception {
        var statement = (SelectStatement) new SqlParser().parse(sql).getFirst();
        String session = UUID.randomUUID().toString();
        var previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("sessionId", session);
            var plan = new Planner(engine).plan(statement);
            var rows = HashJoinOperatorTest.drain(plan.root());
            var joinLogs = Files.readAllLines(Path.of("logs/engine.log")).stream()
                    .map(line -> line.split(",", -1))
                    .filter(fields -> fields[1].equals(session) && fields[5].equals("HashJoinOperator"))
                    .map(fields -> fields[6])
                    .toList();
            return new Run(plan, rows, joinLogs);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    @Test
    void joinsFourTablesLeftDeepInFromListOrder() throws Exception {
        var run = run(testbed(1000), """
                SELECT * FROM orders, customers, products, cities
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;""");
        assertInstanceOf(HashJoinOperator.class, run.plan().root());
        assertEquals(List.of(
                "tables=orders;customers rowsBuild=4 keys=3 rowsProbe=3 rowsOut=4",
                "tables=orders;customers;products rowsBuild=4 keys=2 rowsProbe=2 rowsOut=4",
                "tables=orders;customers;products;cities rowsBuild=4 keys=2 rowsProbe=2 rowsOut=4"),
                run.joinLogs());
        assertArrayEquals(new Object[]{100L, "Anna", "Bike", 1L, "Anna", "Copenhagen",
                        "Bike", 2999.0, "Copenhagen", "Denmark", 1400000L},
                run.rows().stream().filter(row -> row[0].equals(100L)).findFirst().orElseThrow());
        assertEquals(4, run.rows().size());
    }

    @Test
    void pushesFiltersBelowTheJoinsAndPrunesPerTable() throws Exception {
        var run = run(testbed(1), """
                SELECT * FROM orders, customers, products
                WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';""");
        assertEquals(List.of(new ScanStats(4, 4, 0), new ScanStats(3, 2, 1), new ScanStats(2, 2, 0)),
                run.plan().stats());
        assertEquals(List.of(
                "tables=orders;customers rowsBuild=4 keys=3 rowsProbe=2 rowsOut=3",
                "tables=orders;customers;products rowsBuild=3 keys=2 rowsProbe=2 rowsOut=3"),
                run.joinLogs());
        assertEquals(3, run.rows().size());
    }

    @Test
    void appliesFurtherConnectingPredicatesAsAFilterAboveTheJoin() throws Exception {
        var engine = testbed(1000);
        assertEquals(5, run(engine, "SELECT * FROM customers c1, customers c2 "
                + "WHERE c1.c_city = c2.c_city;").rows().size());
        var run = run(engine, "SELECT * FROM customers c1, customers c2 "
                + "WHERE c1.c_city = c2.c_city AND c2.c_name = c1.c_name;");
        assertInstanceOf(ColumnEqualityFilterOperator.class, run.plan().root());
        assertEquals(List.of("tables=c1;c2 rowsBuild=3 keys=2 rowsProbe=3 rowsOut=5"), run.joinLogs());
        assertEquals(3, run.rows().size());
        for (var row : run.rows()) {
            assertEquals(row[0], row[2]);
        }
    }

    @Test
    void rejectsATableWithoutAJoinPredicateToTheTablesBeforeIt() {
        var engine = testbed(1000);
        var statement = (SelectStatement) new SqlParser().parse("""
                SELECT * FROM orders, cities, customers
                WHERE o_custname = c_name AND c_city = ct_city;""").getFirst();
        var error = assertThrows(IllegalArgumentException.class, () -> new Planner(engine).plan(statement));
        assertEquals("No join predicate connects cities to the tables before it; reorder the FROM list",
                error.getMessage());
    }

    static List<Long> distances(Operator root) {
        var distances = new ArrayList<Long>();
        root.open();
        try {
            Object[] row;
            while ((row = root.next()) != null) {
                distances.add((Long) row[1]);
            }
        } finally {
            root.close();
        }
        return distances;
    }

    @Test
    void buildsFilterOverScanForWhereAndBareScanWithoutWhere() {
        var engine = new StorageEngine(dir, 2);
        engine.createTable("trips", schema);
        engine.copyFile("trips", "src/test/resources/trips.csv");
        var planner = new Planner(engine);

        var filtered = planner.plan(TestStatements.select("trips", Optional.of(
                new Predicate("distance", Comparison.GREATER_THAN, 100L))));
        assertInstanceOf(FilterOperator.class, filtered.root());
        var unfiltered = planner.plan(TestStatements.select("trips", Optional.empty()));
        assertInstanceOf(ScanOperator.class, unfiltered.root());
        assertEquals(List.of(new ScanStats(4, 4, 0)), unfiltered.stats());
    }
}

package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlannerTest {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(
            new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG),
            new ColumnSpec("price", ColumnType.DOUBLE));

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
        filtered.root().open();
        try {
            assertInstanceOf(ScanOperator.class, ((FilterOperator) filtered.root()).child());
        } finally {
            filtered.root().close();
        }

        var unfiltered = planner.plan(new SelectStatement("trips", Optional.empty()));
        assertInstanceOf(ScanOperator.class, unfiltered.root());
        assertEquals(new ScanStats(4, 4, 0), unfiltered.stats());
    }
}

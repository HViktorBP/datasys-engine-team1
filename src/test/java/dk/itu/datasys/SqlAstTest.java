package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlAstTest {
    @Test void recordsProvideStructuralEquality() {
        var predicate = new Predicate("distance", Comparison.GREATER_THAN, 100L);
        assertEquals(new SelectStatement("trips", Optional.of(predicate)),
                new SelectStatement("trips", Optional.of(predicate)));
        assertEquals(new CopyStatement("trips", "trips.csv"),
                new CopyStatement("trips", "trips.csv"));
    }

    @Test void createTableOwnsAnImmutableColumnList() {
        var source = new ArrayList<>(List.of(new ColumnSpec("city", ColumnType.STRING)));
        var statement = new CreateTableStatement("trips", source);
        source.clear();
        assertEquals(List.of(new ColumnSpec("city", ColumnType.STRING)), statement.columns());
        assertThrows(UnsupportedOperationException.class,
                () -> statement.columns().add(new ColumnSpec("distance", ColumnType.LONG)));
    }
}

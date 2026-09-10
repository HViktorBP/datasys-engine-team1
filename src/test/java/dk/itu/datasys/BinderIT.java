package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinderIT {
    @TempDir Path dir;
    StorageEngine engine;
    Binder binder;

    @BeforeEach void createCatalog() {
        engine = new StorageEngine(dir);
        engine.createTable("trips", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("distance", ColumnType.LONG),
                new ColumnSpec("price", ColumnType.DOUBLE)));
        binder = new Binder(engine);
    }

    @Test void bindsEveryValidStatementShape() {
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("new_table",
                List.of(new ColumnSpec("id", ColumnType.LONG)))));
        assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "missing.csv")));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips",
                Optional.empty())));
        assertDoesNotThrow(() -> binder.bind(new SelectStatement("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 10L)))));
    }

    @Test void rejectsUnknownTablesAndColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CopyStatement("missing", "input.csv")));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("missing", Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips", Optional.of(
                        new Predicate("missing", Comparison.EQUALS, 1L)))));
    }

    @Test void rejectsMismatchedConstants() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.EQUALS, "x")))));
    }

    @Test void validatesCreateColumnsButDefersExistingTableChecks() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("empty", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CreateTableStatement("duplicate", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("city", ColumnType.LONG)))));
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("trips",
                List.of(new ColumnSpec("id", ColumnType.LONG)))));
    }

    @Test void matchesIdentifiersCaseSensitively() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("Trips", Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new SelectStatement("trips", Optional.of(
                        new Predicate("Distance", Comparison.EQUALS, 1L)))));
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("mixed", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("City", ColumnType.STRING)))));
    }
}

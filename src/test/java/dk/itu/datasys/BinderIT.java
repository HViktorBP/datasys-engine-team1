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
        engine.createTable("orders", List.of(
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        engine.createTable("customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        engine.createTable("products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        engine.createTable("t1", List.of(new ColumnSpec("id", ColumnType.LONG)));
        engine.createTable("t2", List.of(new ColumnSpec("id", ColumnType.LONG)));
        binder = new Binder(engine);
    }

    void bindSql(String sql) {
        binder.bind(new SqlParser().parse(sql).getFirst());
    }

    void assertRejected(String message, String sql) {
        var error = assertThrows(IllegalArgumentException.class, () -> bindSql(sql));
        assertEquals(message, error.getMessage());
    }

    @Test void bindsMultiTableSelectsWithFiltersJoinsAndAliases() {
        assertDoesNotThrow(() -> bindSql("SELECT * FROM orders, customers, products "
                + "WHERE o_custname = c_name AND o_prodname = p_name AND c_city = 'Copenhagen';"));
        assertDoesNotThrow(() -> bindSql("SELECT * FROM customers c1, customers c2 "
                + "WHERE c1.c_city = c2.c_city AND c1.c_name = c2.c_name;"));
        assertDoesNotThrow(() -> bindSql("SELECT * FROM t1, t2 WHERE t1.id = t2.id AND t2.id > 3;"));
    }

    @Test void rejectsBadNamesAcrossTables() {
        assertRejected("Unknown column: c_namee",
                "SELECT * FROM orders, customers WHERE o_custname = c_namee;");
        assertRejected("Ambiguous column: id; qualify it with a table name or alias",
                "SELECT * FROM t1, t2 WHERE id = 1;");
        assertRejected("Unknown table or alias: t3", "SELECT * FROM t1, t2 WHERE t3.id = 1;");
        assertRejected("Duplicate table name or alias in FROM: customers; give each occurrence a distinct alias",
                "SELECT * FROM customers, customers;");
        assertRejected("Unknown table: missing", "SELECT * FROM orders, missing;");
    }

    @Test void rejectsJoinsWithinOneTableOrAcrossTypes() {
        assertRejected("Join needs two tables: o_custname = o_prodname",
                "SELECT * FROM orders, customers WHERE o_custname = o_prodname;");
        assertRejected("Join needs two tables: c1.c_name = c1.c_city",
                "SELECT * FROM customers c1, customers c2 WHERE c1.c_name = c1.c_city;");
        assertRejected("Join needs two columns of one type: o_quantity = p_price",
                "SELECT * FROM orders, products WHERE o_quantity = p_price;");
        assertRejected("Invalid constant type for STRING",
                "SELECT * FROM orders, customers WHERE o_custname = c_name AND c_city = 1;");
    }

    @Test void deleteAcceptsItsOwnTableAsQualifier() {
        assertDoesNotThrow(() -> bindSql("DELETE FROM trips WHERE trips.distance > 5;"));
        assertRejected("Unknown table or alias: t", "DELETE FROM trips WHERE t.distance > 5;");
    }

    @Test void bindsEveryValidStatementShape() {
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("new_table",
                List.of(new ColumnSpec("id", ColumnType.LONG)))));
        assertDoesNotThrow(() -> binder.bind(new CopyStatement("trips", "missing.csv")));
        assertDoesNotThrow(() -> binder.bind(TestStatements.select("trips",
                Optional.empty())));
        assertDoesNotThrow(() -> binder.bind(TestStatements.select("trips",
                Optional.of(new Predicate("distance", Comparison.GREATER_THAN, 10L)))));
    }

    @Test void rejectsUnknownTablesAndColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(new CopyStatement("missing", "input.csv")));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(TestStatements.select("missing", Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(TestStatements.select("trips", Optional.of(
                        new Predicate("missing", Comparison.EQUALS, 1L)))));
    }

    @Test void rejectsMismatchedConstants() {
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(TestStatements.select("trips", Optional.of(
                        new Predicate("distance", Comparison.EQUALS, "x")))));
    }

    @Test void rejectsNullPublicApiInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Binder(null));
        assertThrows(IllegalArgumentException.class, () -> binder.bind(null));
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
                () -> binder.bind(TestStatements.select("Trips", Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> binder.bind(TestStatements.select("trips", Optional.of(
                        new Predicate("Distance", Comparison.EQUALS, 1L)))));
        assertDoesNotThrow(() -> binder.bind(new CreateTableStatement("mixed", List.of(
                new ColumnSpec("city", ColumnType.STRING),
                new ColumnSpec("City", ColumnType.STRING)))));
    }
}

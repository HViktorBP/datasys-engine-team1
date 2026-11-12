package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dk.itu.datasys.ColumnResolver.Resolved;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColumnResolverTest {
    @TempDir Path dir;
    StorageEngine engine;

    @BeforeEach void createTestbedSchemas() {
        engine = new StorageEngine(dir);
        engine.createTable("orders", List.of(
                new ColumnSpec("o_orderkey", ColumnType.LONG),
                new ColumnSpec("o_custname", ColumnType.STRING),
                new ColumnSpec("o_prodname", ColumnType.STRING),
                new ColumnSpec("o_quantity", ColumnType.LONG)));
        engine.createTable("customers", List.of(
                new ColumnSpec("c_name", ColumnType.STRING),
                new ColumnSpec("c_city", ColumnType.STRING)));
        engine.createTable("products", List.of(
                new ColumnSpec("p_name", ColumnType.STRING),
                new ColumnSpec("p_price", ColumnType.DOUBLE)));
        engine.createTable("cities", List.of(
                new ColumnSpec("ct_city", ColumnType.STRING),
                new ColumnSpec("ct_country", ColumnType.STRING),
                new ColumnSpec("ct_population", ColumnType.LONG)));
    }

    ColumnResolver resolver(TableRef... tables) {
        return new ColumnResolver(List.of(tables), engine.requireTables(List.of(tables)));
    }

    static ColumnRef qualified(String qualifier, String name) {
        return new ColumnRef(Optional.of(qualifier), name);
    }

    @Test void mapsNamesToTheirTableAndJoinedRowPosition() {
        var resolver = resolver(new TableRef("orders"), new TableRef("customers"));
        assertEquals(new Resolved(0, 1, 1, ColumnType.STRING), resolver.resolve(new ColumnRef("o_custname")));
        assertEquals(new Resolved(1, 0, 4, ColumnType.STRING), resolver.resolve(new ColumnRef("c_name")));
        assertEquals(new Resolved(1, 1, 5, ColumnType.STRING), resolver.resolve(new ColumnRef("c_city")));
    }

    @Test void offsetsFollowTheFromListOrder() {
        var resolver = resolver(new TableRef("orders"), new TableRef("customers"),
                new TableRef("products"), new TableRef("cities"));
        assertEquals(0, resolver.resolve(new ColumnRef("o_orderkey")).rowIndex());
        assertEquals(4, resolver.resolve(new ColumnRef("c_name")).rowIndex());
        assertEquals(6, resolver.resolve(new ColumnRef("p_name")).rowIndex());
        assertEquals(new Resolved(3, 2, 10, ColumnType.LONG), resolver.resolve(new ColumnRef("ct_population")));

        var reversed = resolver(new TableRef("cities"), new TableRef("orders"));
        assertEquals(new Resolved(1, 0, 3, ColumnType.LONG), reversed.resolve(new ColumnRef("o_orderkey")));
    }

    @Test void resolvesQualifiedNamesThroughTableNamesAndAliases() {
        var resolver = resolver(new TableRef("orders"), new TableRef("customers", Optional.of("c")));
        assertEquals(new Resolved(0, 3, 3, ColumnType.LONG), resolver.resolve(qualified("orders", "o_quantity")));
        assertEquals(new Resolved(1, 1, 5, ColumnType.STRING), resolver.resolve(qualified("c", "c_city")));
    }

    @Test void resolvesBothSidesOfASelfJoin() {
        var resolver = resolver(new TableRef("customers", Optional.of("c1")),
                new TableRef("customers", Optional.of("c2")));
        assertEquals(new Resolved(0, 1, 1, ColumnType.STRING), resolver.resolve(qualified("c1", "c_city")));
        assertEquals(new Resolved(1, 1, 3, ColumnType.STRING), resolver.resolve(qualified("c2", "c_city")));
        assertError("Ambiguous column: c_city", () -> resolver.resolve(new ColumnRef("c_city")));
    }

    @Test void rejectsUnknownAndAmbiguousNames() {
        var resolver = resolver(new TableRef("orders"), new TableRef("customers"));
        assertError("Unknown column: c_namee", () -> resolver.resolve(new ColumnRef("c_namee")));
        assertError("Unknown column: C_NAME", () -> resolver.resolve(new ColumnRef("C_NAME")));
        assertError("Unknown column: orders.c_name", () -> resolver.resolve(qualified("orders", "c_name")));
        assertError("Unknown table or alias: products", () -> resolver.resolve(qualified("products", "p_name")));
    }

    @Test void anAliasHidesItsTableName() {
        var resolver = resolver(new TableRef("customers", Optional.of("c1")));
        assertError("Unknown table or alias: customers", () -> resolver.resolve(qualified("customers", "c_name")));
    }

    @Test void rejectsDuplicateExposedNamesIgnoringCase() {
        assertError("Duplicate table name or alias in FROM: customers",
                () -> resolver(new TableRef("customers"), new TableRef("customers")));
        assertError("Duplicate table name or alias in FROM: C",
                () -> resolver(new TableRef("customers", Optional.of("c")),
                        new TableRef("customers", Optional.of("C"))));
        assertError("Duplicate table name or alias in FROM: orders",
                () -> resolver(new TableRef("orders"), new TableRef("customers", Optional.of("orders"))));
    }

    static void assertError(String message, Runnable action) {
        var error = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(error.getMessage().startsWith(message), error.getMessage());
    }
}

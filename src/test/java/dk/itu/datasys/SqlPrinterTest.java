package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlPrinterTest {
    final SqlParser parser = new SqlParser();
    final SqlPrinter printer = new SqlPrinter();

    @Test void printsAndRoundTripsEveryStatementShape() {
        var statements = List.<Statement>of(
                new CreateTableStatement("Trips", List.of(
                        new ColumnSpec("City", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new CopyStatement("Trips", "input.csv"),
                new SelectStatement("Trips", Optional.empty()),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("distance", Comparison.GREATER_THAN, 100L))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("City", Comparison.EQUALS, "Odense"))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.LESS_THAN, -1.5))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e20))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e-7))),
                new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, -0.0))));

        for (var statement : statements) {
            assertEquals(List.of(statement), parser.parse(printer.print(statement)));
        }
    }

    @Test void emitsStableNormalizedSql() {
        assertEquals("CREATE TABLE trips (city STRING, distance LONG);",
                printer.print(new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG)))));
        assertEquals("COPY trips FROM 'trips.csv';",
                printer.print(new CopyStatement("trips", "trips.csv")));
        assertEquals("SELECT * FROM trips;",
                printer.print(new SelectStatement("trips", Optional.empty())));
        assertEquals("SELECT * FROM trips WHERE distance = 12;",
                printer.print(new SelectStatement("trips", Optional.of(
                        new Predicate("distance", Comparison.EQUALS, 12L)))));
    }

    @Test void emitsPlainDecimalsForExponentAndSignedZeroValues() {
        assertEquals("SELECT * FROM Trips WHERE price = 100000000000000000000.0;",
                printer.print(new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e20)))));
        assertEquals("SELECT * FROM Trips WHERE price = 0.00000010;",
                printer.print(new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e-7)))));
        assertEquals("SELECT * FROM Trips WHERE price = -0.0;",
                printer.print(new SelectStatement("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, -0.0)))));
    }

    @Test void rejectsValuesThatCannotBePrintedAsRestrictedSql() {
        assertThrows(IllegalArgumentException.class,
                () -> printer.print(new CopyStatement("trips", "bad'name.csv")));
        assertThrows(IllegalArgumentException.class,
                () -> printer.print(new SelectStatement("trips", Optional.of(
                        new Predicate("city", Comparison.EQUALS, "line\nbreak")))));
        assertThrows(IllegalArgumentException.class,
                () -> printer.print(new SelectStatement("trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, Double.POSITIVE_INFINITY)))));
        assertThrows(IllegalArgumentException.class,
                () -> printer.print(new SelectStatement("trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, Double.NaN)))));
    }

    @Test void rejectsNullStatement() {
        assertThrows(IllegalArgumentException.class, () -> printer.print(null));
    }

    @Test void rejectsNullCopyPath() {
        assertThrows(IllegalArgumentException.class,
                () -> printer.print(new CopyStatement("trips", null)));
    }

    @Test void rejectsNullPredicateConstant() {
        assertThrows(IllegalArgumentException.class,
                () -> printer.print(new SelectStatement("trips", Optional.of(
                        new Predicate("city", Comparison.EQUALS, null)))));
    }

    @Test void rejectsMalformedTableNamesInEveryStatementShape() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new CreateTableStatement("123trips",
                                List.of(new ColumnSpec("city", ColumnType.STRING))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new CopyStatement("bad-name", "trips.csv"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new SelectStatement("has space", Optional.empty()))));
    }

    @Test void rejectsReservedTableNamesCaseInsensitively() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new CreateTableStatement("select",
                                List.of(new ColumnSpec("city", ColumnType.STRING))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new CopyStatement("FROM", "trips.csv"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new SelectStatement("String", Optional.empty()))));
    }

    @Test void rejectsInvalidAndReservedColumnNames() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new CreateTableStatement("trips",
                                List.of(new ColumnSpec("bad-name", ColumnType.STRING))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new CreateTableStatement("trips",
                                List.of(new ColumnSpec("WHERE", ColumnType.STRING))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new SelectStatement("trips", Optional.of(
                                new Predicate("123city", Comparison.EQUALS, "Odense"))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> printer.print(new SelectStatement("trips", Optional.of(
                                new Predicate("double", Comparison.EQUALS, 1.0))))));
    }
}

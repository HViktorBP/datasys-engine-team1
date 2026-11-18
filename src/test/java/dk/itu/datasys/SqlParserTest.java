package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlParserTest {
    final SqlParser parser = new SqlParser();

    @Test void parsesEveryStatementShape() {
        assertEquals(List.of(new CreateTableStatement("trips", List.of(
                        new ColumnSpec("city", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE)))),
                parser.parse("CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);"));
        assertEquals(List.of(new CopyStatement("trips", "trips.csv")),
                parser.parse("COPY trips FROM 'trips.csv';"));
        assertEquals(List.of(TestStatements.select("trips", Optional.empty())),
                parser.parse("SELECT * FROM trips;"));
        assertEquals(List.of(TestStatements.select("trips", Optional.of(
                        new Predicate("distance", Comparison.GREATER_THAN, 100L)))),
                parser.parse("SELECT * FROM trips WHERE distance > 100;"));
    }

    @Test void parsesTypedPositiveAndNegativeLiterals() {
        var statements = parser.parse("""
                SELECT * FROM trips WHERE distance = 12;
                SELECT * FROM trips WHERE price = 12.0;
                SELECT * FROM trips WHERE city = '12';
                SELECT * FROM trips WHERE distance = -1;
                SELECT * FROM trips WHERE price = -1.5;
                """);
        assertEquals(List.of(12L, 12.0, "12", -1L, -1.5),
                statements.stream()
                        .map(SelectStatement.class::cast)
                        .map(select -> select.filters().getFirst())
                        .map(Predicate::constant)
                        .toList());
    }

    @Test void treatsKeywordsCaseInsensitivelyAndPreservesIdentifiers() {
        assertEquals(List.of(TestStatements.select("Trips_2026", Optional.of(
                        new Predicate("Distance", Comparison.LESS_THAN, 5L)))),
                parser.parse("select * from Trips_2026 where Distance < 5;"));
    }

    @Test void skipsCommentsAndWhitespaceAcrossAWholeScript() {
        assertEquals(List.of(
                        TestStatements.select("trips", Optional.empty()),
                        new CopyStatement("trips", "trips.csv")),
                parser.parse("""
                        -- first statement
                          SELECT * FROM trips; -- trailing comment

                        COPY trips
                        FROM 'trips.csv';
                        """));
    }

    @Test void rejectsMalformedInputAtTheFirstError() {
        assertParseError("SELECT * FROM trips", 1, 19);
        assertParseError("CREATE TABLE trips (city STRING;", 1, 31);
        assertParseError("CREATE TABLE trips (city TEXT);", 1, 25);
        assertParseError("COPY trips FROM 'trips.csv;", 1, 16);
        assertParseError("SELECT * trips;", 1, 9);
    }

    @Test void rejectsEmptyScriptsAndNonAsciiStrings() {
        assertParseError("-- no statements\n", 2, 0);
        assertParseError("COPY trips FROM 'ø.csv';", 1, 16);
        assertParseError("COPY trips FROM '😀.csv';", 1, 16);
    }

    @Test void sortsConditionsIntoFiltersAndJoinsInSourceOrder() {
        assertEquals(List.of(new SelectStatement(
                        List.of(new TableRef("orders"), new TableRef("customers"),
                                new TableRef("products"), new TableRef("cities")),
                        List.of(new Predicate("c_city", Comparison.EQUALS, "Copenhagen"),
                                new Predicate("o_quantity", Comparison.GREATER_THAN, 1L)),
                        List.of(new JoinPredicate("o_custname", "c_name"),
                                new JoinPredicate("o_prodname", "p_name"),
                                new JoinPredicate("c_city", "ct_city")))),
                parser.parse("""
                        SELECT * FROM orders, customers, products, cities
                        WHERE o_custname = c_name AND c_city = 'Copenhagen'
                          and o_prodname = p_name AND o_quantity > 1 AND c_city = ct_city;
                        """));
    }

    @Test void parsesAliasesAndQualifiedColumns() {
        var c1 = Optional.of("c1");
        var c2 = Optional.of("c2");
        assertEquals(List.of(new SelectStatement(
                        List.of(new TableRef("customers", c1), new TableRef("customers", c2)),
                        List.of(new Predicate(new ColumnRef(c1, "c_name"), Comparison.EQUALS, "Anna")),
                        List.of(new JoinPredicate(new ColumnRef(c1, "c_city"), new ColumnRef(c2, "c_city"))))),
                parser.parse("SELECT * FROM customers c1, customers c2 "
                        + "WHERE c1.c_city = c2.c_city AND c1.c_name = 'Anna';"));
        assertEquals(List.of(new DeleteStatement("trips", Optional.of(new Predicate(
                        new ColumnRef(Optional.of("trips"), "distance"), Comparison.GREATER_THAN, 5L)))),
                parser.parse("DELETE FROM trips WHERE trips.distance > 5;"));
    }

    @Test void readsQualifiedNamesAsThreeTokensNextToDecimals() {
        assertEquals(List.of(TestStatements.select("t", Optional.of(new Predicate(
                        new ColumnRef(Optional.of("t"), "x1"), Comparison.EQUALS, 1.5)))),
                parser.parse("SELECT * FROM t WHERE t . x1 = 1.5;"));
    }

    @Test void rejectsOrParenthesesAndJoinsOutsideSelect() {
        assertParseError("SELECT * FROM a, b WHERE x = y OR z = 1;", 1, 31);
        assertParseError("SELECT * FROM a WHERE (x = 1);", 1, 22);
        assertParseError("DELETE FROM a WHERE x = y;", 1, 24);
        assertParseError("SELECT * FROM a WHERE x < y;", 1, 26);
        assertParseError("SELECT * FROM and;", 1, 14);
    }

    private void assertParseError(String sql, int line, int column) {
        var error = assertThrows(SqlParseException.class, () -> parser.parse(sql));
        assertEquals(line, error.line());
        assertEquals(column, error.column());
    }
}

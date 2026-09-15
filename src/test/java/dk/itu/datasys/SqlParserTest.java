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
        assertEquals(List.of(new SelectStatement("trips", Optional.empty())),
                parser.parse("SELECT * FROM trips;"));
        assertEquals(List.of(new SelectStatement("trips", Optional.of(
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
                        .map(SelectStatement::where)
                        .map(Optional::orElseThrow)
                        .map(Predicate::constant)
                        .toList());
    }

    @Test void treatsKeywordsCaseInsensitivelyAndPreservesIdentifiers() {
        assertEquals(List.of(new SelectStatement("Trips_2026", Optional.of(
                        new Predicate("Distance", Comparison.LESS_THAN, 5L)))),
                parser.parse("select * from Trips_2026 where Distance < 5;"));
    }

    @Test void skipsCommentsAndWhitespaceAcrossAWholeScript() {
        assertEquals(List.of(
                        new SelectStatement("trips", Optional.empty()),
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

    private void assertParseError(String sql, int line, int column) {
        var error = assertThrows(SqlParseException.class, () -> parser.parse(sql));
        assertEquals(line, error.line());
        assertEquals(column, error.column());
    }
}

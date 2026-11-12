package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqlPrinterTest {
    final SqlParser parser = new SqlParser();

    @Test void roundTripsEveryStatementShape() {
        var customers = new TableRef("customers", Optional.of("c1"));
        var statements = List.<Statement>of(
                new CreateTableStatement("Trips", List.of(
                        new ColumnSpec("City", ColumnType.STRING),
                        new ColumnSpec("distance", ColumnType.LONG),
                        new ColumnSpec("price", ColumnType.DOUBLE))),
                new CopyStatement("Trips", "input.csv"),
                new DeleteStatement("Trips", Optional.empty()),
                new DeleteStatement("Trips", Optional.of(new Predicate(
                        new ColumnRef(Optional.of("Trips"), "distance"), Comparison.LESS_THAN, 5L))),
                TestStatements.select("Trips", Optional.empty()),
                TestStatements.select("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e20))),
                TestStatements.select("Trips", Optional.of(
                        new Predicate("price", Comparison.EQUALS, 1.0e-7))),
                TestStatements.select("Trips", Optional.of(
                        new Predicate("price", Comparison.GREATER_THAN, -0.0))),
                new SelectStatement(
                        List.of(new TableRef("orders"), new TableRef("customers"), new TableRef("products")),
                        List.of(new Predicate("c_city", Comparison.EQUALS, "Copenhagen"),
                                new Predicate("o_quantity", Comparison.GREATER_THAN, 1L)),
                        List.of(new JoinPredicate("o_custname", "c_name"),
                                new JoinPredicate("o_prodname", "p_name"))),
                new SelectStatement(
                        List.of(customers, new TableRef("customers", Optional.of("c2"))),
                        List.of(new Predicate(new ColumnRef(Optional.of("c1"), "c_name"),
                                Comparison.LESS_THAN, "M")),
                        List.of(new JoinPredicate(new ColumnRef(Optional.of("c1"), "c_city"),
                                new ColumnRef(Optional.of("c2"), "c_city")))));

        for (var statement : statements) {
            assertEquals(List.of(statement), parser.parse(SqlPrinter.print(statement)));
        }
    }

    @Test void printsJoinsBeforeFiltersAndAliasesAfterTables() {
        var statement = parser.parse("""
                select * from orders o, customers
                where c_city = 'Aarhus' and o.o_custname = c_name;
                """).getFirst();
        assertEquals("SELECT * FROM orders o, customers WHERE o.o_custname = c_name"
                + " AND c_city = 'Aarhus';", SqlPrinter.print(statement));
    }

    @Test void printsDoublesWithoutExponents() {
        assertEquals("SELECT * FROM t WHERE x = 100000000000000000000.0;",
                SqlPrinter.print(TestStatements.select("t", Optional.of(
                        new Predicate("x", Comparison.EQUALS, 1.0e20)))));
        assertEquals("SELECT * FROM t WHERE x = 0.00000010;",
                SqlPrinter.print(TestStatements.select("t", Optional.of(
                        new Predicate("x", Comparison.EQUALS, 1.0e-7)))));
    }
}

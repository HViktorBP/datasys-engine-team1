package dk.itu.datasys;

import java.util.List;
import java.util.Optional;

/**
 * Builds statements in the single-table shape that tests from before Exercise 10 use.
 */
final class TestStatements {
    private TestStatements() { }

    /**
     * Builds {@code SELECT * FROM table [WHERE filter]}.
     *
     * @param table the only table
     * @param where the optional filter
     * @return the select statement
     */
    static SelectStatement select(String table, Optional<Predicate> where) {
        return new SelectStatement(List.of(new TableRef(table)), where.stream().toList(), List.of());
    }
}

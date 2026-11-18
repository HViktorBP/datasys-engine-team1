package dk.itu.datasys;

import java.util.List;

/**
 * Represents a {@code SELECT *} statement over one or more tables.
 *
 * <p>The {@code WHERE} clause is a conjunction. The parser sorts each condition into one of two
 * lists: a column compared with a constant is a filter, a column compared with a column is a join.
 *
 * @param tables the {@code FROM} list in source order
 * @param filters the column-to-constant conditions in source order
 * @param joins the column-to-column conditions in source order
 * @author Team 1
 * @version 0.10
 * @since 0.3
 */
public record SelectStatement(List<TableRef> tables, List<Predicate> filters,
                              List<JoinPredicate> joins) implements Statement {
    /**
     * Creates a select statement that owns immutable copies of its lists.
     *
     * @param tables the {@code FROM} list
     * @param filters the filters
     * @param joins the join predicates
     * @throws NullPointerException if a list or one of its elements is null
     * @throws IllegalArgumentException if {@code tables} is empty
     * @since 0.10
     */
    public SelectStatement {
        tables = List.copyOf(tables);
        filters = List.copyOf(filters);
        joins = List.copyOf(joins);
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("SELECT needs at least one table");
        }
    }
}

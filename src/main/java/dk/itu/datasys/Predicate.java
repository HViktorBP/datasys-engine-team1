package dk.itu.datasys;

import java.util.Objects;

/**
 * Represents a typed comparison of a column with a constant, such as {@code c_city = 'Aarhus'}.
 *
 * @param column the column to compare
 * @param comparison the comparison operator
 * @param constant the typed comparison value
 * @author Team 1
 * @version 0.10
 * @since 0.3
 */
public record Predicate(ColumnRef column, Comparison comparison, Object constant) {
    /**
     * Creates a predicate.
     *
     * @param column the column to compare
     * @param comparison the comparison operator
     * @param constant the typed comparison value
     * @throws NullPointerException if {@code column} is null
     * @since 0.10
     */
    public Predicate {
        Objects.requireNonNull(column, "column");
    }

    /**
     * Creates a predicate on an unqualified column.
     *
     * @param columnName the column to compare
     * @param comparison the comparison operator
     * @param constant the typed comparison value
     * @throws NullPointerException if {@code columnName} is null
     * @since 0.10
     */
    public Predicate(String columnName, Comparison comparison, Object constant) {
        this(new ColumnRef(columnName), comparison, constant);
    }
}

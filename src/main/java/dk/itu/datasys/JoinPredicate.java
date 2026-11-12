package dk.itu.datasys;

import java.util.Objects;

/**
 * Represents an equi-join condition that compares a column with a column, such as
 * {@code o_custname = c_name}.
 *
 * @param left the column before {@code =}
 * @param right the column after {@code =}
 * @author Team 1
 * @version 0.10
 * @since 0.10
 */
public record JoinPredicate(ColumnRef left, ColumnRef right) {
    /**
     * Creates a join predicate.
     *
     * @param left the left column
     * @param right the right column
     * @throws NullPointerException if either column is null
     */
    public JoinPredicate {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
    }

    /**
     * Creates a join predicate between two unqualified columns.
     *
     * @param leftColumn the left column name
     * @param rightColumn the right column name
     * @throws NullPointerException if either name is null
     */
    public JoinPredicate(String leftColumn, String rightColumn) {
        this(new ColumnRef(leftColumn), new ColumnRef(rightColumn));
    }

    /**
     * Returns the condition as written in SQL, such as {@code o_custname = c_name}.
     *
     * @return the condition text used in error messages
     */
    @Override
    public String toString() {
        return left + " = " + right;
    }
}

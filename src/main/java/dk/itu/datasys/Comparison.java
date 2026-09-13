package dk.itu.datasys;

/**
 * Defines the comparison operators supported by predicates.
 *
 * @author Team 1
 * @version 0.3
 * @since 0.2
 */
public enum Comparison {
    /** Matches values equal to the predicate constant. */
    EQUALS,

    /** Matches values less than the predicate constant. */
    LESS_THAN,

    /** Matches values greater than the predicate constant. */
    GREATER_THAN;

    /**
     * Tests the result of comparing a candidate value with a predicate constant.
     *
     * @param order a negative value, zero, or a positive value from the comparison
     * @return {@code true} if the comparison result satisfies this operator
     */
    boolean matches(int order) {
        return switch (this) {
            case EQUALS -> order == 0;
            case LESS_THAN -> order < 0;
            case GREATER_THAN -> order > 0;
        };
    }
}

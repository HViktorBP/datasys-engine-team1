package dk.itu.datasys;

/**
 * Determines whether catalog-only min/max statistics prove that a predicate cannot match a row.
 *
 * <p>For the inclusive range {@code [min, max]}, equality prunes when the constant lies outside
 * the range, less-than prunes when {@code min >= constant}, and greater-than prunes when
 * {@code max <= constant}. All other cases are read because the statistics cannot prove absence.
 */
final class PartitionPruner {
    /** Prevents utility-class instantiation. */
    private PartitionPruner() { }

    /**
     * Tests whether a partition can be skipped for a comparison predicate.
     *
     * @param type the type of the predicate column
     * @param statistics the partition's minimum and maximum values
     * @param comparison the predicate operator
     * @param constant the typed predicate constant
     * @return {@code true} if the statistics prove that the partition contains no match
     */
    static boolean canPrune(ColumnType type, PartitionStatistics statistics,
                            Comparison comparison, Object constant) {
        int minOrder = type.compare(type.parse(statistics.min()), constant);
        int maxOrder = type.compare(type.parse(statistics.max()), constant);
        return switch (comparison) {
            case EQUALS -> minOrder > 0 || maxOrder < 0;
            case LESS_THAN -> minOrder >= 0;
            case GREATER_THAN -> maxOrder <= 0;
        };
    }
}

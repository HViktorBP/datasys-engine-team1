package dk.itu.datasys;

import java.util.List;

/**
 * Catalog-only cardinality arithmetic for the join estimator.
 *
 * <p>Formulas and sources are recorded in {@code docs/optimizer-design.md}. Integer division
 * never estimates {@code 0} from a positive input. A zero input stays {@code 0}.
 *
 * @author Team 1
 * @version 0.11
 * @since 0.11
 */
final class Cardinality {
    /** Stand-in NDV for a partition written before distinct counts were collected. */
    private static final long MISSING_NDV = 1L;

    /** Prevents instantiation of the estimator helpers. */
    private Cardinality() { }

    /**
     * Returns the NDV substituted for a catalog chunk that has none.
     *
     * @return {@code 1}, the conservative stand-in that overestimates join size
     * @since 0.11
     */
    static long missingNdv() {
        return MISSING_NDV;
    }

    /**
     * Merges per-partition NDVs by taking the maximum, a lower bound of the table NDV.
     *
     * @param partitionNdvs the exact distinct counts of the surviving partitions
     * @return the largest partition NDV
     * @throws IllegalArgumentException if the list is {@code null}, empty, or contains a
     *                                  {@code null} or non-positive value
     * @since 0.11
     */
    static long tableNdv(List<Long> partitionNdvs) {
        if (partitionNdvs == null || partitionNdvs.isEmpty()) {
            throw new IllegalArgumentException("partition NDVs are required");
        }
        long max = 0;
        for (Long ndv : partitionNdvs) {
            if (ndv == null || ndv < 1) {
                throw new IllegalArgumentException("partition NDV must be at least 1");
            }
            max = Math.max(max, ndv);
        }
        return max;
    }

    /**
     * Returns the estimated rows of a table after partition pruning.
     *
     * @param survivingRows the sum of {@code rowCount} over partitions that survive pruning
     * @return {@code survivingRows}
     * @throws IllegalArgumentException if {@code survivingRows} is negative
     * @since 0.11
     */
    static long baseTable(long survivingRows) {
        if (survivingRows < 0) {
            throw new IllegalArgumentException("surviving rows cannot be negative");
        }
        return survivingRows;
    }

    /**
     * Estimates an equality filter under uniformity: about {@code inputRows / ndv} rows survive.
     *
     * @param inputRows the estimated input cardinality
     * @param ndv the distinct-value count of the filtered column
     * @return {@code 0} when the input is empty, otherwise at least {@code 1}
     * @since 0.11
     */
    static long equalityFilter(long inputRows, long ndv) {
        if (inputRows <= 0) {
            return 0;
        }
        return Math.max(1, inputRows / Math.max(MISSING_NDV, ndv));
    }

    /**
     * Estimates a {@code <} or {@code >} filter as one third of the input, Selinger's 1979 constant.
     *
     * @param inputRows the estimated input cardinality
     * @return {@code 0} when the input is empty, otherwise at least {@code 1}
     * @since 0.11
     */
    static long rangeFilter(long inputRows) {
        if (inputRows <= 0) {
            return 0;
        }
        return Math.max(1, inputRows / 3);
    }

    /**
     * Estimates an equi-join as {@code |R| · |S| / max(ndv(R.a), ndv(S.b))}.
     *
     * @param leftRows the estimated left cardinality
     * @param rightRows the estimated right cardinality
     * @param leftNdv the NDV of the left join column
     * @param rightNdv the NDV of the right join column
     * @return {@code 0} when either input is empty, otherwise at least {@code 1}
     * @since 0.11
     */
    static long equiJoin(long leftRows, long rightRows, long leftNdv, long rightNdv) {
        if (leftRows <= 0 || rightRows <= 0) {
            return 0;
        }
        long distinct = Math.max(Math.max(MISSING_NDV, leftNdv), Math.max(MISSING_NDV, rightNdv));
        try {
            return Math.max(1, Math.multiplyExact(leftRows, rightRows) / distinct);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}

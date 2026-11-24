package dk.itu.datasys;

import java.util.List;

/**
 * Pairs a Volcano operator tree with the catalog scan statistics computed while planning it.
 *
 * @param root the pipeline root
 * @param stats the partition totals of each {@code FROM} entry, in {@code FROM}-list order,
 *              recorded before any data file is opened
 * @param joinOrder the exposed table names in composition order
 * @param mode the join-order mode used to build this plan
 * @author Team 1
 * @version 0.11
 * @since 0.4
 */
public record QueryPlan(Operator root, List<ScanStats> stats, List<String> joinOrder, JoinMode mode) {
    /**
     * Creates a plan that owns immutable copies of its lists.
     *
     * @param root the pipeline root
     * @param stats the per-table partition totals
     * @param joinOrder the composition-order names
     * @param mode the join-order mode
     * @throws NullPointerException if a list or one of its elements is null
     * @since 0.10
     * @version 0.11
     */
    public QueryPlan {
        stats = List.copyOf(stats);
        joinOrder = List.copyOf(joinOrder);
    }
}

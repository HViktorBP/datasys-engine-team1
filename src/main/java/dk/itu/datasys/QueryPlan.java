package dk.itu.datasys;

import java.util.List;

/**
 * Pairs a Volcano operator tree with the catalog scan statistics computed while planning it.
 *
 * @param root the pipeline root
 * @param stats the partition totals of each {@code FROM} entry, in {@code FROM}-list order,
 *              recorded before any data file is opened
 * @author Team 1
 * @version 0.10
 * @since 0.4
 */
public record QueryPlan(Operator root, List<ScanStats> stats) {
    /**
     * Creates a plan that owns an immutable copy of its statistics.
     *
     * @param root the pipeline root
     * @param stats the per-table partition totals
     * @throws NullPointerException if {@code stats} or one of its elements is null
     * @since 0.10
     */
    public QueryPlan {
        stats = List.copyOf(stats);
    }
}

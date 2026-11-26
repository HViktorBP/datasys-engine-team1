package dk.itu.datasys;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Chooses a left-deep equi-join order from catalog estimates.
 *
 * <p>{@link JoinMode#SYNTAX} walks the {@code FROM} list and always builds on the left.
 * {@link JoinMode#OPTIMIZED} starts from the cheapest join edge and grows by the cheapest
 * connected table. Cross products are never chosen while a connecting predicate exists.
 *
 * @author Team 1
 * @version 0.11
 * @since 0.11
 */
final class JoinOrderer {
    /**
     * One equi-join predicate, tables and columns in {@code FROM} indexes.
     *
     * @param leftTable the {@code FROM} index of the left column's table
     * @param leftColumn the column index inside that table
     * @param rightTable the {@code FROM} index of the right column's table
     * @param rightColumn the column index inside that table
     */
    record Edge(int leftTable, int leftColumn, int rightTable, int rightColumn) {
        /**
         * Returns whether this edge connects the two tables.
         *
         * @param a a {@code FROM} index
         * @param b another {@code FROM} index
         * @return {@code true} when the edge is between {@code a} and {@code b}
         */
        boolean connects(int a, int b) {
            return (leftTable == a && rightTable == b) || (leftTable == b && rightTable == a);
        }

        /**
         * Returns the column of this edge that belongs to {@code table}.
         *
         * @param table a {@code FROM} index on this edge
         * @return the in-table column index
         */
        int columnOf(int table) {
            return table == leftTable ? leftColumn : rightColumn;
        }
    }

    /**
     * One candidate the heuristic evaluated, for the planner's {@code check} log line.
     *
     * @param leftTable one table of the candidate
     * @param rightTable the other table, or the table being added
     * @param estRows the estimated join output
     */
    record Check(int leftTable, int rightTable, long estRows) { }

    /**
     * One hash join in composition order.
     *
     * @param leftTable the table on the already-planned side of the hash key
     * @param rightTable the table being added
     * @param leftColumn the hash key column inside {@code leftTable}
     * @param rightColumn the hash key column inside {@code rightTable}
     * @param leftEst the estimated size of the already-planned input
     * @param rightEst the estimated size of the added table
     * @param outEst the estimated join output
     * @param leftBuilds {@code true} when the already-planned input builds the hash table
     */
    record Step(int leftTable, int rightTable, int leftColumn, int rightColumn,
                long leftEst, long rightEst, long outEst, boolean leftBuilds) { }

    /**
     * A complete left-deep join order.
     *
     * @param joinOrder {@code FROM} indexes in composition order
     * @param steps one step per hash join, aligned with {@code joinOrder} from the second table
     * @param checks every candidate evaluated, in evaluation order
     */
    record Result(List<Integer> joinOrder, List<Step> steps, List<Check> checks) { }

    /** Prevents instantiation. */
    private JoinOrderer() { }

    /**
     * Plans joins under the requested mode.
     *
     * @param mode syntax or optimized
     * @param rows estimated rows per {@code FROM} entry after filters
     * @param ndv {@code ndv[table][column]} for each base table
     * @param edges join predicates in {@code WHERE} order
     * @return the composition order and hash steps
     * @throws IllegalArgumentException if a required connecting predicate is missing
     * @since 0.11
     */
    static Result order(JoinMode mode, long[] rows, long[][] ndv, List<Edge> edges) {
        return mode == JoinMode.SYNTAX ? syntax(rows, ndv, edges) : optimized(rows, ndv, edges);
    }

    /**
     * Left-deep {@code FROM} order; the result so far always builds.
     *
     * @param rows estimated rows per table
     * @param ndv per-table per-column NDV
     * @param edges join predicates in {@code WHERE} order
     * @return the week-10 plan with estimates filled in
     * @throws IllegalArgumentException if table {@code i} has no edge to {@code 0..i-1}
     */
    static Result syntax(long[] rows, long[][] ndv, List<Edge> edges) {
        List<Integer> joinOrder = new ArrayList<>();
        joinOrder.add(0);
        List<Step> steps = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        long leftEst = rows[0];
        for (int table = 1; table < rows.length; table++) {
            Edge edge = firstConnecting(edges, table, table);
            if (edge == null) {
                throw disconnected(table);
            }
            int partner = edge.leftTable == table ? edge.rightTable : edge.leftTable;
            long out = Cardinality.equiJoin(leftEst, rows[table],
                    ndv[partner][edge.columnOf(partner)], ndv[table][edge.columnOf(table)]);
            checks.add(new Check(partner, table, out));
            steps.add(new Step(partner, table, edge.columnOf(partner), edge.columnOf(table),
                    leftEst, rows[table], out, true));
            joinOrder.add(table);
            leftEst = out;
        }
        return new Result(List.copyOf(joinOrder), List.copyOf(steps), List.copyOf(checks));
    }

    /**
     * Greedy smallest-intermediate-first order; the smaller estimated input builds.
     *
     * @param rows estimated rows per table
     * @param ndv per-table per-column NDV
     * @param edges join predicates in {@code WHERE} order
     * @return the greedy plan
     * @throws IllegalArgumentException if the join graph is disconnected
     */
    static Result optimized(long[] rows, long[][] ndv, List<Edge> edges) {
        if (rows.length == 1) {
            return new Result(List.of(0), List.of(), List.of());
        }
        List<Check> checks = new ArrayList<>();
        Edge seed = null;
        long seedEst = Long.MAX_VALUE;
        for (Edge edge : edges) {
            long est = Cardinality.equiJoin(rows[edge.leftTable], rows[edge.rightTable],
                    ndv[edge.leftTable][edge.leftColumn], ndv[edge.rightTable][edge.rightColumn]);
            checks.add(new Check(edge.leftTable, edge.rightTable, est));
            if (seed == null || est < seedEst) {
                seed = edge;
                seedEst = est;
            }
        }
        if (seed == null) {
            throw disconnected(1);
        }
        int first = seed.leftTable;
        int second = seed.rightTable;
        boolean firstBuilds = rows[first] <= rows[second];
        if (!firstBuilds) {
            int swap = first;
            first = second;
            second = swap;
        }
        List<Integer> joinOrder = new ArrayList<>();
        joinOrder.add(first);
        joinOrder.add(second);
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(first, second, seed.columnOf(first), seed.columnOf(second),
                rows[first], rows[second], seedEst, true));
        var inPlan = new BitSet();
        inPlan.set(first);
        inPlan.set(second);
        long leftEst = seedEst;
        while (joinOrder.size() < rows.length) {
            Edge best = null;
            int bestAdded = -1;
            int bestPartner = -1;
            long bestEst = Long.MAX_VALUE;
            for (int table = 0; table < rows.length; table++) {
                if (inPlan.get(table)) {
                    continue;
                }
                Edge edge = firstConnecting(edges, table, inPlan);
                if (edge == null) {
                    continue;
                }
                int partner = edge.leftTable == table ? edge.rightTable : edge.leftTable;
                long est = Cardinality.equiJoin(leftEst, rows[table],
                        ndv[partner][edge.columnOf(partner)], ndv[table][edge.columnOf(table)]);
                checks.add(new Check(partner, table, est));
                if (best == null || est < bestEst) {
                    best = edge;
                    bestAdded = table;
                    bestPartner = partner;
                    bestEst = est;
                }
            }
            if (best == null) {
                int missing = 0;
                while (inPlan.get(missing)) {
                    missing++;
                }
                throw disconnected(missing);
            }
            boolean leftBuilds = leftEst <= rows[bestAdded];
            steps.add(new Step(bestPartner, bestAdded, best.columnOf(bestPartner),
                    best.columnOf(bestAdded), leftEst, rows[bestAdded], bestEst, leftBuilds));
            joinOrder.add(bestAdded);
            inPlan.set(bestAdded);
            leftEst = bestEst;
        }
        return new Result(List.copyOf(joinOrder), List.copyOf(steps), List.copyOf(checks));
    }

    /**
     * Returns the first {@code WHERE}-order edge that connects {@code table} to an earlier table.
     *
     * @param edges join predicates
     * @param table the table being added
     * @param earlierCount tables {@code 0 .. earlierCount-1} are already in the plan
     * @return the first connecting edge, or {@code null}
     */
    private static Edge firstConnecting(List<Edge> edges, int table, int earlierCount) {
        var earlier = new BitSet();
        earlier.set(0, earlierCount);
        return firstConnecting(edges, table, earlier);
    }

    /**
     * Returns the first {@code WHERE}-order edge that connects {@code table} to {@code inPlan}.
     *
     * @param edges join predicates
     * @param table the candidate table
     * @param inPlan tables already joined
     * @return the first connecting edge, or {@code null}
     */
    private static Edge firstConnecting(List<Edge> edges, int table, BitSet inPlan) {
        for (Edge edge : edges) {
            if (edge.leftTable == table && inPlan.get(edge.rightTable)
                    || edge.rightTable == table && inPlan.get(edge.leftTable)) {
                return edge;
            }
        }
        return null;
    }

    /**
     * Builds the week-10 disconnected-join error.
     *
     * @param table the {@code FROM} index that could not be connected
     * @return an exception whose message the planner rewrites with the exposed name
     */
    /**
     * Signals that a table has no connecting join predicate.
     *
     * @since 0.11
     */
    static final class DisconnectedException extends IllegalArgumentException {
        /** The {@code FROM} index that could not be joined. */
        final int table;

        /**
         * Creates the exception for one {@code FROM} entry.
         *
         * @param table the disconnected {@code FROM} index
         */
        DisconnectedException(int table) {
            super("No join predicate connects table " + table);
            this.table = table;
        }
    }

    /**
     * Builds the disconnected-join error.
     *
     * @param table the {@code FROM} index that could not be connected
     * @return an exception carrying that index
     */
    private static DisconnectedException disconnected(int table) {
        return new DisconnectedException(table);
    }
}

package dk.itu.datasys;

/**
 * Pairs a Volcano operator tree with the catalog scan statistics computed while planning it.
 *
 * @param root the pipeline root, either a {@link ScanOperator} or a {@link FilterOperator}
 * @param stats the partition totals recorded before any data file is opened
 * @author Team 1
 * @version 0.4
 * @since 0.4
 */
public record QueryPlan(Operator root, ScanStats stats) { }

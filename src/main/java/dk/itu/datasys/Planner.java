package dk.itu.datasys;

import dk.itu.datasys.CatalogStore.Table;
import dk.itu.datasys.ScanOperator.PartitionRef;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a bound {@link SelectStatement} into a Volcano plan and records partition pruning.
 *
 * <p>Each {@code FROM} entry gets its own pipeline: a {@link ScanOperator} over the partitions
 * that survive pruning, wrapped in one {@link FilterOperator} per filter on that table. Filters
 * therefore sit directly above their table's scan, where min/max pruning applies.
 *
 * <p>Pruning uses catalog min/max statistics only. Decision log lines are emitted before any
 * operator opens a data file. A partition is pruned when any filter on its table proves that it
 * holds no match; the filters are checked in {@code WHERE} order and each check logs one line,
 * stopping at the first {@code PRUNED}. Without filters, every partition is logged as
 * {@code decision=READ reason=noPredicate}. The {@code table} field holds the alias when the
 * {@code FROM} entry has one.
 *
 * @author Team 1
 * @version 0.10
 * @since 0.4
 */
public final class Planner {
    /** Diagnostic logger for partition read/prune decisions. */
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);
    /** Storage catalog used to look up tables and published data files. */
    private final StorageEngine engine;

    /**
     * Creates a planner backed by the supplied storage engine.
     *
     * @param engine the engine whose catalogs supply partitions
     * @throws IllegalArgumentException if {@code engine} is {@code null}
     * @since 0.4
     * @version 0.7
     */
    public Planner(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        this.engine = engine;
    }

    /**
     * Builds the plan for a select statement against the current table snapshots.
     *
     * <p>Logs the read/prune decisions of every catalog partition before opening data files.
     *
     * @param statement the bound select statement
     * @return the operator tree and the {@link ScanStats} of each {@code FROM} entry
     * @throws IllegalArgumentException if a table or column is unknown, or the statement joins
     *                                  several tables
     * @since 0.4
     * @version 0.10
     */
    public QueryPlan plan(SelectStatement statement) {
        return plan(statement, engine.requireTables(statement.tables()));
    }

    /**
     * Plans from the statement's captured metadata without resolving current state again.
     *
     * @param statement the bound select
     * @param snapshots the immutable snapshot of each {@code FROM} entry
     * @return the operator tree and the pruning counts of each {@code FROM} entry
     * @throws IllegalArgumentException if a column is unknown, or the statement joins several
     *                                  tables
     * @since 0.7
     * @version 0.10
     */
    QueryPlan plan(SelectStatement statement, List<Table> snapshots) {
        var resolver = new ColumnResolver(statement.tables(), snapshots);
        List<Operator> pipelines = new ArrayList<>();
        List<ScanStats> stats = new ArrayList<>();
        for (int table = 0; table < snapshots.size(); table++) {
            List<Predicate> filters = new ArrayList<>();
            for (var filter : statement.filters()) {
                if (resolver.resolve(filter.column()).table() == table) {
                    filters.add(filter);
                }
            }
            String name = statement.tables().get(table).exposedName();
            pipelines.add(scanAndFilter(name, snapshots.get(table), filters, resolver, stats));
        }
        if (pipelines.size() > 1) {
            throw new IllegalArgumentException("Joins are not supported yet");
        }
        return new QueryPlan(pipelines.getFirst(), stats);
    }

    /**
     * Builds one table's pipeline: a scan of the surviving partitions under its filters.
     *
     * @param name the table's exposed name, used in log lines
     * @param table the table snapshot
     * @param filters the filters on this table
     * @param resolver the resolver that locates each filter's column
     * @param stats receives this table's pruning counts
     * @return the scan, wrapped in one filter per predicate
     */
    private Operator scanAndFilter(String name, Table table, List<Predicate> filters,
                                   ColumnResolver resolver, List<ScanStats> stats) {
        var partitions = allPartitions(table);
        List<PartitionRef> surviving = new ArrayList<>();
        for (int index = 0; index < partitions.size(); index++) {
            if (filters.isEmpty()) {
                LOGGER.debug("table={} partition={} decision=READ reason=noPredicate",
                        StorageEngine.clean(name), index);
                surviving.add(partitions.get(index));
            } else if (!canPrune(name, index, partitions.get(index), filters, resolver)) {
                surviving.add(partitions.get(index));
            }
        }
        int total = partitions.size();
        stats.add(new ScanStats(total, surviving.size(), total - surviving.size()));

        Operator root = new ScanOperator(table.columns(), surviving);
        for (var filter : filters) {
            var column = resolver.resolve(filter.column());
            root = new FilterOperator(root, column.column(), column.type(), filter.comparison(),
                    filter.constant());
        }
        return root;
    }

    /**
     * Tests the filters of a table against one partition's statistics and logs each check.
     *
     * @param name the table's exposed name, used in log lines
     * @param index the partition's position in catalog order
     * @param partition the partition
     * @param filters the filters on this table, at least one
     * @param resolver the resolver that locates each filter's column
     * @return {@code true} if some filter proves that the partition holds no match
     */
    private boolean canPrune(String name, int index, PartitionRef partition, List<Predicate> filters,
                             ColumnResolver resolver) {
        for (var filter : filters) {
            var column = resolver.resolve(filter.column());
            var statistics = partition.partition().chunks().get(column.column()).statistics();
            boolean skip = PartitionPruner.canPrune(column.type(), statistics, filter.comparison(),
                    filter.constant());
            LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    StorageEngine.clean(name),
                    StorageEngine.clean(filter.column().name()),
                    filter.comparison(),
                    StorageEngine.clean(filter.constant()),
                    index,
                    StorageEngine.clean(statistics.min()),
                    StorageEngine.clean(statistics.max()),
                    skip ? "PRUNED" : "READ");
            if (skip) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects every catalog partition in file order.
     *
     * @param table the table snapshot
     * @return partitions in catalog order
     */
    private List<PartitionRef> allPartitions(Table table) {
        List<PartitionRef> partitions = new ArrayList<>();
        for (var file : table.files()) {
            for (var partition : file.partitions()) {
                partitions.add(new PartitionRef(engine.dataPath(file.path()), partition));
            }
        }
        return partitions;
    }

    /**
     * Resolves a column name to its schema index.
     *
     * @param columns the table schema
     * @param columnName the column
     * @return the zero-based column index
     * @throws IllegalArgumentException if the column is unknown
     */
    static int columnIndex(List<ColumnSpec> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown column: " + columnName);
    }
}

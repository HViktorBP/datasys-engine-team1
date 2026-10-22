package dk.itu.datasys;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a bound {@link SelectStatement} into a Volcano plan and records partition pruning.
 *
 * <p>Pruning uses catalog min/max statistics only. Decision log lines are emitted before any
 * operator opens a data file. Without a predicate, every partition is logged as
 * {@code decision=READ reason=noPredicate}.
 *
 * @author Team 1
 * @version 0.7
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
     * Builds a scan or filter-over-scan plan for a select statement.
     *
     * <p>Logs one read/prune decision per catalog partition before opening data files,
     * including read decisions when the statement has no predicate.
     *
     * @param statement the bound select statement
     * @return the operator tree and catalog {@link ScanStats}
     * @throws IllegalArgumentException if the table or predicate column is unknown
     * @since 0.4
     * @version 0.7
     */
    public QueryPlan plan(SelectStatement statement) {
        return plan(statement, engine.requireTable(statement.tableName()));
    }

    /**
     * Plans from the statement's captured metadata without resolving current state again.
     *
     * @param statement the bound select
     * @param table the immutable statement snapshot
     * @return the operator tree and pruning counts
     * @throws IllegalArgumentException if the predicate column is unknown
     * @since 0.7
     * @version 0.7
     */
    QueryPlan plan(SelectStatement statement, CatalogStore.Table table) {
        var columns = table.columns();
        var partitions = allPartitions(table);
        var where = statement.where();
        if (where.isEmpty()) {
            int total = partitions.size();
            for (int index = 0; index < total; index++) {
                LOGGER.debug("table={} partition={} decision=READ reason=noPredicate",
                        StorageEngine.clean(statement.tableName()), index);
            }
            return new QueryPlan(new ScanOperator(columns, partitions),
                    new ScanStats(total, total, 0));
        }

        var predicate = where.orElseThrow();
        int columnIndex = columnIndex(columns, predicate.columnName());
        ColumnType type = columns.get(columnIndex).type();
        List<ScanOperator.PartitionRef> surviving = new ArrayList<>();
        int total = 0;
        int pruned = 0;
        for (var partition : partitions) {
            var statistics = partition.partition().chunks().get(columnIndex).statistics();
            boolean skip = PartitionPruner.canPrune(type, statistics, predicate.comparison(),
                    predicate.constant());
            LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                    StorageEngine.clean(statement.tableName()),
                    StorageEngine.clean(predicate.columnName()),
                    predicate.comparison(),
                    StorageEngine.clean(predicate.constant()),
                    total,
                    StorageEngine.clean(statistics.min()),
                    StorageEngine.clean(statistics.max()),
                    skip ? "PRUNED" : "READ");
            total++;
            if (skip) {
                pruned++;
            } else {
                surviving.add(partition);
            }
        }
        Operator scan = new ScanOperator(columns, surviving);
        Operator root = new FilterOperator(scan, columnIndex, type, predicate.comparison(),
                predicate.constant());
        return new QueryPlan(root, new ScanStats(total, surviving.size(), pruned));
    }

    /**
     * Collects every catalog partition in file order.
     *
     * @param table the table snapshot
     * @return partitions in catalog order
     */
    private List<ScanOperator.PartitionRef> allPartitions(CatalogStore.Table table) {
        List<ScanOperator.PartitionRef> partitions = new ArrayList<>();
        for (var file : table.files()) {
            for (var partition : file.partitions()) {
                partitions.add(new ScanOperator.PartitionRef(engine.dataPath(file.path()), partition));
            }
        }
        return partitions;
    }

    /**
     * Resolves a predicate column name to its schema index.
     *
     * @param columns the table schema
     * @param columnName the predicate column
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

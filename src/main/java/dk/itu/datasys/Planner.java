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
 * therefore sit directly below the joins, right above their table's scan, where min/max pruning
 * applies.
 *
 * <p>Under {@link JoinMode#SYNTAX} the pipelines are joined left-deep in {@code FROM}-list order
 * and the result so far always builds, matching week 10. Under {@link JoinMode#OPTIMIZED}, the
 * default, the planner estimates each intermediate with {@link Cardinality} and grows a left-deep
 * tree from the cheapest equi-join. The smaller estimated input builds the hash table. A
 * {@link ProjectOperator} restores {@code FROM}-list column order so {@code SELECT *} does not
 * depend on the plan.
 *
 * <p>Pruning uses catalog min/max statistics only. Decision log lines are emitted before any
 * operator opens a data file. A partition is pruned when any filter on its table proves that it
 * holds no match; the filters are checked in {@code WHERE} order and each check logs one line,
 * stopping at the first {@code PRUNED}. Without filters, every partition is logged as
 * {@code decision=READ reason=noPredicate}. The {@code table} field holds the alias when the
 * {@code FROM} entry has one.
 *
 * @author Team 1
 * @version 0.11
 * @since 0.4
 */
public final class Planner {
    /** Diagnostic logger for partition read/prune decisions and join ordering. */
    private static final Logger LOGGER = LoggerFactory.getLogger(Planner.class);
    /** Storage catalog used to look up tables and published data files. */
    private final StorageEngine engine;
    /** Whether to reorder joins or keep the {@code FROM}-list plan. */
    private final JoinMode mode;

    /**
     * Creates a planner that reorders joins by estimated intermediate size.
     *
     * @param engine the engine whose catalogs supply partitions
     * @throws IllegalArgumentException if {@code engine} is {@code null}
     * @since 0.4
     * @version 0.11
     */
    public Planner(StorageEngine engine) {
        this(engine, JoinMode.OPTIMIZED);
    }

    /**
     * Creates a planner with an explicit join-order mode.
     *
     * @param engine the engine whose catalogs supply partitions
     * @param mode {@link JoinMode#OPTIMIZED} or {@link JoinMode#SYNTAX}
     * @throws IllegalArgumentException if {@code engine} or {@code mode} is {@code null}
     * @since 0.11
     */
    public Planner(StorageEngine engine, JoinMode mode) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("join mode is required");
        }
        this.engine = engine;
        this.mode = mode;
    }

    /**
     * Builds the plan for a select statement against the current table snapshots.
     *
     * <p>Logs the read/prune decisions of every catalog partition before opening data files.
     *
     * @param statement the bound select statement
     * @return the operator tree and the {@link ScanStats} of each {@code FROM} entry
     * @throws IllegalArgumentException if a table or column is unknown, or a table has no join
     *                                  predicate to the tables already in the plan
     * @since 0.4
     * @version 0.11
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
     * @throws IllegalArgumentException if a column is unknown, or a table has no join predicate
     *                                  to the tables already in the plan
     * @since 0.7
     * @version 0.11
     */
    QueryPlan plan(SelectStatement statement, List<Table> snapshots) {
        var resolver = new ColumnResolver(statement.tables(), snapshots);
        List<Operator> pipelines = new ArrayList<>();
        List<ScanStats> stats = new ArrayList<>();
        List<List<PartitionRef>> surviving = new ArrayList<>();
        for (int table = 0; table < snapshots.size(); table++) {
            List<Predicate> filters = new ArrayList<>();
            for (var filter : statement.filters()) {
                if (resolver.resolve(filter.column()).table() == table) {
                    filters.add(filter);
                }
            }
            String name = statement.tables().get(table).exposedName();
            var pipeline = scanAndFilter(name, snapshots.get(table), filters, resolver);
            pipelines.add(pipeline.root());
            stats.add(pipeline.stats());
            surviving.add(pipeline.surviving());
        }
        long[] rows = new long[snapshots.size()];
        long[][] ndv = new long[snapshots.size()][];
        for (int table = 0; table < snapshots.size(); table++) {
            rows[table] = estimateTable(surviving.get(table), tableFilters(statement, resolver, table),
                    resolver);
            ndv[table] = columnNdvs(surviving.get(table), snapshots.get(table).columns().size());
        }
        List<JoinOrderer.Edge> edges = new ArrayList<>();
        for (var join : statement.joins()) {
            var left = resolver.resolve(join.left());
            var right = resolver.resolve(join.right());
            edges.add(new JoinOrderer.Edge(left.table(), left.column(), right.table(), right.column()));
        }
        JoinOrderer.Result order;
        try {
            order = JoinOrderer.order(mode, rows, ndv, edges);
        } catch (JoinOrderer.DisconnectedException error) {
            throw disconnected(statement, error.table);
        }
        for (var check : order.checks()) {
            LOGGER.debug("check tables={};{} estRows={}",
                    StorageEngine.clean(exposed(statement, check.leftTable())),
                    StorageEngine.clean(exposed(statement, check.rightTable())),
                    check.estRows());
        }
        List<String> joinOrder = order.joinOrder().stream().map(table -> exposed(statement, table)).toList();
        LOGGER.debug("joinOrder={} mode={}", StorageEngine.clean(String.join(";", joinOrder)), mode);
        Operator joined = assemble(statement, resolver, snapshots, pipelines, order);
        return new QueryPlan(joined, stats, joinOrder, mode);
    }

    /**
     * Builds the left-deep hash-join tree and a projection back to {@code FROM}-list column order.
     *
     * @param statement the select statement
     * @param resolver the statement's column resolver
     * @param snapshots the table snapshots
     * @param pipelines one scan/filter pipeline per {@code FROM} entry
     * @param order the join composition
     * @return the plan root
     */
    private Operator assemble(SelectStatement statement, ColumnResolver resolver, List<Table> snapshots,
                              List<Operator> pipelines, JoinOrderer.Result order) {
        if (order.joinOrder().size() == 1) {
            return pipelines.get(order.joinOrder().getFirst());
        }
        List<Integer> physical = new ArrayList<>();
        physical.add(order.joinOrder().getFirst());
        Operator joined = pipelines.get(order.joinOrder().getFirst());
        for (int i = 0; i < order.steps().size(); i++) {
            var step = order.steps().get(i);
            int added = step.rightTable();
            Operator next = pipelines.get(added);
            int buildKey;
            int probeKey;
            Operator build;
            Operator probe;
            if (step.leftBuilds()) {
                build = joined;
                probe = next;
                buildKey = physicalIndex(physical, snapshots, step.leftTable(), step.leftColumn());
                probeKey = step.rightColumn();
            } else {
                build = next;
                probe = joined;
                buildKey = step.rightColumn();
                probeKey = physicalIndex(physical, snapshots, step.leftTable(), step.leftColumn());
            }
            List<Integer> prefix = order.joinOrder().subList(0, i + 2);
            String tables = String.join(";", prefix.stream().map(table -> exposed(statement, table)).toList());
            Operator root = new HashJoinOperator(build, probe, buildKey, probeKey, tables,
                    step.outEst(), i + 1);
            List<Integer> nextPhysical = new ArrayList<>();
            if (step.leftBuilds()) {
                nextPhysical.addAll(physical);
                nextPhysical.add(added);
            } else {
                nextPhysical.add(added);
                nextPhysical.addAll(physical);
            }
            root = extraPredicates(statement, resolver, snapshots, root, physical, added, step,
                    nextPhysical);
            joined = root;
            physical = nextPhysical;
        }
        int[] projection = fromListProjection(snapshots, physical);
        if (!identity(projection)) {
            joined = new ProjectOperator(joined, projection);
        }
        return joined;
    }

    /**
     * Wraps extra connecting equalities above the hash join, in {@code WHERE} order.
     *
     * @param statement the select
     * @param resolver the statement's column resolver
     * @param snapshots the table snapshots
     * @param root the hash join
     * @param previousPhysical tables in the build/probe input that was already joined
     * @param added the table just added
     * @param step the hash-join step whose predicate must not be repeated
     * @param physical the physical table order after this join
     * @return {@code root}, possibly under extra equality filters
     */
    private Operator extraPredicates(SelectStatement statement, ColumnResolver resolver,
                                     List<Table> snapshots, Operator root,
                                     List<Integer> previousPhysical, int added, JoinOrderer.Step step,
                                     List<Integer> physical) {
        for (var join : statement.joins()) {
            var left = resolver.resolve(join.left());
            var right = resolver.resolve(join.right());
            boolean connects = (left.table() == added && previousPhysical.contains(right.table()))
                    || (right.table() == added && previousPhysical.contains(left.table()));
            if (!connects) {
                continue;
            }
            boolean hashKey = sameEdge(left, right, step);
            if (hashKey) {
                continue;
            }
            int leftIndex = physicalIndex(physical, snapshots, left.table(), left.column());
            int rightIndex = physicalIndex(physical, snapshots, right.table(), right.column());
            root = new ColumnEqualityFilterOperator(root, leftIndex, rightIndex, left.type());
        }
        return root;
    }

    /**
     * Returns whether a resolved join is the hash-key edge of a step.
     *
     * @param left one resolved endpoint
     * @param right the other endpoint
     * @param step the hash-join step
     * @return {@code true} when the endpoints are that step's key
     */
    private static boolean sameEdge(ColumnResolver.Resolved left, ColumnResolver.Resolved right,
                                    JoinOrderer.Step step) {
        return left.table() == step.leftTable() && left.column() == step.leftColumn()
                && right.table() == step.rightTable() && right.column() == step.rightColumn()
                || left.table() == step.rightTable() && left.column() == step.rightColumn()
                && right.table() == step.leftTable() && right.column() == step.leftColumn();
    }

    /**
     * Maps a logical table column to its index in a physical concatenation.
     *
     * @param physical tables in emit order
     * @param snapshots the table snapshots
     * @param table the {@code FROM} index
     * @param column the in-table column
     * @return the physical column index
     */
    static int physicalIndex(List<Integer> physical, List<Table> snapshots, int table, int column) {
        int index = 0;
        for (int current : physical) {
            if (current == table) {
                return index + column;
            }
            index += snapshots.get(current).columns().size();
        }
        throw new IllegalStateException("table not in physical layout: " + table);
    }

    /**
     * Builds a projection from physical concat order to {@code FROM}-list concat order.
     *
     * @param snapshots the table snapshots in {@code FROM} order
     * @param physical tables in physical emit order
     * @return {@code output[i] = input[result[i]]}
     */
    static int[] fromListProjection(List<Table> snapshots, List<Integer> physical) {
        int width = 0;
        for (var snapshot : snapshots) {
            width += snapshot.columns().size();
        }
        int[] columns = new int[width];
        int out = 0;
        for (int table = 0; table < snapshots.size(); table++) {
            int start = physicalIndex(physical, snapshots, table, 0);
            for (int column = 0; column < snapshots.get(table).columns().size(); column++) {
                columns[out++] = start + column;
            }
        }
        return columns;
    }

    /**
     * Returns whether a projection is the identity map.
     *
     * @param columns the projection
     * @return {@code true} when {@code columns[i] == i} for every i
     */
    static boolean identity(int[] columns) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i] != i) {
                return false;
            }
        }
        return true;
    }

    /**
     * Estimates one table after pruning and its local filters.
     *
     * @param surviving the partitions that will be scanned
     * @param filters the filters on this table
     * @param resolver the statement resolver
     * @return the estimated row count
     */
    private long estimateTable(List<PartitionRef> surviving, List<Predicate> filters,
                               ColumnResolver resolver) {
        long rows = 0;
        for (var partition : surviving) {
            rows += partition.partition().rowCount();
        }
        rows = Cardinality.baseTable(rows);
        for (var filter : filters) {
            if (filter.comparison() == Comparison.EQUALS) {
                int column = resolver.resolve(filter.column()).column();
                rows = Cardinality.equalityFilter(rows, columnNdv(surviving, column));
            } else {
                rows = Cardinality.rangeFilter(rows);
            }
        }
        return rows;
    }

    /**
     * Collects filters that apply to one {@code FROM} entry.
     *
     * @param statement the select
     * @param resolver the resolver
     * @param table the {@code FROM} index
     * @return filters on that table, in {@code WHERE} order
     */
    private static List<Predicate> tableFilters(SelectStatement statement, ColumnResolver resolver,
                                                int table) {
        List<Predicate> filters = new ArrayList<>();
        for (var filter : statement.filters()) {
            if (resolver.resolve(filter.column()).table() == table) {
                filters.add(filter);
            }
        }
        return filters;
    }

    /**
     * Returns per-column merged NDVs for the surviving partitions of one table.
     *
     * @param surviving the scanned partitions
     * @param width the number of columns
     * @return NDV per column
     */
    private static long[] columnNdvs(List<PartitionRef> surviving, int width) {
        long[] ndv = new long[width];
        for (int column = 0; column < width; column++) {
            ndv[column] = columnNdv(surviving, column);
        }
        return ndv;
    }

    /**
     * Merges per-partition NDVs for one column, substituting {@link Cardinality#missingNdv()}
     * when a partition has none.
     *
     * @param surviving the scanned partitions
     * @param column the in-table column index
     * @return the merged table NDV, or {@link Cardinality#missingNdv()} when nothing survived
     */
    static long columnNdv(List<PartitionRef> surviving, int column) {
        if (surviving.isEmpty()) {
            return Cardinality.missingNdv();
        }
        List<Long> ndvs = new ArrayList<>();
        for (var partition : surviving) {
            Long ndv = partition.partition().chunks().get(column).statistics().ndv();
            ndvs.add(ndv == null ? Cardinality.missingNdv() : ndv);
        }
        return Cardinality.tableNdv(ndvs);
    }

    /**
     * Returns the exposed name of a {@code FROM} entry.
     *
     * @param statement the select
     * @param table the {@code FROM} index
     * @return the alias, or the table name when there is no alias
     */
    private static String exposed(SelectStatement statement, int table) {
        return statement.tables().get(table).exposedName();
    }

    /**
     * Builds the user-facing error for a disconnected join.
     *
     * @param statement the select
     * @param table the disconnected {@code FROM} index
     * @return an {@link IllegalArgumentException} naming the table
     */
    private IllegalArgumentException disconnected(SelectStatement statement, int table) {
        String name = exposed(statement, table);
        if (mode == JoinMode.SYNTAX) {
            return new IllegalArgumentException("No join predicate connects " + name
                    + " to the tables before it; reorder the FROM list");
        }
        return new IllegalArgumentException("No join predicate connects " + name
                + " to the other tables");
    }

    /**
     * One table's scan, filters, surviving partitions, and pruning counts.
     *
     * @param root the scan wrapped in this table's filters
     * @param surviving the partitions that will be read
     * @param stats the pruning counts
     */
    private record TablePipeline(Operator root, List<PartitionRef> surviving, ScanStats stats) { }

    /**
     * Builds one table's pipeline: a scan of the surviving partitions under its filters.
     *
     * @param name the table's exposed name, used in log lines
     * @param table the table snapshot
     * @param filters the filters on this table
     * @param resolver the resolver that locates each filter's column
     * @return the scan, wrapped in one filter per predicate, plus surviving partitions
     */
    private TablePipeline scanAndFilter(String name, Table table, List<Predicate> filters,
                                        ColumnResolver resolver) {
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
        var stats = new ScanStats(total, surviving.size(), total - surviving.size());
        Operator root = new ScanOperator(table.columns(), surviving);
        for (var filter : filters) {
            var column = resolver.resolve(filter.column());
            root = new FilterOperator(root, column.column(), column.type(), filter.comparison(),
                    filter.constant());
        }
        return new TablePipeline(root, List.copyOf(surviving), stats);
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

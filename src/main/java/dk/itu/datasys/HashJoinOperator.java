package dk.itu.datasys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inner equi-join: builds a hash table from the build child, streams the probe child.
 *
 * <p>{@link #open()} drains the build child into a map from key to rows; a key maps to a list
 * because keys repeat. {@link #next()} pulls probe rows, looks up each key and emits one output
 * row per matching build row: the build row's columns followed by the probe row's columns. A probe
 * row that matches {@code k} build rows yields {@code k} rows over {@code k} calls, so the operator
 * keeps a cursor into the current match list between calls. Rows without a partner are dropped.
 *
 * <p>Keys match with {@link Object#equals}, so the binder only allows joins between columns of one
 * type. Memory is linear in the build side and constant in the probe side.
 *
 * @author Team 1
 * @version 0.10
 * @since 0.10
 */
public final class HashJoinOperator implements Operator {
    /** Diagnostic logger for join cardinalities. */
    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);
    /** Child drained into the hash table. */
    private final Operator build;
    /** Child streamed against the hash table. */
    private final Operator probe;
    /** Position of the join key in a build row. */
    private final int buildKey;
    /** Position of the join key in a probe row. */
    private final int probeKey;
    /** The joined tables, such as {@code orders;customers}, for the summary log line. */
    private final String tables;
    /** Build rows by join key. */
    private Map<Object, List<Object[]>> buildMap = new HashMap<>();
    /** The probe row whose matches are being emitted. */
    private Object[] probeRow;
    /** The build rows matching {@link #probeRow}. */
    private List<Object[]> matches = List.of();
    /** Position in {@link #matches} of the next row to emit. */
    private int nextMatch;
    /** Number of rows drained from the build child. */
    private int rowsBuild;
    /** Number of distinct keys in the hash table. */
    private int keys;
    /** Number of rows pulled from the probe child. */
    private int rowsProbe;
    /** Number of rows emitted. */
    private int rowsOut;

    /**
     * Creates a hash join.
     *
     * @param build the child drained into the hash table
     * @param probe the child streamed against the hash table
     * @param buildKey the position of the join key in a build row
     * @param probeKey the position of the join key in a probe row
     * @param tables the joined tables, logged by {@link #close()}
     */
    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey, String tables) {
        this.build = build;
        this.probe = probe;
        this.buildKey = buildKey;
        this.probeKey = probeKey;
        this.tables = tables;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens both children and drains the build child into the hash table.
     */
    @Override
    public void open() {
        buildMap = new HashMap<>();
        probeRow = null;
        matches = List.of();
        nextMatch = 0;
        rowsBuild = 0;
        rowsProbe = 0;
        rowsOut = 0;
        build.open();
        probe.open();
        for (Object[] row = build.next(); row != null; row = build.next()) {
            rowsBuild++;
            buildMap.computeIfAbsent(joinKey(row[buildKey]), key -> new ArrayList<>()).add(row);
        }
        keys = buildMap.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the build row's columns followed by the probe row's columns.
     */
    @Override
    public Object[] next() {
        while (nextMatch == matches.size()) {
            probeRow = probe.next();
            if (probeRow == null) {
                return null;
            }
            rowsProbe++;
            matches = buildMap.getOrDefault(joinKey(probeRow[probeKey]), List.of());
            nextMatch = 0;
        }
        rowsOut++;
        return concat(matches.get(nextMatch++), probeRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Releases the hash table and then logs {@code tables}, {@code rowsBuild}, {@code keys},
     * {@code rowsProbe} and {@code rowsOut} at debug. Because the children close first, the joins
     * of a left-deep plan log from the bottom up.
     */
    @Override
    public void close() {
        try {
            build.close();
        } finally {
            probe.close();
        }
        buildMap = new HashMap<>();
        LOGGER.debug("tables={} rowsBuild={} keys={} rowsProbe={} rowsOut={}",
                StorageEngine.clean(tables), rowsBuild, keys, rowsProbe, rowsOut);
    }

    /**
     * Normalizes a key so that values SQL treats as equal are also {@code equals}.
     *
     * @param value a column value
     * @return the value, with {@code -0.0} replaced by {@code 0.0}
     */
    private static Object joinKey(Object value) {
        // Double.equals tells -0.0 from 0.0, but SQL, DuckDB and our filters treat them as equal.
        return value instanceof Double number && number == 0.0 ? 0.0 : value;
    }

    /**
     * Concatenates a build row and a probe row into one output row.
     *
     * @param buildRow the build row
     * @param probeRow the probe row
     * @return a new row with the build columns first
     */
    private static Object[] concat(Object[] buildRow, Object[] probeRow) {
        Object[] row = new Object[buildRow.length + probeRow.length];
        System.arraycopy(buildRow, 0, row, 0, buildRow.length);
        System.arraycopy(probeRow, 0, row, buildRow.length, probeRow.length);
        return row;
    }
}

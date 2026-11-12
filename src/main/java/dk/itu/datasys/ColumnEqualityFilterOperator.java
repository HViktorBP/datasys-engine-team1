package dk.itu.datasys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits rows from a child operator whose two columns hold equal values.
 *
 * <p>The planner applies the second and later join predicates that connect a table to the tables
 * before it with this operator, directly above the hash join that used the first predicate.
 *
 * @author Team 1
 * @version 0.10
 * @since 0.10
 */
public final class ColumnEqualityFilterOperator implements Operator {
    /** Diagnostic logger for filter cardinalities. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ColumnEqualityFilterOperator.class);
    /** Child operator that supplies candidate rows. */
    private final Operator child;
    /** Position of the first compared column. */
    private final int left;
    /** Position of the second compared column. */
    private final int right;
    /** Type of both compared columns. */
    private final ColumnType type;
    /** Number of rows pulled from the child. */
    private int rowsIn;
    /** Number of rows emitted by this operator. */
    private int rowsOut;

    /**
     * Creates an equality filter over the supplied child operator.
     *
     * @param child the operator that produces candidate rows
     * @param left the position of the first compared column
     * @param right the position of the second compared column
     * @param type the type of both columns
     */
    ColumnEqualityFilterOperator(Operator child, int left, int right, ColumnType type) {
        this.child = child;
        this.left = left;
        this.right = right;
        this.type = type;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void open() {
        rowsIn = 0;
        rowsOut = 0;
        child.open();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] next() {
        Object[] row;
        while ((row = child.next()) != null) {
            rowsIn++;
            if (type.compare(row[left], row[right]) == 0) {
                rowsOut++;
                return row;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also logs {@code rowsIn} and {@code rowsOut} at debug after closing the child.
     */
    @Override
    public void close() {
        child.close();
        LOGGER.debug("rowsIn={} rowsOut={}", rowsIn, rowsOut);
    }
}

package dk.itu.datasys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits rows from a child operator that satisfy a typed comparison predicate.
 *
 * @author Team 1
 * @version 0.4
 * @since 0.4
 */
public final class FilterOperator implements Operator {
    /** Diagnostic logger for filter cardinalities. */
    private static final Logger LOGGER = LoggerFactory.getLogger(FilterOperator.class);
    /** Child operator that supplies candidate rows. */
    private final Operator child;
    /** Zero-based index of the predicate column. */
    private final int columnIndex;
    /** Type of the predicate column. */
    private final ColumnType type;
    /** Comparison applied to the predicate column. */
    private final Comparison comparison;
    /** Typed predicate constant. */
    private final Object constant;
    /** Number of rows pulled from the child. */
    private int rowsIn;
    /** Number of rows emitted by this operator. */
    private int rowsOut;

    /**
     * Creates a filter over the supplied child operator.
     *
     * @param child the operator that produces candidate rows
     * @param columnIndex the zero-based predicate column
     * @param type the type of the predicate column
     * @param comparison the comparison operator
     * @param constant the typed predicate constant
     */
    FilterOperator(Operator child, int columnIndex, ColumnType type, Comparison comparison,
                   Object constant) {
        this.child = child;
        this.columnIndex = columnIndex;
        this.type = type;
        this.comparison = comparison;
        this.constant = constant;
    }

    /**
     * Returns the child operator supplying candidate rows.
     *
     * @return the child operator
     */
    Operator child() {
        return child;
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
            if (comparison.matches(type.compare(row[columnIndex], constant))) {
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

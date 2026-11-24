package dk.itu.datasys;

/**
 * Reorders columns of each child row, used to restore {@code FROM}-list order after a reordered
 * join tree.
 *
 * @author Team 1
 * @version 0.11
 * @since 0.11
 */
final class ProjectOperator implements Operator {
    /** Child that produces physical rows. */
    private final Operator child;
    /** Physical column index for each output column. */
    private final int[] columns;

    /**
     * Creates a projection.
     *
     * @param child the operator that produces physical rows
     * @param columns {@code output[i] = input[columns[i]]}
     */
    ProjectOperator(Operator child, int[] columns) {
        this.child = child;
        this.columns = columns.clone();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void open() {
        child.open();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a new row with columns in projection order.
     */
    @Override
    public Object[] next() {
        Object[] in = child.next();
        if (in == null) {
            return null;
        }
        Object[] out = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            out[i] = in[columns[i]];
        }
        return out;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        child.close();
    }
}

package dk.itu.datasys;

/**
 * Pulls rows from a Volcano-style execution pipeline.
 *
 * <p>Callers invoke {@link #open()}, then {@link #next()} until it returns {@code null}, then
 * {@link #close()}. {@code close} may be called after a failure in {@code open} or {@code next} to
 * release resources.
 *
 * @author Team 1
 * @version 0.4
 * @since 0.4
 */
public interface Operator {
    /**
     * Prepares this operator to produce rows.
     *
     * @throws java.io.UncheckedIOException if a storage-backed operator cannot open its inputs
     */
    void open();

    /**
     * Returns the next row in schema column order, or {@code null} when the operator is exhausted.
     *
     * @return the next row, or {@code null} when no rows remain
     * @throws java.io.UncheckedIOException if a storage-backed operator cannot read the next row
     */
    Object[] next();

    /**
     * Releases resources held by this operator.
     *
     * <p>Repeated calls are safe. Implementations should still close children when {@code open} or
     * {@code next} failed.
     */
    void close();
}

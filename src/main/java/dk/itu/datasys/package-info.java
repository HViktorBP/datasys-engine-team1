/**
 * Provides the SQL front end, Volcano executor, and persistent columnar storage API for the
 * Team 1 query engine.
 *
 * <p>SQL text is parsed, bound, planned, and executed. {@code SELECT} runs on a
 * {@link dk.itu.datasys.ScanOperator scan} and optional {@link dk.itu.datasys.FilterOperator
 * filter}. Persistent operations are exposed through {@link dk.itu.datasys.StorageEngine}.
 *
 * @since 0.1
 */
package dk.itu.datasys;

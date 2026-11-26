/**
 * Provides the SQL front end, Volcano executor, and persistent columnar storage API for the
 * Team 1 query engine.
 *
 * <p>SQL text is parsed, bound, planned, and executed. {@code SELECT} runs on a
 * Volcano pipeline of scans, filters and hash joins. The planner can reorder
 * joins using catalog NDV statistics. Persistent operations are exposed through
 * {@link StorageEngine}.
 *
 * @since 0.1
 */
package dk.itu.datasys;

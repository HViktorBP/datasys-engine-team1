/**
 * Provides the SQL front end and persistent columnar storage API for the Team 1 query engine.
 *
 * <p>The public API models the supported SQL statements, validates them against stored schemas,
 * renders them as SQL, and stores or scans typed table data. Persistent operations are exposed
 * through {@link dk.itu.datasys.StorageEngine}.
 *
 * @since 0.1
 */
package dk.itu.datasys;

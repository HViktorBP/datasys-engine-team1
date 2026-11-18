package dk.itu.datasys;

import java.util.Objects;
import java.util.Optional;

/**
 * Names one entry of a {@code FROM} list: a table and an optional alias.
 *
 * <p>In {@code FROM customers c1, orders}, the first entry has the alias {@code c1} and the second
 * has none. Columns are qualified with the {@linkplain #exposedName() exposed name}.
 *
 * @param tableName the catalog table to read
 * @param alias the alias written after the table name, or empty
 * @author Team 1
 * @version 0.10
 * @since 0.10
 */
public record TableRef(String tableName, Optional<String> alias) {
    /**
     * Creates a table reference.
     *
     * @param tableName the catalog table
     * @param alias the optional alias
     * @throws NullPointerException if {@code tableName} or {@code alias} is null
     */
    public TableRef {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(alias, "alias");
    }

    /**
     * Creates a table reference without an alias.
     *
     * @param tableName the catalog table
     * @throws NullPointerException if {@code tableName} is null
     */
    public TableRef(String tableName) {
        this(tableName, Optional.empty());
    }

    /**
     * Returns the name that qualifies this entry's columns: the alias if present, otherwise the
     * table name.
     *
     * <p>As in DuckDB, an alias hides the table name, so {@code FROM customers c1} accepts
     * {@code c1.c_city} but not {@code customers.c_city}.
     *
     * @return the alias or the table name
     */
    public String exposedName() {
        return alias.orElse(tableName);
    }
}

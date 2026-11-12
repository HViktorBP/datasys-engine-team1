package dk.itu.datasys;

import java.util.Objects;
import java.util.Optional;

/**
 * Names a column in a query, optionally qualified by a table name or alias.
 *
 * <p>{@code c_city} has no qualifier; {@code c1.c_city} has the qualifier {@code c1}. The binder
 * resolves the reference against the tables of the {@code FROM} list.
 *
 * @param qualifier the table name or alias before the dot, or empty for an unqualified name
 * @param name the column name
 * @author Team 1
 * @version 0.10
 * @since 0.10
 */
public record ColumnRef(Optional<String> qualifier, String name) {
    /**
     * Creates a column reference.
     *
     * @param qualifier the optional table name or alias
     * @param name the column name
     * @throws NullPointerException if {@code qualifier} or {@code name} is null
     */
    public ColumnRef {
        Objects.requireNonNull(qualifier, "qualifier");
        Objects.requireNonNull(name, "name");
    }

    /**
     * Creates an unqualified column reference.
     *
     * @param name the column name
     * @throws NullPointerException if {@code name} is null
     */
    public ColumnRef(String name) {
        this(Optional.empty(), name);
    }

    /**
     * Returns the reference as written in SQL, such as {@code c1.c_city} or {@code c_city}.
     *
     * @return the qualified or plain column name
     */
    @Override
    public String toString() {
        return qualifier.map(table -> table + "." + name).orElse(name);
    }
}

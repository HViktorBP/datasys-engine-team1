package dk.itu.datasys;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents a DELETE statement with an optional typed predicate.
 *
 * @param tableName the table whose matching rows are removed
 * @param where the optional WHERE comparison, empty to remove every row
 * @author Team 1
 * @version 0.7
 * @since 0.7
 */
public record DeleteStatement(String tableName, Optional<Predicate> where) implements Statement {
    /**
     * Creates a delete statement with a required optional-predicate container.
     *
     * @param tableName the target table
     * @param where the optional typed predicate
     * @throws NullPointerException if {@code where} is null
     * @since 0.7
     * @version 0.7
     */
    public DeleteStatement { Objects.requireNonNull(where, "where"); }
}

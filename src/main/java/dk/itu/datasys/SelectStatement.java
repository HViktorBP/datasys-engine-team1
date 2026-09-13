package dk.itu.datasys;

import java.util.Optional;

/**
 * Represents a {@code SELECT *} statement with an optional filtering predicate.
 *
 * @param tableName the table to scan
 * @param where the optional predicate from the {@code WHERE} clause
 * @author Team 1
 * @version 0.3
 * @since 0.3
 */
public record SelectStatement(String tableName, Optional<Predicate> where)
        implements Statement { }

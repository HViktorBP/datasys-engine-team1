package dk.itu.datasys;

import java.util.List;

/**
 * Represents a {@code CREATE TABLE} statement and its ordered column definitions.
 *
 * @param tableName the name of the table to create
 * @param columns the ordered column definitions
 * @author Team 1
 * @version 0.3
 * @since 0.3
 */
public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement {
    /**
     * Creates a statement with an immutable snapshot of its column definitions.
     *
     * @param tableName the name of the table to create
     * @param columns the ordered column definitions
     * @throws NullPointerException if {@code columns} is {@code null}
     */
    public CreateTableStatement {
        columns = List.copyOf(columns);
    }
}

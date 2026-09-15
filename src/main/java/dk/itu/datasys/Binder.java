package dk.itu.datasys;

import java.util.HashSet;

/**
 * Performs the statement-specific semantic checks required before query planning.
 *
 * <p>For {@link CreateTableStatement}, the binder requires at least one column and rejects
 * duplicate column names, but deliberately defers the existing-table check. For
 * {@link CopyStatement}, it verifies only that the destination table exists; it does not access
 * the source file. For {@link SelectStatement}, it resolves the table and optional predicate
 * column and requires the constant's exact Java type.
 *
 * @author Team 1
 * @version 0.3
 * @since 0.3
 */
public final class Binder {
    /** Storage catalog used to resolve table schemas. */
    private final StorageEngine engine;

    /**
     * Creates a binder backed by the supplied storage engine.
     *
     * @param engine the engine whose catalog supplies table schemas
     * @throws IllegalArgumentException if {@code engine} is {@code null}
     */
    public Binder(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        this.engine = engine;
    }

    /**
     * Performs the supported semantic checks for a statement.
     *
     * @param statement the statement to validate
     * @throws IllegalArgumentException if the statement is {@code null}; a create statement has
     *                                  no columns or duplicate column names; a copy or select table
     *                                  is unknown; or a select predicate has an unknown column or
     *                                  incorrectly typed constant
     */
    public void bind(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement is required");
        }
        switch (statement) {
            case CreateTableStatement create -> bindCreate(create);
            case CopyStatement copy -> engine.schema(copy.tableName());
            case SelectStatement select -> bindSelect(select);
        }
    }

    /**
     * Validates the structural constraints of a create-table statement.
     *
     * @param statement the create-table statement to validate
     * @throws IllegalArgumentException if it has no columns or repeats a column name
     */
    private void bindCreate(CreateTableStatement statement) {
        if (statement.columns().isEmpty()) {
            throw new IllegalArgumentException("Table must have at least one column");
        }
        var names = new HashSet<String>();
        for (var column : statement.columns()) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("Duplicate column: " + column.name());
            }
        }
    }

    /**
     * Resolves and type-checks the optional predicate of a select statement.
     *
     * @param statement the select statement to validate
     * @throws IllegalArgumentException if the table or predicate column is unknown or the constant
     *                                  has the wrong type
     */
    private void bindSelect(SelectStatement statement) {
        var schema = engine.schema(statement.tableName());
        var where = statement.where();
        if (where.isEmpty()) {
            return;
        }

        var predicate = where.orElseThrow();
        var column = schema.stream()
                .filter(candidate -> candidate.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown column: " + predicate.columnName()));
        if (!column.type().accepts(predicate.constant())) {
            throw new IllegalArgumentException(
                    "Invalid constant type for " + column.type());
        }
    }
}

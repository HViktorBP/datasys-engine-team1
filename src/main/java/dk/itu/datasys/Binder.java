package dk.itu.datasys;

import dk.itu.datasys.CatalogStore.Table;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Performs the statement-specific semantic checks required before query planning.
 *
 * <p>For {@link CreateTableStatement}, the binder requires at least one column and rejects
 * duplicate column names, but deliberately defers the existing-table check. For
 * {@link CopyStatement}, it verifies only that the destination table exists; it does not access
 * the source file. For {@link SelectStatement} and {@link DeleteStatement}, it resolves the table and optional predicate
 * column and requires the constant's exact Java type.
 *
 * @author Team 1
 * @version 0.7
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
     *                                  no columns or duplicate column names; a copy, select or delete table
     *                                  is unknown; or a select/delete predicate has an unknown column or
     *                                  incorrectly typed constant
     */
    public void bind(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement is required");
        }
        var snapshot = switch (statement) {
            case CreateTableStatement ignored -> null;
            case CopyStatement copy -> engine.requireTable(copy.tableName());
            case SelectStatement select -> engine.requireTable(select.tableName());
            case DeleteStatement delete -> engine.requireTable(delete.tableName());
        };
        bind(statement, snapshot);
    }

    /**
     * Validates a statement exclusively against metadata captured before binding.
     *
     * @param statement the statement to validate
     * @param snapshot the captured table, or null for CREATE
     * @throws IllegalArgumentException if the statement or predicate is invalid
     * @since 0.7
     * @version 0.7
     */
    void bind(Statement statement, Table snapshot) {
        if (statement == null) throw new IllegalArgumentException("statement is required");
        switch (statement) {
            case CreateTableStatement create -> bindCreate(create);
            case CopyStatement ignored -> { }
            case SelectStatement select -> bindPredicate(snapshot.columns(), select.where());
            case DeleteStatement delete -> bindPredicate(snapshot.columns(), delete.where());
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
     * Resolves and type-checks an optional SELECT or DELETE comparison.
     *
     * @param schema the captured ordered schema
     * @param where the optional typed comparison
     * @throws IllegalArgumentException if the column is unknown or its constant has the wrong type
     */
    private void bindPredicate(List<ColumnSpec> schema, Optional<Predicate> where) {
        if (where.isEmpty()) return;
        var predicate = where.orElseThrow();
        var column = schema.stream()
                .filter(candidate -> candidate.name().equals(predicate.columnName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column: " + predicate.columnName()));
        if (!column.type().accepts(predicate.constant())) {
            throw new IllegalArgumentException("Invalid constant type for " + column.type());
        }
    }
}

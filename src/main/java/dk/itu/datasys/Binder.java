package dk.itu.datasys;

import dk.itu.datasys.CatalogStore.Table;
import java.util.HashSet;
import java.util.List;

/**
 * Performs the statement-specific semantic checks required before query planning.
 *
 * <p>For {@link CreateTableStatement}, the binder requires at least one column and rejects
 * duplicate column names, but deliberately defers the existing-table check. For
 * {@link CopyStatement}, it verifies only that the destination table exists; it does not access
 * the source file. For {@link SelectStatement} and {@link DeleteStatement}, it resolves every
 * column through a {@link ColumnResolver}, requires each filter constant to have the column's
 * exact Java type, and requires each join to compare columns of one type from two different
 * tables.
 *
 * @author Team 1
 * @version 0.10
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
     *                                  no columns or duplicate column names; a referenced table is
     *                                  unknown; a column is unknown or ambiguous; a constant has
     *                                  the wrong type; or a join is invalid
     * @version 0.10
     */
    public void bind(Statement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("statement is required");
        }
        var snapshots = switch (statement) {
            case CreateTableStatement ignored -> List.<Table>of();
            case CopyStatement copy -> List.of(engine.requireTable(copy.tableName()));
            case SelectStatement select -> engine.requireTables(select.tables());
            case DeleteStatement delete -> List.of(engine.requireTable(delete.tableName()));
        };
        bind(statement, snapshots);
    }

    /**
     * Validates a statement exclusively against metadata captured before binding.
     *
     * @param statement the statement to validate
     * @param snapshots the captured tables in the order the statement names them: none for
     *                  CREATE, one for COPY and DELETE, one per {@code FROM} entry for SELECT
     * @throws IllegalArgumentException if the statement or one of its predicates is invalid
     * @since 0.7
     * @version 0.10
     */
    void bind(Statement statement, List<Table> snapshots) {
        if (statement == null) throw new IllegalArgumentException("statement is required");
        switch (statement) {
            case CreateTableStatement create -> bindCreate(create);
            case CopyStatement ignored -> { }
            case SelectStatement select -> bindSelect(select, snapshots);
            case DeleteStatement delete -> {
                var resolver = new ColumnResolver(List.of(new TableRef(delete.tableName())), snapshots);
                delete.where().ifPresent(filter -> bindFilter(resolver, filter));
            }
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
     * Resolves every filter and join of a select statement.
     *
     * @param statement the select statement
     * @param snapshots the captured table of each {@code FROM} entry
     * @throws IllegalArgumentException if a name is duplicated, unknown or ambiguous, a constant
     *                                  has the wrong type, or a join is invalid
     */
    private void bindSelect(SelectStatement statement, List<Table> snapshots) {
        var resolver = new ColumnResolver(statement.tables(), snapshots);
        for (var filter : statement.filters()) {
            bindFilter(resolver, filter);
        }
        for (var join : statement.joins()) {
            var left = resolver.resolve(join.left());
            var right = resolver.resolve(join.right());
            if (left.table() == right.table()) {
                throw new IllegalArgumentException("Join needs two tables: " + join);
            }
            // The hash join matches keys with equals, so 3L and 3.0 would silently never match.
            if (left.type() != right.type()) {
                throw new IllegalArgumentException("Join needs two columns of one type: " + join);
            }
        }
    }

    /**
     * Resolves a filter's column and type-checks its constant.
     *
     * @param resolver the resolver for the statement's tables
     * @param filter the column-to-constant comparison
     * @throws IllegalArgumentException if the column is unknown or ambiguous, or the constant has
     *                                  the wrong type
     */
    private void bindFilter(ColumnResolver resolver, Predicate filter) {
        var type = resolver.resolve(filter.column()).type();
        if (!type.accepts(filter.constant())) {
            throw new IllegalArgumentException("Invalid constant type for " + type);
        }
    }
}

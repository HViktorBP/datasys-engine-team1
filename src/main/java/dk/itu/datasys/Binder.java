package dk.itu.datasys;

import java.util.HashSet;

public final class Binder {
    private final StorageEngine engine;

    public Binder(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        this.engine = engine;
    }

    /** Validates a statement against the catalog. */
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

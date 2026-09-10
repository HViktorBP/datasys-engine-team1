package dk.itu.datasys;

import java.util.List;

public record CreateTableStatement(String tableName, List<ColumnSpec> columns)
        implements Statement {
    public CreateTableStatement {
        columns = List.copyOf(columns);
    }
}

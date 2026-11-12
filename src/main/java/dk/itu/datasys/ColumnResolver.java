package dk.itu.datasys;

import dk.itu.datasys.CatalogStore.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Answers "which table owns this column, and where does it sit in the joined row?" for one
 * {@code FROM} list.
 *
 * <p>The joined row is the concatenation of the table schemas in {@code FROM}-list order. For
 * {@code FROM orders, customers}, {@code orders} fills positions 0 to 3 and {@code c_name} sits
 * at position 4. The binder uses this class to reject bad names and the planner uses it to find
 * column positions, so both always agree.
 *
 * <p>Unqualified names must match exactly one table. Qualified names use the
 * {@linkplain TableRef#exposedName() exposed name}, so an alias hides its table name. Names are
 * case-sensitive, like everywhere else in the engine, but two entries may not expose names that
 * differ only in case, because DuckDB rejects such duplicate aliases.
 *
 * @author Team 1
 * @version 0.10
 * @since 0.10
 */
final class ColumnResolver {
    /** The {@code FROM} list in source order. */
    private final List<TableRef> tables;
    /** The schema of each {@code FROM} entry, aligned with {@link #tables}. */
    private final List<List<ColumnSpec>> schemas;
    /** The position of each entry's first column in the joined row, aligned with {@link #tables}. */
    private final List<Integer> offsets = new ArrayList<>();

    /**
     * Where a column was found.
     *
     * @param table the position of the owning table in the {@code FROM} list
     * @param column the column's position inside its own table
     * @param rowIndex the column's position in the joined row
     * @param type the column's type
     */
    record Resolved(int table, int column, int rowIndex, ColumnType type) { }

    /**
     * Creates a resolver for a {@code FROM} list.
     *
     * @param tables the {@code FROM} list
     * @param snapshots the table snapshot for each entry, aligned with {@code tables}
     * @throws IllegalArgumentException if two entries expose the same name, ignoring case
     */
    ColumnResolver(List<TableRef> tables, List<Table> snapshots) {
        this.tables = List.copyOf(tables);
        this.schemas = snapshots.stream().map(Table::columns).toList();
        var exposedNames = new HashSet<String>();
        int offset = 0;
        for (int i = 0; i < tables.size(); i++) {
            String name = tables.get(i).exposedName();
            if (!exposedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate table name or alias in FROM: " + name
                        + "; give each occurrence a distinct alias");
            }
            offsets.add(offset);
            offset += schemas.get(i).size();
        }
    }

    /**
     * Finds the table and positions of a column reference.
     *
     * @param ref the column as written in the query
     * @return the owning table and the column's positions
     * @throws IllegalArgumentException if the qualifier names no {@code FROM} entry, the column
     *                                  is unknown, or an unqualified name occurs in several tables
     */
    Resolved resolve(ColumnRef ref) {
        if (ref.qualifier().isPresent()) {
            int table = tableNamed(ref.qualifier().get());
            var columns = schemas.get(table);
            for (int column = 0; column < columns.size(); column++) {
                if (columns.get(column).name().equals(ref.name())) {
                    return resolved(table, column);
                }
            }
            throw new IllegalArgumentException("Unknown column: " + ref);
        }
        Resolved found = null;
        for (int table = 0; table < tables.size(); table++) {
            var columns = schemas.get(table);
            for (int column = 0; column < columns.size(); column++) {
                if (columns.get(column).name().equals(ref.name())) {
                    if (found != null) {
                        throw new IllegalArgumentException("Ambiguous column: " + ref.name()
                                + "; qualify it with a table name or alias");
                    }
                    found = resolved(table, column);
                }
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Unknown column: " + ref.name());
        }
        return found;
    }

    /**
     * Finds the {@code FROM} entry with an exposed name.
     *
     * @param name the qualifier
     * @return the entry's position in the {@code FROM} list
     * @throws IllegalArgumentException if no entry exposes the name
     */
    private int tableNamed(String name) {
        for (int table = 0; table < tables.size(); table++) {
            if (tables.get(table).exposedName().equals(name)) {
                return table;
            }
        }
        throw new IllegalArgumentException("Unknown table or alias: " + name);
    }

    /**
     * Builds a result for a column of a table.
     *
     * @param table the table's position in the {@code FROM} list
     * @param column the column's position in its table
     * @return the result, including the joined-row position
     */
    private Resolved resolved(int table, int column) {
        return new Resolved(table, column, offsets.get(table) + column,
                schemas.get(table).get(column).type());
    }
}

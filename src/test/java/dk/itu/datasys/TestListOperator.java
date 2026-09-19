package dk.itu.datasys;

import java.util.Iterator;
import java.util.List;

/**
 * Serves a fixed list of rows to operator unit tests.
 */
final class TestListOperator implements Operator {
    private final List<Object[]> rows;
    private Iterator<Object[]> cursor;

    TestListOperator(List<Object[]> rows) {
        this.rows = List.copyOf(rows);
    }

    @Override
    public void open() {
        cursor = rows.iterator();
    }

    @Override
    public Object[] next() {
        return cursor.hasNext() ? cursor.next() : null;
    }

    @Override
    public void close() { }
}

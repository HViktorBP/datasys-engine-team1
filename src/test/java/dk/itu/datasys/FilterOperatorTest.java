package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterOperatorTest {
    @Test
    void emitsRowsThatPassEqualsLessThanAndGreaterThan() {
        var child = new TestListOperator(List.of(
                new Object[]{"A", 10L},
                new Object[]{"B", 20L},
                new Object[]{"C", 30L}));
        assertRows(new FilterOperator(child, 1, ColumnType.LONG, Comparison.EQUALS, 20L),
                new Object[]{"B", 20L});
        child = new TestListOperator(List.of(
                new Object[]{"A", 10L},
                new Object[]{"B", 20L},
                new Object[]{"C", 30L}));
        assertRows(new FilterOperator(child, 1, ColumnType.LONG, Comparison.LESS_THAN, 20L),
                new Object[]{"A", 10L});
        child = new TestListOperator(List.of(
                new Object[]{"A", 10L},
                new Object[]{"B", 20L},
                new Object[]{"C", 30L}));
        assertRows(new FilterOperator(child, 1, ColumnType.LONG, Comparison.GREATER_THAN, 20L),
                new Object[]{"C", 30L});
    }

    @Test
    void rejectsNonMatchingRowsAndEmptyChildren() {
        var child = new TestListOperator(List.<Object[]>of(new Object[]{"A", 10L}));
        assertRows(new FilterOperator(child, 1, ColumnType.LONG, Comparison.EQUALS, 99L));
        assertRows(new FilterOperator(new TestListOperator(List.of()), 0, ColumnType.LONG,
                Comparison.EQUALS, 1L));
    }

    private static void assertRows(Operator operator, Object[]... expected) {
        operator.open();
        try {
            var actual = new ArrayList<Object[]>();
            Object[] row;
            while ((row = operator.next()) != null) {
                actual.add(row);
            }
            assertNull(operator.next());
            assertArrayEquals(expected, actual.toArray(Object[][]::new));
        } finally {
            operator.close();
        }
    }
}

package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnEqualityFilterOperatorTest {
    @Test void keepsRowsWhoseTwoColumnsAreEqual() {
        var child = new TestListOperator(List.of(
                new Object[]{"Anna", "Copenhagen", "Anna", "Copenhagen"},
                new Object[]{"Anna", "Copenhagen", "Carol", "Copenhagen"},
                new Object[]{"Bob", "Aarhus", "Bob", "Aarhus"}));
        var rows = HashJoinOperatorTest.drain(new ColumnEqualityFilterOperator(child, 0, 2, ColumnType.STRING));
        assertArrayEquals(new Object[][]{
                {"Anna", "Copenhagen", "Anna", "Copenhagen"},
                {"Bob", "Aarhus", "Bob", "Aarhus"}}, rows.toArray(Object[][]::new));
    }

    @Test void comparesDoublesLikeOtherFilters() {
        var child = new TestListOperator(List.of(new Object[]{-0.0, 0.0}, new Object[]{1.0, 2.0}));
        var rows = HashJoinOperatorTest.drain(new ColumnEqualityFilterOperator(child, 0, 1, ColumnType.DOUBLE));
        assertArrayEquals(new Object[][]{{-0.0, 0.0}}, rows.toArray(Object[][]::new));
    }
}

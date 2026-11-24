package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CardinalityTest {
    @Test
    void tableNdvIsTheMaximumPartitionNdv() {
        assertEquals(7L, Cardinality.tableNdv(List.of(3L, 7L, 2L)));
        assertEquals(1L, Cardinality.tableNdv(List.of(1L)));
    }

    @Test
    void tableNdvRejectsEmptyNullAndNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> Cardinality.tableNdv(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Cardinality.tableNdv(null));
        assertThrows(IllegalArgumentException.class, () -> Cardinality.tableNdv(List.of(2L, 0L)));
        assertThrows(IllegalArgumentException.class, () -> Cardinality.tableNdv(java.util.Arrays.asList(2L, null)));
    }

    @Test
    void baseTableIsSurvivingRowCount() {
        assertEquals(0L, Cardinality.baseTable(0));
        assertEquals(12L, Cardinality.baseTable(12));
        assertThrows(IllegalArgumentException.class, () -> Cardinality.baseTable(-1));
    }

    @Test
    void equalityFilterIsRowsDividedByNdv() {
        assertEquals(4L, Cardinality.equalityFilter(12, 3));
        assertEquals(1L, Cardinality.equalityFilter(2, 5));
        assertEquals(0L, Cardinality.equalityFilter(0, 3));
    }

    @Test
    void rangeFilterUsesSelingerOneThird() {
        assertEquals(4L, Cardinality.rangeFilter(12));
        assertEquals(1L, Cardinality.rangeFilter(2));
        assertEquals(0L, Cardinality.rangeFilter(0));
    }

    @Test
    void equiJoinUsesLectureTenFormula() {
        assertEquals(4L, Cardinality.equiJoin(4, 3, 3, 3));
        assertEquals(4L, Cardinality.equiJoin(2, 4, 2, 2));
        assertEquals(2L, Cardinality.equiJoin(10, 2, 10, 2));
        assertEquals(0L, Cardinality.equiJoin(0, 10, 1, 1));
        assertEquals(Long.MAX_VALUE, Cardinality.equiJoin(Long.MAX_VALUE, 3, 1, 1));
    }

    @Test
    void missingNdvIsOne() {
        assertEquals(1L, Cardinality.missingNdv());
    }
}

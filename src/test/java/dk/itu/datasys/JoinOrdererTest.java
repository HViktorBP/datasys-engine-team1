package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JoinOrdererTest {
    /** a.a_k NDV 1, b.b_ak NDV 1, b.b_ck NDV 10, c.c_k NDV 2. */
    static final long[] ROWS = {100, 10, 2};
    static final long[][] NDV = {
            {1},
            {1, 10},
            {2}
    };
    static final List<JoinOrderer.Edge> EDGES = List.of(
            new JoinOrderer.Edge(0, 0, 1, 0),
            new JoinOrderer.Edge(1, 1, 2, 0));

    @Test
    void optimizedStartsWithTheCheapestEdgeAndNeverPicksACrossProduct() {
        var plan = JoinOrderer.optimized(ROWS, NDV, EDGES);
        assertEquals(List.of(2, 1, 0), plan.joinOrder());
        assertEquals(2, plan.steps().size());
        assertTrue(plan.steps().getFirst().leftBuilds());
        assertEquals(2L, plan.steps().getFirst().outEst());
        assertEquals(200L, plan.steps().get(1).outEst());
        assertTrue(plan.steps().get(1).leftBuilds());
        assertTrue(plan.checks().stream().anyMatch(check -> check.estRows() == 1000L));
        assertTrue(plan.checks().stream().anyMatch(check -> check.estRows() == 2L));
    }

    @Test
    void syntaxKeepsFromListOrderAndAlwaysBuildsOnTheLeft() {
        var plan = JoinOrderer.syntax(ROWS, NDV, EDGES);
        assertEquals(List.of(0, 1, 2), plan.joinOrder());
        assertEquals(1000L, plan.steps().getFirst().outEst());
        assertEquals(200L, plan.steps().get(1).outEst());
        for (var step : plan.steps()) {
            assertTrue(step.leftBuilds());
        }
    }

    @Test
    void optimizedBuildsTheSmallerEstimatedInput() {
        var plan = JoinOrderer.optimized(new long[]{100, 2}, new long[][]{{1}, {2}},
                List.of(new JoinOrderer.Edge(0, 0, 1, 0)));
        assertEquals(List.of(1, 0), plan.joinOrder());
        assertTrue(plan.steps().getFirst().leftBuilds());
        assertEquals(2L, plan.steps().getFirst().leftEst());
        assertEquals(100L, plan.steps().getFirst().rightEst());
    }

    @Test
    void syntaxRejectsADisconnectedFromList() {
        var error = assertThrows(IllegalArgumentException.class, () -> JoinOrderer.syntax(
                new long[]{4, 2, 3},
                new long[][]{{3}, {2}, {3}},
                List.of(new JoinOrderer.Edge(0, 0, 2, 0))));
        assertTrue(error.getMessage().contains("table 1"));
    }

    @Test
    void optimizedRejectsADisconnectedGraph() {
        assertThrows(IllegalArgumentException.class, () -> JoinOrderer.optimized(
                new long[]{4, 2, 3},
                new long[][]{{1}, {1}, {1}},
                List.of(new JoinOrderer.Edge(0, 0, 1, 0))));
    }

    @Test
    void optimizedDoesNotAddACrossProductWhileAConnectedTableExists() {
        var plan = JoinOrderer.optimized(
                new long[]{100, 50, 2},
                new long[][]{{1}, {1, 1}, {2}},
                List.of(new JoinOrderer.Edge(0, 0, 1, 0), new JoinOrderer.Edge(1, 1, 2, 0)));
        assertFalse(plan.joinOrder().equals(List.of(0, 2, 1)));
        assertEquals(2, plan.joinOrder().getFirst());
    }
}

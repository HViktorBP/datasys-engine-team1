package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class HashJoinOperatorTest {
    static final List<Object[]> ORDERS = List.of(
            new Object[]{100L, "Anna", "Bike", 1L},
            new Object[]{101L, "Bob", "Helmet", 2L},
            new Object[]{102L, "Carol", "Bike", 1L},
            new Object[]{103L, "Anna", "Helmet", 1L});
    static final List<Object[]> CUSTOMERS = List.of(
            new Object[]{"Anna", "Copenhagen"},
            new Object[]{"Bob", "Aarhus"},
            new Object[]{"Carol", "Copenhagen"});

    static HashJoinOperator join(List<Object[]> build, List<Object[]> probe, int buildKey, int probeKey) {
        return new HashJoinOperator(new TestListOperator(build), new TestListOperator(probe),
                buildKey, probeKey, "t");
    }

    @Test void emitsBuildColumnsThenProbeColumnsOnePerCall() {
        var join = join(ORDERS, CUSTOMERS, 1, 0);
        join.open();
        assertArrayEquals(new Object[]{100L, "Anna", "Bike", 1L, "Anna", "Copenhagen"}, join.next());
        assertArrayEquals(new Object[]{103L, "Anna", "Helmet", 1L, "Anna", "Copenhagen"}, join.next());
        assertArrayEquals(new Object[]{101L, "Bob", "Helmet", 2L, "Bob", "Aarhus"}, join.next());
        assertArrayEquals(new Object[]{102L, "Carol", "Bike", 1L, "Carol", "Copenhagen"}, join.next());
        assertNull(join.next());
        assertNull(join.next());
        join.close();
    }

    @Test void duplicateKeysOnBothSidesMultiply() {
        var build = List.of(new Object[]{"k", 1L}, new Object[]{"k", 2L});
        var probe = List.of(new Object[]{"k", "a"}, new Object[]{"k", "b"}, new Object[]{"k", "c"});
        assertEquals(6, drain(join(build, probe, 0, 0)).size());
    }

    @Test void dropsRowsWithoutAPartner() {
        var build = List.of(new Object[]{1L, "only build"}, new Object[]{2L, "both"});
        var probe = List.of(new Object[]{2L, "both"}, new Object[]{3L, "only probe"});
        var rows = drain(join(build, probe, 0, 0));
        assertEquals(1, rows.size());
        assertArrayEquals(new Object[]{2L, "both", 2L, "both"}, rows.getFirst());
    }

    @Test void emptyBuildOrProbeSideJoinsToNothing() {
        assertEquals(0, drain(join(List.of(), CUSTOMERS, 1, 0)).size());
        assertEquals(0, drain(join(ORDERS, List.of(), 1, 0)).size());
    }

    @Test void negativeZeroMatchesPositiveZero() {
        var build = List.<Object[]>of(new Object[]{-0.0});
        var probe = List.<Object[]>of(new Object[]{0.0});
        assertEquals(1, drain(join(build, probe, 0, 0)).size());
    }

    @Test void reopeningAfterPartialUseStartsAgain() {
        var join = join(ORDERS, CUSTOMERS, 1, 0);
        join.open();
        join.next();
        join.close();
        assertEquals(4, drain(join).size());
    }

    @Test void closeLogsTheJoinSummary() throws Exception {
        String session = UUID.randomUUID().toString();
        var previous = MDC.getCopyOfContextMap();
        try {
            MDC.put("sessionId", session);
            drain(new HashJoinOperator(new TestListOperator(ORDERS), new TestListOperator(CUSTOMERS),
                    1, 0, "orders;customers"));
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
        var messages = Files.readAllLines(Path.of("logs/engine.log")).stream()
                .map(line -> line.split(",", -1))
                .filter(fields -> fields[1].equals(session) && fields[5].equals("HashJoinOperator"))
                .map(fields -> fields[6])
                .toList();
        assertEquals(List.of("tables=orders;customers rowsBuild=4 keys=3 rowsProbe=3 rowsOut=4"), messages);
    }

    static List<Object[]> drain(Operator operator) {
        var rows = new ArrayList<Object[]>();
        operator.open();
        try {
            Object[] row;
            while ((row = operator.next()) != null) {
                rows.add(row);
            }
        } finally {
            operator.close();
        }
        return rows;
    }
}

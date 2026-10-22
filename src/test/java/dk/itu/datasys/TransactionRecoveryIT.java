package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies recovery from actual JVM termination on both sides of the disk commit point.
 *
 * @author Team 1
 * @version 0.7
 * @since 0.7
 */
class TransactionRecoveryIT {
    @TempDir Path dir;

    @Test void processTerminationBeforePublicationLeavesOldVersionAndCleansOrphans() throws Exception {
        exerciseCrash(false);
    }

    @Test void processTerminationAfterPublicationLoadsCommittedVersionBeforeHeapInstallation() throws Exception {
        exerciseCrash(true);
    }

    /**
     * Halts a child writer immediately before or after its atomic catalog move.
     *
     * @param after whether to halt after the disk commit point
     * @throws Exception if process execution or filesystem access fails
     */
    private void exerciseCrash(boolean after) throws Exception {
        var engine = new StorageEngine(dir, 1);
        engine.createTable("t", List.of(new ColumnSpec("id", ColumnType.LONG)));
        var csv = Files.writeString(dir.resolve("input.csv"), "1\n");
        engine.copyFile("t", csv.toString());
        Files.writeString(csv, "2\n3\n");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), TransactionRecoveryIT.class.getName(),
                dir.toString(), Boolean.toString(after))
                .redirectErrorStream(true).redirectOutput(dir.resolve("child.log").toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child writer timed out");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertEquals(37, process.exitValue(), Files.readString(dir.resolve("child.log")));
        try (var paths = Files.list(dir.resolve("data"))) { assertEquals(3, paths.count()); }
        var recovered = new StorageEngine(dir);
        assertEquals(after ? 2 : 1, recovered.requireTable("t").current().tableVersion());
        assertEquals(after ? 1 : 0, recovered.requireTable("t").previous().tableVersion());
        try (var paths = Files.list(dir.resolve("data"))) { assertEquals(after ? 3 : 1, paths.count()); }
        try (var paths = Files.list(dir.resolve("catalog"))) {
            assertTrue(paths.allMatch(path -> path.toString().endsWith(".json")));
        }
        var plan = new Planner(recovered).plan(new SelectStatement("t", Optional.empty()));
        var rows = new ArrayList<Long>();
        try {
            plan.root().open();
            Object[] row;
            while ((row = plan.root().next()) != null) rows.add((Long) row[0]);
        } finally { plan.root().close(); }
        assertEquals(after ? List.of(1L, 2L, 3L) : List.of(1L), rows);
    }

    /**
     * Runs the deliberately terminated child writer used by these integration tests.
     *
     * @param args the storage root and whether to terminate after publication
     */
    public static void main(String[] args) {
        boolean after = Boolean.parseBoolean(args[1]);
        var io = new StorageIo() {
            /** {@inheritDoc} */
            @Override void publish(Path source, Path target) throws IOException {
                if (after) super.publish(source, target);
                Runtime.getRuntime().halt(37);
            }
        };
        new StorageEngine(Path.of(args[0]), 1, io).copyFile("t", Path.of(args[0], "input.csv").toString());
        throw new AssertionError("writer must terminate at publication");
    }
}

package dk.itu.datasys;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import dk.itu.datasys.CatalogStore.DataFile;
import dk.itu.datasys.StorageEngine.CopyResult;
import dk.itu.datasys.StorageEngine.DeleteResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Forces the interleavings of concurrent statements that matter, without sleeps.
 *
 * <p>A writer parks in {@link StorageEngine#beforeCommit} between its work and its commit while a
 * second statement runs, either on another thread or nested in the hook on the same thread.
 * Every wait has a timeout, so a broken engine fails a test instead of hanging the build.
 */
@Timeout(value = 30, unit = SECONDS)
class ConcurrencyTest {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(new ColumnSpec("id", ColumnType.LONG),
            new ColumnSpec("name", ColumnType.STRING), new ColumnSpec("price", ColumnType.DOUBLE));
    /** Runs the writer that parks; the test thread plays the winner. */
    ExecutorService loserThread;
    /** Distinguishes the CSV files a test writes. */
    int inputs;

    @BeforeEach void startLoserThread() {
        loserThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach void stopLoserThread() {
        loserThread.shutdownNow();
    }

    /** Opens an engine with two rows per partition and creates table {@code name} at version 0. */
    StorageEngine engine(String name) {
        var engine = new StorageEngine(dir, 2);
        engine.createTable(name, schema);
        return engine;
    }

    /** Copies CSV {@code content} into {@code table}. */
    CopyResult copy(StorageEngine engine, String table, String content) {
        try {
            Path csv = Files.writeString(dir.resolve("input-" + inputs++ + ".csv"), content);
            return engine.copyFile(table, csv.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the ids of {@code table}'s current version in scan order. */
    static List<Long> ids(StorageEngine engine, String table) {
        return drain(new Planner(engine).plan(new SelectStatement(table, Optional.empty())).root());
    }

    /** Opens, drains and closes an operator, returning the id column. */
    static List<Long> drain(Operator root) {
        root.open();
        return rest(root);
    }

    /** Drains an already opened operator and closes it, returning the id column. */
    static List<Long> rest(Operator root) {
        var ids = new ArrayList<Long>();
        try {
            Object[] row;
            while ((row = root.next()) != null) ids.add((Long) row[0]);
        } finally {
            root.close();
        }
        return ids;
    }

    static Optional<Predicate> idIs(long id) {
        return Optional.of(new Predicate("id", Comparison.EQUALS, id));
    }

    /** Waits for a latch, failing the calling thread after five seconds. */
    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, SECONDS), "timed out waiting for the other writer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * Parks the next commit of {@code engine} in its hook.
     *
     * @param parked counted down once the writer sits right before its validation
     * @param release counted down by the test to let the writer validate
     */
    record Park(CountDownLatch parked, CountDownLatch release) { }

    static Park parkNextCommit(StorageEngine engine) {
        var park = new Park(new CountDownLatch(1), new CountDownLatch(1));
        engine.beforeCommit = () -> {
            park.parked().countDown();
            await(park.release());
        };
        return park;
    }

    /** Returns the partition files on disk, relative to the storage root. */
    Set<String> dataFiles() throws IOException {
        try (Stream<Path> paths = Files.list(dir.resolve("data"))) {
            return new HashSet<>(paths.map(path -> "data/" + path.getFileName()).toList());
        }
    }

    /** Returns the partition files {@code table}'s current and previous versions reference. */
    static Set<String> retainedFiles(StorageEngine engine, String table) {
        var current = engine.requireTable(table);
        var files = new HashSet<String>();
        current.current().files().stream().map(DataFile::path).forEach(files::add);
        if (current.previous() != null) current.previous().files().stream().map(DataFile::path).forEach(files::add);
        return files;
    }

    @Test void openScanReturnsItsPinnedRowsAfterADelete() {
        var engine = engine("t");
        copy(engine, "t", "1,A,1.0\n2,B,2.0\n3,C,3.0\n4,D,4.0\n");
        var scan = new Planner(engine).plan(new SelectStatement("t", Optional.empty())).root();
        scan.open();
        engine.delete("t", idIs(1));
        assertEquals(List.of(1L, 2L, 3L, 4L), rest(scan));
        assertEquals(List.of(2L, 3L, 4L), ids(engine, "t"));
    }

    @Test void openScanKeepsItsFilesAcrossSeveralCommits() {
        var engine = engine("t");
        copy(engine, "t", "1,A,1.0\n2,B,2.0\n3,C,3.0\n4,D,4.0\n");
        var scan = new Planner(engine).plan(new SelectStatement("t", Optional.empty())).root();
        scan.open();
        // Three commits replace or remove every partition the scan pinned before it reads them.
        engine.delete("t", idIs(1));
        engine.delete("t", idIs(2));
        engine.delete("t", idIs(3));
        copy(engine, "t", "");
        assertEquals(List.of(1L, 2L, 3L, 4L), rest(scan));
        assertEquals(List.of(4L), ids(engine, "t"));
    }

    @Test void parkedDeleteRebasesOntoACopyThatCommittedFirst() throws Exception {
        var engine = engine("t");
        copy(engine, "t", "1,A,1.0\n2,B,2.0\n3,C,3.0\n4,D,4.0\n");
        var park = parkNextCommit(engine);
        Future<DeleteResult> loser = loserThread.submit(() -> engine.delete("t", idIs(1)));
        await(park.parked());
        engine.beforeCommit = () -> { };
        // The winner adds another row with id 1: the delete never saw it, so it survives.
        assertEquals(2, copy(engine, "t", "1,E,5.0\n5,F,6.0\n").version());
        park.release().countDown();
        var result = loser.get(5, SECONDS);
        assertEquals(3, result.version());
        assertEquals(1, result.rowsDeleted());
        assertEquals(List.of(2L, 3L, 4L, 1L, 5L), ids(engine, "t"));
        assertEquals(List.of(2L, 3L, 4L, 1L, 5L), ids(new StorageEngine(dir, 2), "t"));
    }

    @Test void deleteRebasesOverACopyNestedInItsHookOnOneThread() {
        var engine = engine("t");
        copy(engine, "t", "1,A,1.0\n2,B,2.0\n3,C,3.0\n4,D,4.0\n");
        engine.beforeCommit = () -> {
            engine.beforeCommit = () -> { };
            copy(engine, "t", "5,E,5.0\n");
        };
        var result = engine.delete("t", idIs(1));
        assertEquals(3, result.version());
        assertEquals(List.of(2L, 3L, 4L, 5L), ids(engine, "t"));
    }

    @Test void parkedCopyRebasesOntoACopyThatCommittedFirst() throws Exception {
        var engine = engine("t");
        copy(engine, "t", "1,A,1.0\n");
        var park = parkNextCommit(engine);
        Future<CopyResult> loser = loserThread.submit(() -> copy(engine, "t", "2,B,2.0\n3,C,3.0\n"));
        await(park.parked());
        engine.beforeCommit = () -> { };
        var staged = dataFiles();
        staged.removeAll(retainedFiles(engine, "t"));
        assertEquals(1, staged.size(), "the parked copy has staged its partition");
        assertEquals(2, copy(engine, "t", "4,D,4.0\n").version());
        assertTrue(dataFiles().containsAll(staged), "the winner's commit must keep the parked copy's files");
        park.release().countDown();
        var result = loser.get(5, SECONDS);
        assertEquals(3, result.version());
        assertEquals(2, result.rows());
        assertEquals(List.of(1L, 4L, 2L, 3L), ids(engine, "t"));
        assertEquals(List.of(1L, 4L, 2L, 3L), ids(new StorageEngine(dir, 2), "t"));
    }

    @Test void deletesRewritingTheSamePartitionConflictAndTheLoserCanRerun() throws Exception {
        var engine = engine("t");
        copy(engine, "t", "1,A,1.0\n2,B,2.0\n3,C,3.0\n4,D,4.0\n");
        var park = parkNextCommit(engine);
        Future<DeleteResult> loser = loserThread.submit(() -> engine.delete("t", idIs(1)));
        await(park.parked());
        engine.beforeCommit = () -> { };
        assertEquals(2, engine.delete("t", idIs(2)).version());
        var winner = engine.requireTable("t");
        park.release().countDown();

        var failure = assertThrows(ExecutionException.class, () -> loser.get(5, SECONDS));
        var conflict = assertInstanceOf(ConflictException.class, failure.getCause());
        assertEquals("t", conflict.table());
        assertEquals(1, conflict.baseVersion());
        assertEquals(2, conflict.currentVersion());
        assertSame(winner, engine.requireTable("t"));
        assertEquals(List.of(1L, 3L, 4L), ids(engine, "t"));
        assertEquals(retainedFiles(engine, "t"), dataFiles(), "the loser's staged files are removed");

        var rerun = engine.delete("t", idIs(1));
        assertEquals(3, rerun.version());
        assertEquals(List.of(3L, 4L), ids(engine, "t"));
    }

    @Test void deletesRewritingDifferentPartitionsBothCommit() throws Exception {
        var engine = engine("t");
        copy(engine, "t", "1,A,1.0\n2,B,2.0\n3,C,3.0\n4,D,4.0\n");
        var park = parkNextCommit(engine);
        Future<DeleteResult> loser = loserThread.submit(() -> engine.delete("t", idIs(1)));
        await(park.parked());
        engine.beforeCommit = () -> { };
        assertEquals(2, engine.delete("t", idIs(3)).version());
        park.release().countDown();
        assertEquals(3, loser.get(5, SECONDS).version());
        assertEquals(List.of(2L, 4L), ids(engine, "t"));
    }

    @Test void conflictLeavesAnErrorLineInTheLog() throws IOException {
        String table = "conflict_" + UUID.randomUUID().toString().replace("-", "");
        var engine = engine(table);
        copy(engine, table, "1,A,1.0\n2,B,2.0\n");
        engine.beforeCommit = () -> {
            engine.beforeCommit = () -> { };
            engine.delete(table, idIs(2));
        };
        assertThrows(ConflictException.class, () -> engine.delete(table, idIs(1)));
        var errors = Files.readAllLines(Path.of("logs/engine.log")).stream()
                .map(line -> line.split(",", -1))
                .filter(fields -> fields.length == 7 && fields[4].equals("ERROR")
                        && fields[6].equals("conflict table=" + table + " base=1 current=2"))
                .toList();
        assertEquals(1, errors.size());
    }
}

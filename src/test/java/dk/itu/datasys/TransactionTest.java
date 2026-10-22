package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies statement snapshots, version retention and copy-on-write publication. */
class TransactionTest {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(new ColumnSpec("id", ColumnType.LONG),
            new ColumnSpec("name", ColumnType.STRING), new ColumnSpec("price", ColumnType.DOUBLE));

    StorageEngine empty() {
        var engine = new StorageEngine(dir, 2);
        engine.createTable("t", schema);
        return engine;
    }

    Path csv(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    void copy(StorageEngine engine, String content) throws IOException {
        engine.copyFile("t", csv("input.csv", content).toString());
    }

    List<Long> ids(StorageEngine engine) {
        return drain(new Planner(engine).plan(new SelectStatement("t", Optional.empty())).root());
    }

    List<Long> drain(Operator root) {
        var result = new ArrayList<Long>();
        try {
            root.open();
            Object[] row;
            while ((row = root.next()) != null) result.add((Long) row[0]);
        } finally {
            root.close();
        }
        return result;
    }

    Optional<Predicate> where(long id) {
        return Optional.of(new Predicate("id", Comparison.EQUALS, id));
    }

    @Test void versionsZeroThroughThreeAndRestartRetainBothStates() throws Exception {
        var e = empty();
        assertEquals(0, e.requireTable("t").current().tableVersion());
        assertNull(e.requireTable("t").previous());
        copy(e, "1,A,1.0\n2,B,2.0\n3,C,3.0\n");
        assertEquals(List.of(1L, 2L, 3L), ids(e));
        assertEquals(1, e.requireTable("t").current().tableVersion());
        copy(e, "4,D,4.0\n5,E,5.0\n");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(e));
        assertEquals(2, e.requireTable("t").current().tableVersion());
        var before = e.requireTable("t");
        var result = e.delete("t", where(2));
        assertEquals(1, result.rowsDeleted());
        assertEquals(1, result.partitionsRewritten());
        assertEquals(2, result.partitionsCarried());
        assertEquals(0, result.partitionsRemoved());
        assertEquals(3, result.version());
        assertEquals(List.of(1L, 3L, 4L, 5L), ids(e));
        var restarted = new StorageEngine(dir);
        assertEquals(e.requireTable("t"), restarted.requireTable("t"));
        assertEquals(before.current(), restarted.requireTable("t").previous());
        assertEquals(List.of(1L, 3L, 4L, 5L), ids(restarted));
        var json = new ObjectMapper().readTree(dir.resolve("catalog/" + before.id() + ".json").toFile());
        assertEquals(2, json.get("version").asInt());
        assertEquals(3, json.get("current").get("tableVersion").asLong());
        assertEquals(2, json.get("previous").get("tableVersion").asLong());
    }

    @Test void emptyCopiesCommitWithoutFilesAndNoOpDeletePreservesHistory() throws Exception {
        var e = empty();
        copy(e, "");
        copy(e, "");
        var before = e.requireTable("t");
        assertEquals(2, before.current().tableVersion());
        assertTrue(before.files().isEmpty());
        assertEquals(0, e.delete("t", Optional.empty()).rowsDeleted());
        assertSame(before, e.requireTable("t"));
        try (var paths = Files.list(dir.resolve("data"))) { assertEquals(0, paths.count()); }
        assertEquals(before, new StorageEngine(dir).requireTable("t"));
    }

    @Test void malformedSecondCopyRollsBackOnlyItsOwnFiles() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n2,B,2.0\n");
        var before = e.requireTable("t");
        assertThrows(IllegalArgumentException.class,
                () -> copy(e, "3,C,3.0\n4,D,4.0\nbad,E,5.0\n"));
        assertSame(before, e.requireTable("t"));
        assertEquals(List.of(1L, 2L), ids(new StorageEngine(dir)));
        try (var paths = Files.list(dir.resolve("data"))) { assertEquals(1, paths.count()); }
        copy(e, "3,C,3.0\n");
        assertEquals(List.of(1L, 2L, 3L), ids(e));
    }

    @Test void falsePositiveCarriesOriginalAndWholeMatchRemovesWithoutEmptyFile() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n3,C,3.0\n");
        var before = e.requireTable("t");
        var noMatch = e.delete("t", where(2));
        assertEquals(0, noMatch.rowsDeleted());
        assertEquals(1, noMatch.partitionsCarried());
        assertSame(before, e.requireTable("t"));
        var all = e.delete("t", Optional.of(new Predicate("id", Comparison.LESS_THAN, 4L)));
        assertEquals(2, all.rowsDeleted());
        assertEquals(1, all.partitionsRemoved());
        assertEquals(0, all.partitionsRewritten());
        assertTrue(e.requireTable("t").files().isEmpty());
        assertTrue(Files.exists(dir.resolve(before.files().getFirst().path())));
        copy(e, ""); // Retires the last version that still owns the old file.
        assertFalse(Files.exists(dir.resolve(before.files().getFirst().path())));
    }

    @Test void prunedDeleteNeverOpensCarriedFilesAndUnfilteredDeleteDoesNotDecode() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n2,B,2.0\n10,J,10.0\n11,K,11.0\n");
        var files = e.requireTable("t").files();
        Files.delete(dir.resolve(files.getFirst().path()));
        var result = e.delete("t", where(10));
        assertEquals(1, result.rowsDeleted());
        assertEquals(1, result.partitionsCarried());
        assertEquals(files.getFirst(), e.requireTable("t").files().getFirst());
        for (var file : e.requireTable("t").files()) Files.deleteIfExists(dir.resolve(file.path()));
        assertEquals(3, e.delete("t", Optional.empty()).rowsDeleted());
        assertEquals(List.of(), ids(e));
    }

    @Test void plansPinMetadataBeforeOpenAndExplicitBindingUsesCapturedSchema() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n2,B,2.0\n");
        var captured = e.requireTable("t");
        var statement = new SelectStatement("t", Optional.empty());
        var plan = new Planner(e).plan(statement);
        copy(e, "3,C,3.0\n");
        assertEquals(List.of(1L, 2L), drain(plan.root()));
        assertEquals(List.of(1L, 2L), drain(new Planner(e).plan(statement, captured).root()));
        assertEquals(List.of(1L, 2L, 3L), ids(e));
        new Binder(e).bind(new DeleteStatement("t", where(1)), captured);
        assertThrows(UnsupportedOperationException.class, () -> captured.current().files().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> captured.files().getFirst().partitions().getFirst().chunks().clear());
    }

    @Test void deleteParsingAndBindingRejectInvalidPredicates() {
        var e = empty();
        var parsed = new SqlParser().parse("delete from t; DELETE FROM t WHERE id = 1;");
        assertEquals(new DeleteStatement("t", Optional.empty()), parsed.getFirst());
        assertEquals(new DeleteStatement("t", where(1)), parsed.getLast());
        var binder = new Binder(e);
        binder.bind(parsed.getFirst());
        binder.bind(parsed.getLast());
        for (var bad : List.of(new DeleteStatement("missing", Optional.empty()),
                new DeleteStatement("t", Optional.of(new Predicate("missing", Comparison.EQUALS, 1L))),
                new DeleteStatement("t", Optional.of(new Predicate("id", Comparison.EQUALS, "1"))))) {
            assertThrows(IllegalArgumentException.class, () -> binder.bind(bad));
        }
    }

    @Test void failedPublicationKeepsDiskAndHeapAndDeletesOnlyStagedFiles() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n2,B,2.0\n");
        var before = e.requireTable("t");
        var io = new StorageIo() {
            @Override void publish(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
            }
        };
        var failing = new StorageEngine(dir, 2, io);
        assertThrows(UncheckedIOException.class, () -> failing.delete("t", where(1)));
        assertEquals(before, failing.requireTable("t"));
        assertThrows(UncheckedIOException.class, () -> copy(failing, "3,C,3.0\n"));
        assertEquals(before, new StorageEngine(dir).requireTable("t"));
        assertEquals(List.of(1L, 2L), ids(failing));
        try (var paths = Files.list(dir.resolve("data"))) { assertEquals(1, paths.count()); }
    }

    @Test void postCommitCleanupFailuresCannotReportRollbackOrRemoveCommittedFiles() throws Exception {
        var io = new StorageIo() {
            @Override void delete(Path path) throws IOException { throw new IOException("injected cleanup failure"); }
        };
        var e = new StorageEngine(dir, 2, io);
        e.createTable("t", schema);
        copy(e, "1,A,1.0\n2,B,2.0\n");
        var old = e.requireTable("t").files().getFirst();
        e.delete("t", where(1));
        copy(e, "3,C,3.0\n");
        assertEquals(3, e.requireTable("t").current().tableVersion());
        assertEquals(List.of(2L, 3L), ids(e));
        assertTrue(Files.exists(dir.resolve(old.path())));
        assertEquals(List.of(2L, 3L), ids(new StorageEngine(dir)));
        assertFalse(Files.exists(dir.resolve(old.path())));
    }

    @Test void startupCleansOnlyRecognizedOrphansAfterAllCatalogsValidate() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n");
        Path orphan = dir.resolve("data/" + UUID.randomUUID() + "-" + UUID.randomUUID() + ".bin");
        Files.writeString(orphan, "orphan");
        Path unrelated = Files.writeString(dir.resolve("data/notes.bin"), "keep");
        Path invalid = Files.writeString(dir.resolve("catalog/broken.json"), "{}");
        assertThrows(UncheckedIOException.class, () -> new StorageEngine(dir));
        assertTrue(Files.exists(orphan));
        Files.delete(invalid);
        assertEquals(List.of(1L), ids(new StorageEngine(dir)));
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(unrelated));
    }

    @Test void oldCatalogFormatIsExplicitlyUnsupported() throws Exception {
        empty();
        Path catalog;
        try (var paths = Files.list(dir.resolve("catalog"))) { catalog = paths.findFirst().orElseThrow(); }
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(catalog.toFile());
        tree.put("version", 1);
        tree.remove("current");
        tree.remove("previous");
        tree.putArray("files");
        Files.writeString(catalog, tree.toString());
        var failure = assertThrows(UncheckedIOException.class, () -> new StorageEngine(dir));
        assertTrue(failure.getCause().getMessage().contains("Unsupported catalog format"));
        var error = new java.io.ByteArrayOutputStream();
        assertEquals(1, Engine.run(new String[]{"-c", "SELECT * FROM t"}, dir,
                new java.io.PrintStream(new java.io.ByteArrayOutputStream()), new java.io.PrintStream(error)));
        assertTrue(error.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Unsupported catalog format"));
    }

    @Test void versionOverflowFailsBeforePublicationAndLeavesStateIntact() throws Exception {
        var e = empty();
        var table = e.requireTable("t");
        new CatalogStore(dir).save(new CatalogStore.Table(2, table.id(), table.name(), table.columns(),
                new CatalogStore.Snapshot(Long.MAX_VALUE, List.of()),
                new CatalogStore.Snapshot(Long.MAX_VALUE - 1, List.of())));
        var reopened = new StorageEngine(dir);
        assertThrows(ArithmeticException.class, () -> copy(reopened, "1,A,1.0\n"));
        assertEquals(Long.MAX_VALUE, new StorageEngine(dir).requireTable("t").current().tableVersion());
        assertEquals(0, reopened.delete("t", Optional.empty()).rowsDeleted());
    }
    @Test void uncheckedCleanupFailureCannotUndoPublishedCommit() throws Exception {
        var io = new StorageIo() {
            @Override void delete(Path path) {
                throw new UncheckedIOException(new IOException("unchecked cleanup failure"));
            }
        };
        var e = new StorageEngine(dir, 2, io);
        assertDoesNotThrow(() -> e.createTable("t", schema));
        copy(e, "1,A,1.0\n2,B,2.0\n");
        assertEquals(1, e.delete("t", where(1)).rowsDeleted());
        copy(e, "3,C,3.0\n");
        assertEquals(List.of(2L, 3L), ids(new StorageEngine(dir)));
    }

    @Test void sharedFileSurvivesWhileEitherRetainedVersionReferencesAnyOfItsPartitions() throws Exception {
        var e = empty();
        var initial = e.requireTable("t");
        String relative = "data/" + initial.id() + "-" + UUID.randomUUID() + ".bin";
        List<CatalogStore.Partition> partitions = new ArrayList<>();
        try (var output = new java.io.RandomAccessFile(dir.resolve(relative).toFile(), "rw")) {
            BinaryColumnCodec.writeHeader(output);
            for (Object[] row : List.of(new Object[]{1L, "A", 1.0}, new Object[]{2L, "B", 2.0})) {
                List<CatalogStore.Chunk> chunks = new ArrayList<>();
                for (int column = 0; column < schema.size(); column++) {
                    long offset = output.getFilePointer();
                    BinaryColumnCodec.writeValue(output, schema.get(column).type(), row[column]);
                    chunks.add(new CatalogStore.Chunk(offset, output.getFilePointer() - offset,
                            PartitionStatistics.compute(schema.get(column).type(), List.of(row[column]))));
                }
                partitions.add(new CatalogStore.Partition(1, chunks));
            }
        }
        var shared = new CatalogStore.DataFile(relative, partitions);
        new CatalogStore(dir).save(new CatalogStore.Table(2, initial.id(), "t", schema,
                new CatalogStore.Snapshot(1, List.of(shared)), initial.current()));
        e = new StorageEngine(dir, 2);
        assertEquals(List.of(1L, 2L), ids(e));
        e.delete("t", where(1));
        var carried = e.requireTable("t").files().getFirst();
        assertEquals(relative, carried.path());
        assertEquals(List.of(partitions.getLast()), carried.partitions());
        copy(e, "");
        assertEquals(List.of(2L), ids(new StorageEngine(dir)));
        assertTrue(Files.exists(dir.resolve(relative)));
        e.delete("t", Optional.empty());
        assertTrue(Files.exists(dir.resolve(relative)));
        copy(e, "");
        assertFalse(Files.exists(dir.resolve(relative)));
    }

    @Test void allTypedDeleteComparisonsAgreeWithSelectIncludingSignedZero() throws Exception {
        int run = 0;
        for (String column : List.of("id", "name", "price")) {
            for (Comparison comparison : Comparison.values()) {
                var e = new StorageEngine(dir.resolve("case" + run++), 2);
                e.createTable("t", schema);
                copy(e, "1,A,-0.0\n2,B,0.0\n3,C,3.0\n4,D,4.0\n");
                Object constant = switch (column) {
                    case "id" -> 2L;
                    case "name" -> "B";
                    default -> 0.0;
                };
                var predicate = Optional.of(new Predicate(column, comparison, constant));
                var select = new SelectStatement("t", predicate);
                new Binder(e).bind(select);
                var matches = drain(new Planner(e).plan(select).root());
                var expected = ids(e).stream().filter(id -> !matches.contains(id)).toList();
                assertEquals(matches.size(), e.delete("t", predicate).rowsDeleted());
                assertEquals(expected, ids(new StorageEngine(dir.resolve("case" + (run - 1)))));
            }
        }
    }

    @Test void nestedMetadataDefensivelyCopiesEverySourceList() {
        var chunks = new ArrayList<>(List.of(new CatalogStore.Chunk(8, 8, new PartitionStatistics("1", "1"))));
        var partition = new CatalogStore.Partition(1, chunks);
        chunks.clear();
        var partitions = new ArrayList<>(List.of(partition));
        var file = new CatalogStore.DataFile("data/test.bin", partitions);
        partitions.clear();
        var files = new ArrayList<>(List.of(file));
        var snapshot = new CatalogStore.Snapshot(1, files);
        files.clear();
        assertEquals(1, snapshot.files().getFirst().partitions().getFirst().chunks().size());
        assertThrows(UnsupportedOperationException.class, () -> file.partitions().clear());
    }

    @Test void changingDeletesAtVersionOverflowLeaveCatalogAndFilesIntact() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n2,B,2.0\n");
        var table = e.requireTable("t");
        var overflowing = new CatalogStore.Table(2, table.id(), "t", schema,
                new CatalogStore.Snapshot(Long.MAX_VALUE, table.files()),
                new CatalogStore.Snapshot(Long.MAX_VALUE - 1, table.files()));
        new CatalogStore(dir).save(overflowing);
        var reopened = new StorageEngine(dir);
        assertThrows(ArithmeticException.class, () -> reopened.delete("t", where(1)));
        assertThrows(ArithmeticException.class, () -> reopened.delete("t", Optional.empty()));
        assertEquals(overflowing, reopened.requireTable("t"));
        assertEquals(overflowing, new StorageEngine(dir).requireTable("t"));
        assertEquals(List.of(1L, 2L), ids(reopened));
        try (var paths = Files.list(dir.resolve("data"))) { assertEquals(1, paths.count()); }
    }

    @Test void deleteReadFailureAfterStagingEarlierReplacementRollsBackOnlyNewFiles() throws Exception {
        var e = empty();
        copy(e, "1,A,1.0\n2,B,2.0\n3,C,3.0\n4,D,4.0\n");
        var before = e.requireTable("t");
        Path laterFile = dir.resolve(before.files().getLast().path());
        byte[] original = Files.readAllBytes(laterFile);
        Files.writeString(laterFile, "invalid header");
        assertThrows(UncheckedIOException.class, () -> e.delete("t",
                Optional.of(new Predicate("id", Comparison.GREATER_THAN, 1L))));
        assertSame(before, e.requireTable("t"));
        assertEquals(before, new StorageEngine(dir).requireTable("t"));
        try (var paths = Files.list(dir.resolve("data"))) { assertEquals(2, paths.count()); }
        Files.write(laterFile, original);
        assertEquals(List.of(1L, 2L, 3L, 4L), ids(new StorageEngine(dir)));
    }

}

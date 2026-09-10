package dk.itu.datasys;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageEngineIT {
    @TempDir Path dir;
    final List<ColumnSpec> schema = List.of(new ColumnSpec("city", ColumnType.STRING),
            new ColumnSpec("distance", ColumnType.LONG), new ColumnSpec("price", ColumnType.DOUBLE));

    StorageEngine golden() {
        var engine = new StorageEngine(dir, 2);
        engine.createTable("trips", schema);
        engine.copyFile("trips", "src/test/resources/trips.csv");
        return engine;
    }

    List<Long> distances(StorageEngine engine, String column, Comparison comparison, Object value) {
        return engine.select("trips", column, comparison, value).stream().map(row -> (Long) row[1]).toList();
    }

    Path onlyFile(String directory) throws IOException {
        try (var files = Files.list(dir.resolve(directory))) { return files.findFirst().orElseThrow(); }
    }

    @Test void persistsEmptySchemaWithSafeFilenamesAndNoData() throws Exception {
        var engine = new StorageEngine(dir);
        engine.createTable("../table / name", schema);
        assertEquals(0, new StorageEngine(dir).select("../table / name", "city", Comparison.EQUALS, "A").size());
        try (var files = Files.list(dir.resolve("data"))) { assertEquals(0, files.count()); }
        assertEquals(dir.resolve("catalog"), onlyFile("catalog").getParent());
        assertThrows(IllegalArgumentException.class, () -> new StorageEngine(dir).createTable("../table / name", schema));
    }

    @Test void roundTripsGoldenValuesAndSurvivesDifferentPartitionSetting() {
        var a = golden();
        var b = new StorageEngine(dir, 7);
        var rows = b.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        assertEquals(List.of(12L,187L,95L,140L,210L,31L,88L,299L), rows.stream().map(r -> r[1]).toList());
        assertArrayEquals(new Object[]{"Copenhagen", 12L, 23.5}, rows.getFirst());
        assertArrayEquals(new Object[]{"Esbjerg", 299L, 450.25}, rows.getLast());
        var original = a.select("trips", "distance", Comparison.GREATER_THAN, -1L);
        for (int i = 0; i < rows.size(); i++) assertArrayEquals(original.get(i), rows.get(i));
        assertEquals(new ScanStats(4,4,0), b.getLastScanStats());
    }

    @Test void allNineComparisonsPreserveInputOrder() {
        var e = golden();
        assertEquals(List.of(12L,140L,88L), distances(e,"city",Comparison.EQUALS,"Copenhagen"));
        assertEquals(List.of(187L,210L), distances(e,"city",Comparison.LESS_THAN,"Copenhagen"));
        assertEquals(List.of(95L,31L,299L), distances(e,"city",Comparison.GREATER_THAN,"Copenhagen"));
        assertEquals(List.of(95L), distances(e,"distance",Comparison.EQUALS,95L));
        assertEquals(List.of(12L,31L,88L), distances(e,"distance",Comparison.LESS_THAN,95L));
        assertEquals(List.of(187L,140L,210L,299L), distances(e,"distance",Comparison.GREATER_THAN,100L));
        assertEquals(List.of(31L), distances(e,"price",Comparison.EQUALS,45.0));
        assertEquals(List.of(12L,31L), distances(e,"price",Comparison.LESS_THAN,50.0));
        assertEquals(List.of(187L,95L,140L,210L,88L,299L), distances(e,"price",Comparison.GREATER_THAN,45.0));
        assertTrue(distances(e,"distance",Comparison.GREATER_THAN,1000L).isEmpty());
        assertEquals(new ScanStats(4,0,4), e.getLastScanStats());
    }

    @Test void rejectsInvalidApiArguments() {
        assertThrows(IllegalArgumentException.class, () -> new StorageEngine(dir, 0));
        var e = golden();
        assertThrows(IllegalArgumentException.class, () -> e.createTable("trips", schema));
        assertThrows(IllegalArgumentException.class, () -> e.createTable("empty", List.of()));
        assertThrows(IllegalArgumentException.class, () -> e.createTable("duplicates", List.of(schema.getFirst(),schema.getFirst())));
        assertThrows(IllegalArgumentException.class, () -> e.copyFile("missing", "missing.csv"));
        assertThrows(IllegalArgumentException.class, () -> e.select("missing", "city", Comparison.EQUALS, "A"));
        assertThrows(IllegalArgumentException.class, () -> e.select("trips", "missing", Comparison.EQUALS, "A"));
        for (Object wrong : new Object[]{1, "1", 1.0, null}) {
            assertThrows(IllegalArgumentException.class, () -> e.select("trips", "distance", Comparison.EQUALS, wrong));
        }
        assertThrows(IllegalArgumentException.class, () -> e.select("trips", "city", null, "A"));
        assertThrows(UnsupportedOperationException.class, () -> new StorageEngine(dir).copyFile("trips", "src/test/resources/trips.csv"));
    }

    @Test void persistsPartitionOffsetsAndCanonicalStatistics() throws Exception {
        golden();
        var catalog = new ObjectMapper().readTree(onlyFile("catalog").toFile());
        assertEquals(1, catalog.get("version").asInt());
        var files = catalog.get("files");
        assertEquals(1, files.size());
        var partitions = files.get(0).get("partitions");
        assertEquals(4, partitions.size());
        long end = 8;
        String[][] ranges = {{"12","187"},{"95","140"},{"31","210"},{"88","299"}};
        for (int p = 0; p < 4; p++) {
            var partition = partitions.get(p);
            assertEquals(2, partition.get("rowCount").asInt());
            var chunks = partition.get("chunks");
            assertEquals(ranges[p][0], chunks.get(1).get("statistics").get("min").asText());
            assertEquals(ranges[p][1], chunks.get(1).get("statistics").get("max").asText());
            for (var chunk : chunks) {
                assertEquals(end, chunk.get("offset").asLong());
                end += chunk.get("length").asLong();
            }
        }
        assertEquals(Files.size(onlyFile("data")), end);
    }

    @Test void sortedScanPrunesThreePartitionsAndPrunedScanNeverOpensData() throws Exception {
        Path csv = dir.resolve("sorted.csv");
        Files.writeString(csv,"Copenhagen,12,23.5\nRoskilde,31,45.0\nCopenhagen,88,99.99\nOdense,95,120.75\nCopenhagen,140,210.0\nAarhus,187,301.0\nAalborg,210,340.5\nEsbjerg,299,450.25\n");
        var e = new StorageEngine(dir,2); e.createTable("trips",schema); e.copyFile("trips",csv.toString());
        assertEquals(List.of(210L,299L),distances(e,"distance",Comparison.GREATER_THAN,200L));
        assertEquals(new ScanStats(4,1,3),e.getLastScanStats());
        Files.delete(onlyFile("data"));
        var restarted = new StorageEngine(dir);
        assertTrue(distances(restarted,"distance",Comparison.GREATER_THAN,1000L).isEmpty());
        assertEquals(new ScanStats(4,0,4),restarted.getLastScanStats());
    }

    @Test void malformedCopyRollsBackAndCanBeRetriedAfterRestart() throws Exception {
        var e = new StorageEngine(dir,2); e.createTable("trips",schema);
        Path csv = dir.resolve("bad.csv");
        Files.writeString(csv,"A,1,2.0\nB,2,3.0\nC,bad,4.0\n");
        var error = assertThrows(IllegalArgumentException.class, () -> e.copyFile("trips",csv.toString()));
        assertTrue(error.getMessage().contains(csv.toString())); assertTrue(error.getMessage().contains("3"));
        try (var files = Files.list(dir.resolve("data"))) { assertEquals(0,files.count()); }
        var restarted = new StorageEngine(dir,2);
        assertTrue(distances(restarted,"distance",Comparison.GREATER_THAN,0L).isEmpty());
        restarted.copyFile("trips","src/test/resources/trips.csv");
        assertEquals(8,distances(restarted,"distance",Comparison.GREATER_THAN,0L).size());
    }

    @Test void handlesEmptyCopyAndPartialFinalPartition() throws Exception {
        var e = new StorageEngine(dir,2); e.createTable("trips",schema);
        Path csv = dir.resolve("small.csv"); Files.writeString(csv, "A,1,1\nB,2,2\nC,3,3\n");
        e.copyFile("trips",csv.toString());
        assertEquals(List.of(1L,2L,3L),distances(e,"distance",Comparison.GREATER_THAN,0L));
        assertEquals(new ScanStats(2,2,0),e.getLastScanStats());
        e.createTable("empty",schema); Files.writeString(csv, ""); e.copyFile("empty",csv.toString());
        assertTrue(e.select("empty","city",Comparison.EQUALS,"").isEmpty());
        assertEquals(new ScanStats(0,0,0),e.getLastScanStats());
        assertThrows(UnsupportedOperationException.class, () -> e.copyFile("empty",csv.toString()));
    }

    @Test void rejectsCorruptDataAndKeepsLastSuccessfulStats() throws Exception {
        var e = golden(); distances(e,"distance",Comparison.GREATER_THAN,1000L);
        try (var f = new RandomAccessFile(onlyFile("data").toFile(),"rw")) { f.writeInt(0); }
        assertThrows(UncheckedIOException.class, () -> distances(e,"distance",Comparison.GREATER_THAN,0L));
        assertEquals(new ScanStats(4,0,4),e.getLastScanStats());
    }

    @Test void rejectsUnsupportedCatalogVersions() throws Exception {
        golden(); Path catalog = onlyFile("catalog");
        Files.writeString(catalog,Files.readString(catalog).replace("\"version\" : 1", "\"version\" : 999"));
        assertThrows(UncheckedIOException.class, () -> new StorageEngine(dir));
    }
    @Test void exposesOrderedReadOnlySchemaAndRejectsUnknownTables() {
        var engine = new StorageEngine(dir);
        engine.createTable("Trips", schema);

        var actual = engine.schema("Trips");
        assertEquals(schema, actual);
        assertThrows(UnsupportedOperationException.class,
                () -> actual.add(new ColumnSpec("extra", ColumnType.STRING)));
        assertThrows(IllegalArgumentException.class, () -> engine.schema("trips"));
        assertThrows(IllegalArgumentException.class, () -> engine.schema("missing"));
    }
    @Test void signedZeroHasNumericEqualityWithoutLosingEncodedSign() throws Exception {
        var e = new StorageEngine(dir,1); e.createTable("trips",schema);
        Path csv = dir.resolve("zeros.csv"); Files.writeString(csv, "A,1,-0.0\nB,2,0.0\n");
        e.copyFile("trips",csv.toString());
        var restarted = new StorageEngine(dir);
        for (double zero : new double[]{-0.0, 0.0}) {
            assertEquals(List.of(1L,2L),distances(restarted,"price",Comparison.EQUALS,zero));
            assertTrue(distances(restarted,"price",Comparison.LESS_THAN,zero).isEmpty());
            assertTrue(distances(restarted,"price",Comparison.GREATER_THAN,zero).isEmpty());
        }
        var rows = restarted.select("trips","price",Comparison.EQUALS,0.0);
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits((Double) rows.getFirst()[2]));
    }

    @Test void catalogPublicationFailureRemovesPublishedBinaryAndLeavesCopyRetryable() throws Exception {
        var e = new StorageEngine(dir,2); e.createTable("trips",schema);
        Path catalog = onlyFile("catalog");
        Path backup = dir.resolve("catalog-backup.json"); Files.move(catalog,backup);
        // A nonempty directory cannot be replaced by the catalog's atomic move.
        Files.createDirectory(catalog); Files.writeString(catalog.resolve("blocker"),"block");
        try {
            assertThrows(UncheckedIOException.class, () -> e.copyFile("trips","src/test/resources/trips.csv"));
            assertTrue(distances(e,"distance",Comparison.GREATER_THAN,0L).isEmpty());
            try (var files = Files.list(dir.resolve("data"))) { assertEquals(0,files.count()); }
        } finally {
            Files.delete(catalog.resolve("blocker")); Files.delete(catalog); Files.move(backup,catalog);
        }
        assertTrue(distances(new StorageEngine(dir),"distance",Comparison.GREATER_THAN,0L).isEmpty());
        e.copyFile("trips","src/test/resources/trips.csv");
        assertEquals(8,distances(new StorageEngine(dir),"distance",Comparison.GREATER_THAN,0L).size());
    }

    @Test void logsEveryStatisticAndDecisionInSevenFieldsEvenWithSpecialNames() throws Exception {
        String marker = "logging-" + UUID.randomUUID();
        String table = marker + ",name\nline\rreturn";
        var e = new StorageEngine(dir,2); e.createTable(table,schema);
        e.copyFile(table,"src/test/resources/trips.csv");
        e.select(table,"city",Comparison.EQUALS,"a,b\nc\rd");
        assertThrows(IllegalArgumentException.class, () -> e.createTable(table,schema));
        var lines = Files.readAllLines(Path.of("logs/engine.log")).stream().filter(line -> line.contains(marker)).toList();
        assertEquals(20, lines.size()); // 12 statistics + 4 decisions + 4 API summaries
        for (String line : lines) assertEquals(7,line.split(",",-1).length,line);
        assertEquals(12,lines.stream().filter(line -> line.contains(" min=") && !line.contains("decision=")).count());
        assertEquals(4,lines.stream().filter(line -> line.contains("decision=")).count());
        assertEquals(4,lines.stream().filter(line -> line.contains("operation=")).count());
    }

    @Test void defaultsToEightRowsAndKeepsTrailingEmptyStrings() throws Exception {
        var e = new StorageEngine(dir);
        e.createTable("trips",schema); e.copyFile("trips","src/test/resources/trips.csv");
        assertEquals(8,distances(e,"distance",Comparison.GREATER_THAN,0L).size());
        assertEquals(new ScanStats(1,1,0),e.getLastScanStats());
        e.createTable("strings",List.of(new ColumnSpec("id",ColumnType.LONG),new ColumnSpec("text",ColumnType.STRING)));
        Path csv = dir.resolve("empty-string.csv"); Files.writeString(csv,"1,\n"); e.copyFile("strings",csv.toString());
        assertArrayEquals(new Object[]{1L,""},new StorageEngine(dir).select("strings","text",Comparison.EQUALS,"").getFirst());
    }

}

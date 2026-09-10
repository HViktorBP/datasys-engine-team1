package dk.itu.datasys;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Persistent columnar storage. Use one writer engine per data directory. */
public final class StorageEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    private final CatalogStore catalogs;
    private final Map<String, CatalogStore.Table> tables;
    private final int maxRowsPerPartition;
    private ScanStats lastScanStats = new ScanStats(0, 0, 0);

    public StorageEngine(Path dataDirectory) { this(dataDirectory, 8); }

    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        if (dataDirectory == null) throw new IllegalArgumentException("Data directory is required");
        if (maxRowsPerPartition < 1) throw new IllegalArgumentException("Partition size must be positive");
        this.maxRowsPerPartition = maxRowsPerPartition;
        try {
            catalogs = new CatalogStore(dataDirectory);
            tables = catalogs.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load storage directory " + dataDirectory, e);
        }
    }

    public synchronized void createTable(String tableName, List<ColumnSpec> columns) {
        long start = startCall();
        boolean success = false;
        try {
            CatalogStore.validateSchema(tableName, columns);
            if (tables.containsKey(tableName)) throw new IllegalArgumentException("Table already exists: " + tableName);
            var table = new CatalogStore.Table(1, UUID.randomUUID().toString(), tableName, List.copyOf(columns), List.of());
            catalogs.save(table);
            tables.put(tableName, table);
            success = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create table " + tableName, e);
        } finally {
            LOGGER.debug("operation=createTable table={} success={} durationMs={}", clean(tableName), success, elapsed(start));
        }
    }

    public synchronized void copyFile(String tableName, String csvFilePath) {
        long start = startCall();
        long rowCount = 0;
        int partitionCount = 0;
        boolean success = false;
        Path temporary = null;
        Path published = null;
        try {
            var table = table(tableName);
            if (!table.files().isEmpty()) throw new UnsupportedOperationException("Only one copy per table is supported");
            if (csvFilePath == null) throw new IllegalArgumentException("CSV path is required");
            String relative = "data/" + table.id() + "-0.bin";
            Path target = catalogs.dataPath(relative);
            temporary = Files.createTempFile(target.getParent(), table.id() + "-", ".tmp");
            List<CatalogStore.Partition> partitions = new ArrayList<>();
            try (var input = Files.newBufferedReader(Path.of(csvFilePath), StandardCharsets.UTF_8);
                 var output = new RandomAccessFile(temporary.toFile(), "rw")) {
                BinaryColumnCodec.writeHeader(output);
                List<Object[]> rows = new ArrayList<>();
                String line;
                while ((line = input.readLine()) != null) {
                    rows.add(CsvRowParser.parse(line, table.columns(), csvFilePath, ++rowCount));
                    if (rows.size() == maxRowsPerPartition) {
                        partitions.add(writePartition(output, table, rows, partitions.size()));
                        rows.clear();
                    }
                }
                if (!rows.isEmpty()) partitions.add(writePartition(output, table, rows, partitions.size()));
            }
            partitionCount = partitions.size();
            CatalogStore.publish(temporary, target);
            published = target;
            var updated = new CatalogStore.Table(table.version(), table.id(), table.name(), table.columns(),
                    List.of(new CatalogStore.DataFile(relative, List.copyOf(partitions))));
            catalogs.save(updated);
            tables.put(tableName, updated);
            success = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot copy " + csvFilePath + " near line " + (rowCount + 1), e);
        } finally {
            if (!success && published != null) removeFailedFile(published);
            if (temporary != null) removeFailedFile(temporary);
            LOGGER.debug("operation=copyFile table={} file={} rows={} partitions={} success={} durationMs={}",
                    clean(tableName), clean(csvFilePath), rowCount, partitionCount, success, elapsed(start));
        }
    }

    private CatalogStore.Partition writePartition(RandomAccessFile output, CatalogStore.Table table,
                                                   List<Object[]> rows, int partitionIndex) throws IOException {
        List<CatalogStore.Chunk> chunks = new ArrayList<>();
        for (int column = 0; column < table.columns().size(); column++) {
            ColumnSpec spec = table.columns().get(column);
            List<Object> values = new ArrayList<>(rows.size());
            long offset = output.getFilePointer();
            for (Object[] row : rows) {
                values.add(row[column]);
                BinaryColumnCodec.writeValue(output, spec.type(), row[column]);
            }
            PartitionStatistics statistics = PartitionStatistics.compute(spec.type(), values);
            LOGGER.debug("table={} partition={} column={} min={} max={}", clean(table.name()), partitionIndex,
                    clean(spec.name()), clean(statistics.min()), clean(statistics.max()));
            chunks.add(new CatalogStore.Chunk(offset, output.getFilePointer() - offset, statistics));
        }
        return new CatalogStore.Partition(rows.size(), List.copyOf(chunks));
    }

    public synchronized List<Object[]> select(String tableName, String columnName,
                                               Comparison comparison, Object constant) {
        long start = startCall();
        int total = 0;
        int read = 0;
        int pruned = 0;
        List<Object[]> result = new ArrayList<>();
        boolean success = false;
        try {
            var table = table(tableName);
            int predicateColumn = -1;
            for (int i = 0; i < table.columns().size(); i++) {
                if (table.columns().get(i).name().equals(columnName)) predicateColumn = i;
            }
            if (predicateColumn < 0) throw new IllegalArgumentException("Unknown column: " + columnName);
            ColumnType type = table.columns().get(predicateColumn).type();
            if (comparison == null || !type.accepts(constant)) throw new IllegalArgumentException("Invalid comparison or constant type for " + type);
            if (type == ColumnType.STRING) ColumnType.requireAscii((String) constant);
            for (var dataFile : table.files()) {
                // Open lazily: even a missing binary file is irrelevant to an entirely pruned scan.
                RandomAccessFile input = null;
                try {
                    for (var partition : dataFile.partitions()) {
                        var statistics = partition.chunks().get(predicateColumn).statistics();
                        boolean skip = PartitionPruner.canPrune(type, statistics, comparison, constant);
                        LOGGER.debug("table={} column={} comparison={} const={} partition={} min={} max={} decision={}",
                                clean(tableName), clean(columnName), comparison, clean(constant), total,
                                clean(statistics.min()), clean(statistics.max()), skip ? "PRUNED" : "READ");
                        total++;
                        if (skip) { pruned++; continue; }
                        if (input == null) {
                            input = new RandomAccessFile(catalogs.dataPath(dataFile.path()).toFile(), "r");
                            BinaryColumnCodec.checkHeader(input);
                        }
                        Object[][] columns = new Object[table.columns().size()][];
                        for (int i = 0; i < columns.length; i++) {
                            var chunk = partition.chunks().get(i);
                            columns[i] = BinaryColumnCodec.readChunk(input, table.columns().get(i).type(),
                                    chunk.offset(), chunk.length(), partition.rowCount());
                        }
                        read++;
                        for (int row = 0; row < partition.rowCount(); row++) {
                            if (comparison.matches(type.compare(columns[predicateColumn][row], constant))) {
                                Object[] match = new Object[columns.length];
                                for (int i = 0; i < columns.length; i++) match[i] = columns[i][row];
                                result.add(match);
                            }
                        }
                    }
                } finally {
                    if (input != null) input.close();
                }
            }
            lastScanStats = new ScanStats(total, read, pruned);
            success = true;
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read table " + tableName, e);
        } finally {
            LOGGER.debug("operation=select table={} column={} comparison={} const={} partitionsTotal={} partitionsRead={} partitionsPruned={} rowsOut={} success={} durationMs={}",
                    clean(tableName), clean(columnName), comparison, clean(constant), total, read, pruned, result.size(), success, elapsed(start));
        }
    }

    public synchronized ScanStats getLastScanStats() { return lastScanStats; }

    private CatalogStore.Table table(String name) {
        var table = tables.get(name);
        if (table == null) throw new IllegalArgumentException("Unknown table: " + name);
        return table;
    }

    /** Returns the table schema in column order. */
    public synchronized List<ColumnSpec> schema(String tableName) {
        return List.copyOf(table(tableName).columns());
    }

    private static long startCall() {
        if (MDC.get("sessionId") == null) MDC.put("sessionId", UUID.randomUUID().toString());
        if (MDC.get("statementNumber") == null) MDC.put("statementNumber", "0");
        return System.nanoTime();
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    private static String clean(Object value) {
        return String.valueOf(value).replace(',', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static void removeFailedFile(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException e) { LOGGER.warn("operation=cleanup path={} error={}", clean(path), clean(e.getMessage())); }
    }
}

package dk.itu.datasys;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Provides persistent, partitioned columnar storage for typed tables.
 *
 * <p>Use one writer engine per data directory. Each instance loads immutable catalog snapshots
 * when constructed, so reopen the engine to observe catalogs published by another instance.
 *
 * @author Team 1
 * @version 0.3
 * @see ColumnSpec
 * @see ScanStats
 * @since 0.2
 */
public final class StorageEngine {
    /** Diagnostic logger for storage operations and scan decisions. */
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    /** Catalog persistence helper for the storage root. */
    private final CatalogStore catalogs;
    /** Current immutable table snapshots indexed by logical name. */
    private final Map<String, CatalogStore.Table> tables;
    /** Positive maximum number of rows written to each partition. */
    private final int maxRowsPerPartition;
    /** Statistics from the latest successful scan. */
    private ScanStats lastScanStats = new ScanStats(0, 0, 0);

    /**
     * Opens a storage directory using a maximum of eight rows per partition.
     *
     * @param dataDirectory the root directory for catalogs and column data
     * @throws IllegalArgumentException if {@code dataDirectory} is {@code null}
     * @throws UncheckedIOException if the storage directory cannot be initialized or loaded
     */
    public StorageEngine(Path dataDirectory) { this(dataDirectory, 8); }

    /**
     * Opens a storage directory using the specified maximum partition size.
     *
     * @param dataDirectory the root directory for catalogs and column data
     * @param maxRowsPerPartition the positive maximum number of rows in each partition
     * @throws IllegalArgumentException if the directory is {@code null} or the partition size is
     *                                  not positive
     * @throws UncheckedIOException if the storage directory cannot be initialized or loaded
     */
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

    /**
     * Creates and persists an empty table with an ordered schema.
     *
     * @param tableName the unique, non-blank table name
     * @param columns the non-empty ordered column definitions
     * @throws IllegalArgumentException if the schema is invalid or the table already exists
     * @throws UncheckedIOException if the catalog cannot be persisted
     */
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

    /**
     * Imports a headerless ASCII CSV file into a table with no prior successful copy.
     *
     * <p>The import preserves input row order and publishes the completed binary data before its
     * catalog snapshot. Part 1 supports one successful copy per table.
     *
     * @param tableName the destination table name
     * @param csvFilePath the source CSV file path
     * @throws IllegalArgumentException if the table or path is invalid or a row does not match the
     *                                  table schema
     * @throws UnsupportedOperationException if the table already has a copied data file
     * @throws UncheckedIOException if the source or destination cannot be read or written
     */
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

    /**
     * Writes one row partition as consecutive column chunks.
     *
     * @param output the destination binary file
     * @param table the destination table metadata
     * @param rows the typed rows to write
     * @param partitionIndex the zero-based partition index used for logging
     * @return metadata describing the written partition
     * @throws IOException if a column value cannot be written
     */
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

    /**
     * Selects rows whose named column satisfies a comparison predicate.
     *
     * <p>Rows retain input order and fields retain schema order. Catalog-only partition statistics
     * are used to avoid reading partitions that cannot contain a match, and the resulting scan
     * counts are available from {@link #getLastScanStats()} after a successful call.
     *
     * @param tableName the table to scan
     * @param columnName the predicate column
     * @param comparison the comparison operator
     * @param constant the predicate constant with the column's exact Java type
     * @return matching rows in input order
     * @throws IllegalArgumentException if the table or column is unknown or the comparison value
     *                                  is invalid for the column type
     * @throws UncheckedIOException if published table data cannot be read or decoded
     */
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

    /**
     * Returns statistics for the latest successful scan.
     *
     * @return the latest scan statistics, or zero counts before the first successful scan
     */
    public synchronized ScanStats getLastScanStats() { return lastScanStats; }

    /**
     * Looks up a table by logical name.
     *
     * @param name the table name
     * @return the current immutable table snapshot
     * @throws IllegalArgumentException if the table is unknown
     */
    private CatalogStore.Table table(String name) {
        var table = tables.get(name);
        if (table == null) throw new IllegalArgumentException("Unknown table: " + name);
        return table;
    }

    /**
     * Returns an immutable snapshot of a table schema in column order.
     *
     * @param tableName the table name
     * @return the ordered column definitions
     * @throws IllegalArgumentException if the table is unknown
     * @since 0.3
     */
    public synchronized List<ColumnSpec> schema(String tableName) {
        return List.copyOf(table(tableName).columns());
    }

    /**
     * Initializes per-thread diagnostic context and captures a monotonic start time.
     *
     * @return the starting value from {@link System#nanoTime()}
     */
    private static long startCall() {
        if (MDC.get("sessionId") == null) MDC.put("sessionId", UUID.randomUUID().toString());
        if (MDC.get("statementNumber") == null) MDC.put("statementNumber", "0");
        return System.nanoTime();
    }

    /**
     * Computes elapsed whole milliseconds from a monotonic start time.
     *
     * @param start the starting value from {@link System#nanoTime()}
     * @return the elapsed time in milliseconds
     */
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    /**
     * Produces a single-line, comma-free representation for structured log values.
     *
     * @param value the value to sanitize
     * @return the sanitized representation
     */
    private static String clean(Object value) {
        return String.valueOf(value).replace(',', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Best-effort deletes an unpublished or failed data file and logs cleanup failures.
     *
     * @param path the file to remove
     */
    private static void removeFailedFile(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException e) { LOGGER.warn("operation=cleanup path={} error={}", clean(path), clean(e.getMessage())); }
    }
}

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
 * @version 0.5
 * @see ColumnSpec
 * @see Planner
 * @since 0.2
 */
public final class StorageEngine {
    /** Diagnostic logger for storage operations. */
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    /** Catalog persistence helper for the storage root. */
    private final CatalogStore catalogs;
    /** Current immutable table snapshots indexed by logical name. */
    private final Map<String, CatalogStore.Table> tables;
    /** Positive maximum number of rows written to each partition. */
    private final int maxRowsPerPartition;

    /**
     * Opens a storage directory using a maximum of 1,000 rows per partition.
     *
     * <p>The default changed from eight to 1,000 in release 0.5. It applies to future imports;
     * existing data retains the partition boundaries recorded in its catalog.
     *
     * @param dataDirectory the root directory for catalogs and column data
     * @throws IllegalArgumentException if {@code dataDirectory} is {@code null}
     * @throws UncheckedIOException if the storage directory cannot be initialized or loaded
     * @since 0.2
     * @version 0.5
     */
    public StorageEngine(Path dataDirectory) { this(dataDirectory, 1000); }

    /**
     * Opens a storage directory using the specified maximum partition size.
     *
     * @param dataDirectory the root directory for catalogs and column data
     * @param maxRowsPerPartition the positive maximum number of rows in each partition
     * @throws IllegalArgumentException if the directory is {@code null} or the partition size is
     *                                  not positive
     * @throws UncheckedIOException if the storage directory cannot be initialized or loaded
     * @since 0.2
     * @version 0.5
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
     * @since 0.2
     * @version 0.5
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
     * The input is limited to its byte length at open time, so importing a live log excludes
     * records appended during the import. This does not isolate in-place edits or truncation.
     *
     * @param tableName the destination table name
     * @param csvFilePath the source CSV file path
     * @throws IllegalArgumentException if the table or path is invalid or a row does not match the
     *                                  table schema
     * @throws UnsupportedOperationException if the table already has a copied data file
     * @throws UncheckedIOException if the source or destination cannot be read or written
     * @since 0.2
     * @version 0.5
     */
    public synchronized void copyFile(String tableName, String csvFilePath) {
        long start = startCall();
        long rowCount = 0;
        int partitionCount = 0;
        boolean success = false;
        Path temporary = null;
        Path published = null;
        try {
            var table = requireTable(tableName);
            if (!table.files().isEmpty()) throw new UnsupportedOperationException("Only one copy per table is supported");
            if (csvFilePath == null) throw new IllegalArgumentException("CSV path is required");
            String relative = "data/" + table.id() + "-0.bin";
            Path target = catalogs.dataPath(relative);
            temporary = Files.createTempFile(target.getParent(), table.id() + "-", ".tmp");
            List<CatalogStore.Partition> partitions = new ArrayList<>();
            try (var input = new BufferedReader(new InputStreamReader(
                         new SnapshotInputStream(Path.of(csvFilePath)), StandardCharsets.UTF_8));
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
     * Looks up a table by logical name.
     *
     * @param name the table name
     * @return the current immutable table snapshot
     * @throws IllegalArgumentException if the table is unknown
     * @since 0.4
     */
    synchronized CatalogStore.Table requireTable(String name) {
        var table = tables.get(name);
        if (table == null) throw new IllegalArgumentException("Unknown table: " + name);
        return table;
    }

    /**
     * Returns the published data file for a table, or {@code null} when the table has no copy.
     *
     * @param tableName the table name
     * @return the absolute data path, or {@code null} when no file is published
     * @throws IllegalArgumentException if the table is unknown
     * @throws UncheckedIOException if the catalog path cannot be resolved
     * @since 0.4
     */
    synchronized Path publishedDataFile(String tableName) {
        var table = requireTable(tableName);
        if (table.files().isEmpty()) {
            return null;
        }
        try {
            return catalogs.dataPath(table.files().getFirst().path());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve data file for " + tableName, e);
        }
    }

    /**
     * Returns an immutable snapshot of a table schema in column order.
     *
     * @param tableName the table name
     * @return the ordered column definitions
     * @throws IllegalArgumentException if the table is unknown
     * @since 0.3
     * @version 0.5
     */
    public synchronized List<ColumnSpec> schema(String tableName) {
        return List.copyOf(requireTable(tableName).columns());
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
     * Produces single-line ASCII text without commas or double quotes for log values.
     *
     * <p>Non-ASCII UTF-16 code units become hexadecimal escapes so diagnostics can be imported
     * by the engine's ASCII CSV reader, including paths supplied through {@code -f}.
     *
     * @param value the value to sanitize
     * @return the sanitized ASCII representation
     * @since 0.2
     * @version 0.5
     */
    static String clean(Object value) {
        String text = String.valueOf(value);
        var result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character > 127) {
                result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else if (character == ',' || character == '"' || character == '\n' || character == '\r') {
                result.append(' ');
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    /**
     * Best-effort deletes an unpublished or failed data file and logs cleanup failures.
     *
     * @param path the file to remove
     */
    private static void removeFailedFile(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException e) { LOGGER.error("operation=cleanup path={} error={}", clean(path), clean(e.getMessage())); }
    }
}

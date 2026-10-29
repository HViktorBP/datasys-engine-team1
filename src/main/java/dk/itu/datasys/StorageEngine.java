package dk.itu.datasys;

import dk.itu.datasys.CatalogStore.Chunk;
import dk.itu.datasys.CatalogStore.DataFile;
import dk.itu.datasys.CatalogStore.Partition;
import dk.itu.datasys.CatalogStore.Snapshot;
import dk.itu.datasys.CatalogStore.Table;
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
 * <p>Use exactly one engine per data directory and execute statements sequentially, consuming and
 * closing each query before starting another statement. Synchronized methods do not support
 * overlapping operators or multiple engines on one directory. Each changing statement commits
 * an immutable catalog with current and previous versions. Process-crash recovery is supported
 * while the operating system remains running; power-loss durability is not promised.
 *
 * @author Team 1
 * @version 0.7
 * @see ColumnSpec
 * @see Planner
 * @since 0.2
 */
public final class StorageEngine {
    /** Diagnostic logger for storage operations. */
    private static final Logger LOGGER = LoggerFactory.getLogger(StorageEngine.class);
    /** Write buffer size for partition files, so most encoded values cost no system call. */
    private static final int OUTPUT_BUFFER_BYTES = 1 << 16;
    /** Catalog persistence helper for the storage root. */
    private final CatalogStore catalogs;
    /** Current immutable table snapshots indexed by logical name. */
    private final Map<String, Table> tables;
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
     * @version 0.7
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
     * @version 0.7
     */
    public StorageEngine(Path dataDirectory, int maxRowsPerPartition) {
        this(dataDirectory, maxRowsPerPartition, new StorageIo());
    }

    /**
     * Opens storage with injectable publication and cleanup operations.
     *
     * @param dataDirectory the storage root
     * @param maxRowsPerPartition the positive row-buffer bound
     * @param io the filesystem operations
     * @throws IllegalArgumentException if an argument is invalid
     * @throws UncheckedIOException if catalogs cannot be loaded
     * @since 0.7
     * @version 0.7
     */
    StorageEngine(Path dataDirectory, int maxRowsPerPartition, StorageIo io) {
        if (dataDirectory == null) throw new IllegalArgumentException("Data directory is required");
        if (maxRowsPerPartition < 1) throw new IllegalArgumentException("Partition size must be positive");
        this.maxRowsPerPartition = maxRowsPerPartition;
        try {
            catalogs = new CatalogStore(dataDirectory, Objects.requireNonNull(io));
            tables = catalogs.load();
            catalogs.cleanup(tables.values());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load storage directory " + dataDirectory + ": " + e.getMessage(), e);
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
     * @version 0.7
     */
    public synchronized void createTable(String tableName, List<ColumnSpec> columns) {
        long start = startCall();
        boolean success = false;
        try {
            CatalogStore.validateSchema(tableName, columns);
            if (tables.containsKey(tableName)) throw new IllegalArgumentException("Table already exists: " + tableName);
            var table = new Table(2, UUID.randomUUID().toString(), tableName, columns,
                    new Snapshot(0, List.of()), null);
            catalogs.save(table);
            tables.put(tableName, table);
            success = true;
            catalogs.cleanup(tables.values());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create table " + tableName, e);
        } finally {
            LOGGER.debug("operation=createTable table={} success={} version=0 durationMs={}", clean(tableName), success, elapsed(start));
        }
    }

    /**
     * Appends a headerless ASCII CSV file as an independently committed statement.
     *
     * <p>Each new partition has a unique file; malformed input rolls back only this copy.
     * Empty input still commits the next version without creating partition files. Input is
     * limited to its byte length when opened, excluding later appends but not in-place edits.
     *
     * @param tableName the destination table
     * @param csvFilePath the input CSV path
     * @throws IllegalArgumentException if the table, path or any input row is invalid
     * @throws ArithmeticException if the table version would overflow
     * @throws UncheckedIOException if input, staging or catalog publication fails
     * @since 0.2
     * @version 0.7
     */
    public synchronized void copyFile(String tableName, String csvFilePath) {
        copyFile(requireTable(tableName), csvFilePath);
    }

    /**
     * Appends input using the snapshot captured before statement binding.
     *
     * @param base the captured table
     * @param csvFilePath the CSV source
     * @throws IllegalArgumentException if the source or a row is invalid
     * @throws ArithmeticException if the version would overflow
     * @throws UncheckedIOException if copying or publication fails
     * @since 0.7
     */
    synchronized void copyFile(Table base, String csvFilePath) {
        long start = startCall();
        long rowCount = 0;
        int partitionCount = 0;
        boolean committed = false;
        List<Path> staged = new ArrayList<>();
        long version = Math.incrementExact(base.current().tableVersion());
        try {
            if (csvFilePath == null) throw new IllegalArgumentException("CSV path is required");
            List<DataFile> files = new ArrayList<>(base.files());
            try (var input = new BufferedReader(new InputStreamReader(
                    new SnapshotInputStream(Path.of(csvFilePath)), StandardCharsets.UTF_8))) {
                List<Object[]> rows = new ArrayList<>();
                String line;
                while ((line = input.readLine()) != null) {
                    rows.add(CsvRowParser.parse(line, base.columns(), csvFilePath, ++rowCount));
                    if (rows.size() == maxRowsPerPartition) {
                        files.add(stagePartition(base, rows, partitionCount++, staged));
                        rows.clear();
                    }
                }
                if (!rows.isEmpty()) files.add(stagePartition(base, rows, partitionCount++, staged));
            }
            commit(base, files, version);
            committed = true;
            catalogs.cleanup(tables.values());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot copy " + csvFilePath + " near line " + (rowCount + 1), e);
        } finally {
            if (!committed) for (Path path : staged) catalogs.remove(path);
            LOGGER.debug("operation=copyFile table={} file={} rows={} partitions={} success={} version={} durationMs={}",
                    clean(base.name()), clean(csvFilePath), rowCount, partitionCount, committed,
                    committed ? version : base.current().tableVersion(), elapsed(start));
        }
    }

    /**
     * Describes the visible result and partition work of one delete statement.
     *
     * @param version the version committed, or read for a no-op
     * @param rowsDeleted the number of matching rows removed
     * @param partitionsRewritten the number replaced by nonempty survivor partitions
     * @param partitionsCarried the number retained unchanged, including pruned partitions
     * @param partitionsRemoved the number dropped without replacement
     * @author Team 1
     * @since 0.7
     * @version 0.7
     */
    public record DeleteResult(long version, long rowsDeleted, int partitionsRewritten,
                               int partitionsCarried, int partitionsRemoved) { }

    /**
     * Removes matching rows by publishing immutable replacement partitions.
     *
     * <p>Without a predicate, removes all references using metadata row counts without decoding
     * data. No matches preserve the version and retained history. Candidate partitions use the
     * same pruning and typed comparison semantics as SELECT, preserving surviving row order.
     *
     * @param tableName the target table
     * @param where the optional typed comparison, empty to delete every row
     * @return deletion counts and the resulting version
     * @throws IllegalArgumentException if the table or predicate is invalid
     * @throws ArithmeticException if a changing delete would overflow the version
     * @throws UncheckedIOException if reading, staging or publication fails
     * @since 0.7
     * @version 0.7
     */
    public synchronized DeleteResult delete(String tableName, Optional<Predicate> where) {
        var base = requireTable(tableName);
        new Binder(this).bind(new DeleteStatement(tableName, where), base);
        return delete(base, where);
    }

    /**
     * Executes a bound delete against the captured statement snapshot.
     *
     * @param base the captured table
     * @param where the validated optional predicate
     * @return the committed or unchanged version and work counts
     * @throws ArithmeticException if a changing statement would overflow
     * @throws UncheckedIOException if data access or publication fails
     * @since 0.7
     */
    synchronized DeleteResult delete(Table base, Optional<Predicate> where) {
        long start = startCall();
        long deleted = 0;
        int rewritten = 0;
        int carried = 0;
        int removed = 0;
        boolean committed = false;
        List<Path> staged = new ArrayList<>();
        List<DataFile> candidate = new ArrayList<>();
        int index = where.isPresent() ? Planner.columnIndex(base.columns(), where.orElseThrow().columnName()) : -1;
        try {
            for (var file : base.files()) {
                for (var partition : file.partitions()) {
                    if (where.isEmpty()) {
                        deleted = Math.addExact(deleted, partition.rowCount());
                        removed++;
                        continue;
                    }
                    var predicate = where.orElseThrow();
                    var type = base.columns().get(index).type();
                    if (PartitionPruner.canPrune(type, partition.chunks().get(index).statistics(),
                            predicate.comparison(), predicate.constant())) {
                        candidate.add(new DataFile(file.path(), List.of(partition)));
                        carried++;
                        continue;
                    }
                    List<Object[]> survivors = new ArrayList<>();
                    var scan = new ScanOperator(dataPath(file.path()), base.columns(), List.of(partition));
                    try {
                        scan.open();
                        Object[] row;
                        while ((row = scan.next()) != null) {
                            if (!predicate.comparison().matches(type.compare(row[index], predicate.constant()))) {
                                survivors.add(row);
                            }
                        }
                    } finally {
                        scan.close();
                    }
                    int matches = partition.rowCount() - survivors.size();
                    if (matches == 0) {
                        candidate.add(new DataFile(file.path(), List.of(partition)));
                        carried++;
                    } else {
                        // Check before staging any replacement for an overflowing version.
                        Math.incrementExact(base.current().tableVersion());
                        deleted = Math.addExact(deleted, matches);
                        if (survivors.isEmpty()) removed++;
                        else candidate.add(stagePartition(base, survivors, rewritten++, staged));
                    }
                }
            }
            long version = base.current().tableVersion();
            if (deleted > 0) {
                version = Math.incrementExact(version);
                commit(base, candidate, version);
                committed = true;
                catalogs.cleanup(tables.values());
            }
            LOGGER.debug("operation=delete table={} rowsDeleted={} partitionsRewritten={} partitionsCarried={} partitionsRemoved={} version={} durationMs={}",
                    clean(base.name()), deleted, rewritten, carried, removed, version, elapsed(start));
            return new DeleteResult(version, deleted, rewritten, carried, removed);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete from " + base.name(), e);
        } finally {
            if (!committed) for (Path path : staged) catalogs.remove(path);
        }
    }

    /**
     * Finishes one uniquely named partition file without replacing any existing file.
     *
     * <p>The file is written through a buffered stream, because every value written directly
     * to a {@link RandomAccessFile} would be a separate system call.
     *
     * @param table the schema and identity
     * @param rows the nonempty typed row buffer
     * @param index the logical index for statistics logging
     * @param staged the paths owned by this statement for rollback
     * @return the completed partition reference
     * @throws IOException if creating or writing the file fails
     */
    private DataFile stagePartition(Table table, List<Object[]> rows,
                                                 int index, List<Path> staged) throws IOException {
        String relative = "data/" + table.id() + "-" + UUID.randomUUID() + ".bin";
        Path path = catalogs.dataPath(relative);
        Files.createFile(path);
        staged.add(path);
        Partition partition;
        try (var output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(path, StandardOpenOption.WRITE), OUTPUT_BUFFER_BYTES))) {
            BinaryColumnCodec.writeHeader(output);
            partition = writePartition(output, table, rows, index);
        }
        return new DataFile(relative, List.of(partition));
    }

    /**
     * Publishes a fully prepared next version and installs it in memory.
     *
     * @param base the captured current state
     * @param files the candidate ordered file references
     * @param version the checked next version
     * @throws IOException if catalog validation or publication fails
     */
    private void commit(Table base, List<DataFile> files, long version) throws IOException {
        var replacement = new Table(2, base.id(), base.name(), base.columns(),
                new Snapshot(version, files), base.current());
        catalogs.save(replacement);
        tables.put(base.name(), replacement);
    }

    /**
     * Resolves a captured file reference without consulting the current table state.
     *
     * @param relative the captured path relative to the root
     * @return the absolute binary path
     * @throws UncheckedIOException if the reference is invalid
     * @since 0.7
     */
    Path dataPath(String relative) {
        try { return catalogs.dataPath(relative); }
        catch (IOException e) { throw new UncheckedIOException("Cannot resolve data file " + relative, e); }
    }

    /**
     * Writes one row partition as consecutive column chunks, directly after the file header.
     *
     * <p>Each partition file holds exactly one partition, so its first chunk starts at
     * {@link BinaryColumnCodec#HEADER_SIZE}. The output is a stream and cannot report its
     * position, so chunk offsets and lengths are computed from the byte counts returned by
     * {@link BinaryColumnCodec#writeValue}.
     *
     * @param output the buffered destination, positioned directly after the header
     * @param table the destination table metadata
     * @param rows the typed rows to write
     * @param partitionIndex the zero-based partition index used for logging
     * @return metadata describing the written partition
     * @throws IOException if a column value cannot be written
     */
    private Partition writePartition(DataOutput output, Table table,
                                                   List<Object[]> rows, int partitionIndex) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        long offset = BinaryColumnCodec.HEADER_SIZE;
        for (int column = 0; column < table.columns().size(); column++) {
            ColumnSpec spec = table.columns().get(column);
            List<Object> values = new ArrayList<>(rows.size());
            long length = 0;
            for (Object[] row : rows) {
                values.add(row[column]);
                length += BinaryColumnCodec.writeValue(output, spec.type(), row[column]);
            }
            PartitionStatistics statistics = PartitionStatistics.compute(spec.type(), values);
            LOGGER.debug("table={} partition={} column={} min={} max={}", clean(table.name()), partitionIndex,
                    clean(spec.name()), clean(statistics.min()), clean(statistics.max()));
            chunks.add(new Chunk(offset, length, statistics));
            offset += length;
        }
        return new Partition(rows.size(), List.copyOf(chunks));
    }

    /**
     * Looks up a table by logical name.
     *
     * @param name the table name
     * @return the current immutable table snapshot
     * @throws IllegalArgumentException if the table is unknown
     * @since 0.4
     */
    synchronized Table requireTable(String name) {
        var table = tables.get(name);
        if (table == null) throw new IllegalArgumentException("Unknown table: " + name);
        return table;
    }

    /**
     * Returns the first current data file, or {@code null} when the table is empty.
     *
     * @param tableName the table name
     * @return the first absolute data path, or {@code null} when no file is referenced
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
     * @version 0.7
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
     * @version 0.7
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

}

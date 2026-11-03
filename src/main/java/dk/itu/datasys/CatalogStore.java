package dk.itu.datasys;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists immutable format-2 catalogs with two retained committed snapshots.
 *
 * <p>Atomic catalog replacement is the disk commit point. Binary files remain format 1.
 *
 * @author Team 1
 * @version 0.7
 * @since 0.2
 */
final class CatalogStore {
    /** Logger for cleanup failures that must not invalidate a committed statement. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogStore.class);
    /** Canonical UUID spelling used in engine-owned filenames. */
    private static final String UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** Recognized partition filenames, including the table identity and a unique partition ID. */
    private static final Pattern DATA_NAME = Pattern.compile(UUID_TEXT + "-" + UUID_TEXT + "\\.bin");
    /** Recognized catalog-temporary filenames emitted by createTempFile. */
    private static final Pattern TEMP_NAME = Pattern.compile(UUID_TEXT + "-[0-9]+\\.tmp");

    /**
     * Describes one column's bytes and catalog-only pruning statistics.
     *
     * @param offset the absolute starting byte offset
     * @param length the encoded byte length
     * @param statistics the canonical typed minimum and maximum text
     * @since 0.2
     * @version 0.7
     */
    record Chunk(long offset, long length, PartitionStatistics statistics) { }

    /**
     * Describes a nonempty row partition with immutable chunks in schema order.
     *
     * @param rowCount the number of rows
     * @param chunks the chunks in schema order
     * @since 0.2
     * @version 0.7
     */
    record Partition(int rowCount, List<Chunk> chunks) {
        /**
         * Copies the chunk list to prevent later metadata mutation.
         *
         * @param rowCount the number of rows
         * @param chunks the chunks in schema order
         */
        Partition { chunks = List.copyOf(chunks); }
    }

    /**
     * Describes an immutable ordered group of partitions in one binary file.
     *
     * @param path the path relative to the storage root
     * @param partitions the partitions in logical row order
     * @since 0.2
     * @version 0.7
     */
    record DataFile(String path, List<Partition> partitions) {
        /**
         * Copies the partition list to prevent later metadata mutation.
         *
         * @param path the relative file path
         * @param partitions the ordered partitions
         */
        DataFile { partitions = List.copyOf(partitions); }
    }

    /**
     * Pins one committed version and its ordered immutable data references.
     *
     * @param tableVersion the nonnegative commit counter
     * @param files the ordered data references
     * @since 0.7
     * @version 0.7
     */
    record Snapshot(long tableVersion, List<DataFile> files) {
        /**
         * Copies the data-reference list.
         *
         * @param tableVersion the commit counter
         * @param files the ordered references
         */
        Snapshot { files = List.copyOf(files); }
    }

    /**
     * Stores stable table identity, schema and the current and previous committed versions.
     *
     * @param version the catalog format version, always 2
     * @param id the canonical table UUID
     * @param name the logical table name
     * @param columns the immutable ordered schema
     * @param current the current committed snapshot
     * @param previous the immediately preceding snapshot, or {@code null} for version 0
     * @since 0.2
     * @version 0.7
     */
    record Table(int version, String id, String name, List<ColumnSpec> columns,
                 Snapshot current, Snapshot previous) {
        /**
         * Copies the ordered schema.
         *
         * @param version the catalog format version
         * @param id the canonical UUID
         * @param name the logical name
         * @param columns the schema
         * @param current the current snapshot
         * @param previous the previous snapshot or {@code null}
         */
        Table { columns = List.copyOf(columns); }

        /**
         * Returns the current snapshot's immutable ordered file references.
         *
         * @return current file references
         * @since 0.2
         * @version 0.7
         */
        List<DataFile> files() { return current.files(); }
    }

    /** Normalized absolute storage root. */
    private final Path root;
    /** Directory containing published table catalogs. */
    private final Path catalogs;
    /** Filesystem operations used for publication and cleanup. */
    private final StorageIo io;
    /** Strict serializer for catalog records. */
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

    /**
     * Opens a storage catalog with standard filesystem operations.
     *
     * @param root the storage directory
     * @throws IOException if directories cannot be created
     */
    CatalogStore(Path root) throws IOException { this(root, new StorageIo()); }

    /**
     * Opens a storage catalog with injectable filesystem operations.
     *
     * @param root the storage directory
     * @param io the filesystem operations
     * @throws IOException if directories cannot be created
     * @since 0.7
     */
    CatalogStore(Path root, StorageIo io) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.io = io;
        catalogs = this.root.resolve("catalog");
        Files.createDirectories(catalogs);
        Files.createDirectories(this.root.resolve("data"));
    }

    /**
     * Loads every table, validating all catalogs before the caller may clean orphan files.
     *
     * @return tables indexed by logical name
     * @throws IOException if any catalog is unreadable, unsupported or invalid
     */
    Map<String, Table> load() throws IOException {
        Map<String, Table> tables = new HashMap<>();
        try (var paths = Files.newDirectoryStream(catalogs, "*.json")) {
            for (Path path : paths) {
                var tree = mapper.readTree(path.toFile());
                if (tree == null || !tree.path("version").isIntegralNumber()
                        || tree.path("version").asLong() != 2) {
                    throw new IOException("Unsupported catalog format: " + path);
                }
                Table table = mapper.treeToValue(tree, Table.class);
                validate(table);
                if (!path.getFileName().toString().equals(table.id() + ".json")) {
                    throw new IOException("Catalog filename does not match table ID: " + path);
                }
                if (tables.putIfAbsent(table.name(), table) != null) {
                    throw new IOException("Duplicate catalog table name");
                }
            }
        }
        return tables;
    }

    /**
     * Validates both versions and atomically replaces the table catalog.
     *
     * <p>Temporary-file cleanup is best effort, including after publication; it never reports a
     * successful publication as a rollback. Atomic moves are required with no fallback.
     *
     * @param table the fully prepared replacement
     * @throws IOException if validation, serialization or publication fails before commit
     */
    void save(Table table) throws IOException {
        validate(table);
        Path temporary = Files.createTempFile(catalogs, table.id() + "-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), table);
            io.publish(temporary, catalogs.resolve(table.id() + ".json"));
        } finally {
            remove(temporary);
        }
    }

    /**
     * Resolves a relative reference to a direct child of the data directory.
     *
     * @param relative the relative data path
     * @return the normalized absolute path
     * @throws IOException if the path is absolute or escapes the data directory
     */
    Path dataPath(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !root.resolve("data").equals(path.getParent())) {
            throw new IOException("Invalid catalog data path: " + relative);
        }
        return path;
    }

    /**
     * Removes recognized files absent from every current and previous snapshot.
     *
     * <p>Each failure is logged and retried at the next startup. Unknown filenames and directories
     * are preserved. The caller must first load and validate every catalog, and must call this
     * only while no statement runs: it also removes partition files a running statement is still
     * staging or reading, and catalog temporary files of a save in progress. The storage engine
     * therefore calls it only when it opens the directory.
     *
     * @param tables all loaded tables after the latest heap installation
     * @since 0.7
     * @version 0.8
     */
    void cleanup(Collection<Table> tables) {
        Set<String> live = new HashSet<>();
        for (Table table : tables) {
            for (DataFile file : table.current().files()) live.add(file.path());
            if (table.previous() != null) {
                for (DataFile file : table.previous().files()) live.add(file.path());
            }
        }
        cleanupDirectory(root.resolve("data"), DATA_NAME, live);
        cleanupDirectory(catalogs, TEMP_NAME, Set.of());
    }

    /**
     * Removes unreferenced recognized regular files in one engine directory.
     *
     * @param directory the directory to inspect
     * @param recognized the owned filename pattern
     * @param live the referenced paths relative to the root
     */
    private void cleanupDirectory(Path directory, Pattern recognized, Set<String> live) {
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (recognized.matcher(path.getFileName().toString()).matches()
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !live.contains(relative)) {
                    remove(path);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("operation=cleanup path={} error={}", StorageEngine.clean(directory), StorageEngine.clean(e.getMessage()));
        }
    }

    /**
     * Deletes a known unreferenced or temporary file without changing commit success.
     *
     * @param path the file to delete
     */
    void remove(Path path) {
        try { io.delete(path); }
        catch (IOException | RuntimeException e) {
            LOGGER.error("operation=cleanup path={} error={}", StorageEngine.clean(path), StorageEngine.clean(e.getMessage()));
        }
    }

    /**
     * Validates an ordered table schema.
     *
     * @param name the table name
     * @param columns the ordered definitions
     * @throws IllegalArgumentException if the name or schema is missing, incomplete or duplicated
     */
    static void validateSchema(String name, List<ColumnSpec> columns) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Table name is required");
        if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("Schema must contain columns");
        Set<String> names = new HashSet<>();
        for (ColumnSpec column : columns) {
            if (column == null || column.name() == null || column.name().isBlank() || column.type() == null) {
                throw new IllegalArgumentException("Column name and type are required");
            }
            if (!names.add(column.name())) throw new IllegalArgumentException("Duplicate column: " + column.name());
        }
    }

    /**
     * Validates table identity, history and both snapshots without opening binary files.
     *
     * @param table the candidate catalog
     * @throws IOException if an invariant is violated
     */
    private void validate(Table table) throws IOException {
        try {
            if (table == null || table.version() != 2) throw new IllegalArgumentException("Unsupported catalog format");
            if (!UUID.fromString(table.id()).toString().equals(table.id())) throw new IllegalArgumentException("Invalid table ID");
            validateSchema(table.name(), table.columns());
            if (table.current().tableVersion() < 0) throw new IllegalArgumentException("Negative table version");
            if (table.current().tableVersion() == 0) {
                if (table.previous() != null || !table.files().isEmpty()) throw new IllegalArgumentException("Invalid initial version");
            } else if (table.previous() == null
                    || table.previous().tableVersion() != table.current().tableVersion() - 1) {
                throw new IllegalArgumentException("Invalid retained history");
            }
            validateSnapshot(table, table.current());
            if (table.previous() != null) validateSnapshot(table, table.previous());
        } catch (RuntimeException e) {
            throw new IOException("Invalid catalog: " + e.getMessage(), e);
        }
    }

    /**
     * Validates ordered chunk bounds and typed ranges while allowing carried-file gaps.
     *
     * @param table the table identity and schema
     * @param snapshot the version to validate
     */
    private void validateSnapshot(Table table, Snapshot snapshot) {
        Map<String, Long> ends = new HashMap<>();
        if (snapshot.tableVersion() == 0 && !snapshot.files().isEmpty()) {
            throw new IllegalArgumentException("Initial snapshot must be empty");
        }
        for (DataFile file : snapshot.files()) {
            if (!file.path().startsWith("data/" + table.id() + "-")
                    || !DATA_NAME.matcher(file.path().substring(5)).matches() || file.partitions().isEmpty()) {
                throw new IllegalArgumentException("Invalid data filename or empty reference");
            }
            long end = ends.getOrDefault(file.path(), (long) BinaryColumnCodec.HEADER_SIZE);
            for (Partition partition : file.partitions()) {
                if (partition.rowCount() < 1 || partition.chunks().size() != table.columns().size()) {
                    throw new IllegalArgumentException("Invalid partition shape");
                }
                if (partition.chunks().getFirst().offset() < end) throw new IllegalArgumentException("Overlapping chunk bounds");
                end = partition.chunks().getFirst().offset();
                for (int i = 0; i < partition.chunks().size(); i++) {
                    Chunk chunk = partition.chunks().get(i);
                    ColumnType type = table.columns().get(i).type();
                    long minimum = (long) partition.rowCount() * (type == ColumnType.STRING ? 4 : 8);
                    if (chunk.offset() != end || chunk.length() < minimum
                            || (type != ColumnType.STRING && chunk.length() != minimum)) {
                        throw new IllegalArgumentException("Invalid chunk bounds");
                    }
                    end = Math.addExact(end, chunk.length());
                    Object min = type.parse(chunk.statistics().min());
                    Object max = type.parse(chunk.statistics().max());
                    if (type.compare(min, max) > 0) throw new IllegalArgumentException("Invalid statistics range");
                }
            }
            ends.put(file.path(), end);
        }
    }
}

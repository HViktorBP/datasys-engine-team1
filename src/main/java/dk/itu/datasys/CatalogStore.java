package dk.itu.datasys;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Persists and validates immutable table-catalog snapshots.
 *
 * <p>Publishing a catalog is the commit point for a copy operation.
 */
final class CatalogStore {
    /**
     * Describes one column's bytes and pruning statistics within a partition.
     *
     * @param offset the absolute starting byte offset
     * @param length the encoded byte length
     * @param statistics the minimum and maximum values stored as canonical catalog text
     */
    record Chunk(long offset, long length, PartitionStatistics statistics) { }

    /**
     * Describes a row partition and its chunks in schema-column order.
     *
     * @param rowCount the number of rows in the partition
     * @param chunks the column chunks in schema order
     */
    record Partition(int rowCount, List<Chunk> chunks) { }

    /**
     * Describes a table data file and its ordered partitions.
     *
     * @param path the path relative to the storage root
     * @param partitions the partitions in input order
     */
    record DataFile(String path, List<Partition> partitions) { }

    /**
     * Describes a complete persisted table snapshot.
     *
     * @param version the catalog format version
     * @param id the table's canonical UUID
     * @param name the logical table name
     * @param columns the ordered table schema
     * @param files the published table data files
     */
    record Table(int version, String id, String name, List<ColumnSpec> columns, List<DataFile> files) { }

    /** Normalized absolute storage root. */
    private final Path root;
    /** Directory containing published catalog snapshots. */
    private final Path catalogs;
    /** Strict JSON serializer used for catalog snapshots. */
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Opens a catalog rooted in a storage directory, creating its subdirectories when absent.
     *
     * @param root the storage directory
     * @throws IOException if the catalog or data directory cannot be created
     */
    CatalogStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        catalogs = this.root.resolve("catalog");
        Files.createDirectories(catalogs);
        Files.createDirectories(this.root.resolve("data"));
    }

    /**
     * Loads and validates every catalog snapshot in the catalog directory.
     *
     * @return the catalog tables indexed by logical table name
     * @throws IOException if a catalog cannot be read or is invalid
     */
    Map<String, Table> load() throws IOException {
        Map<String, Table> tables = new HashMap<>();
        try (var paths = Files.newDirectoryStream(catalogs, "*.json")) {
            for (Path path : paths) {
                Table table = mapper.readValue(path.toFile(), Table.class);
                validate(table);
                if (!path.getFileName().toString().equals(table.id() + ".json")) {
                    throw new IOException("Catalog filename does not match table ID: " + path);
                }
                if (tables.putIfAbsent(table.name(), table) != null) throw new IOException("Duplicate catalog table name");
            }
        }
        return tables;
    }

    /**
     * Validates and publishes a table snapshot, using atomic replacement when supported.
     *
     * @param table the table snapshot to persist
     * @throws IOException if the snapshot is invalid or cannot be published
     */
    void save(Table table) throws IOException {
        validate(table);
        Path temporary = Files.createTempFile(catalogs, table.id() + "-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), table);
            publish(temporary, catalogs.resolve(table.id() + ".json"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Resolves a catalog path while ensuring it identifies a direct child of the data directory.
     *
     * @param relative the catalog path relative to the storage root
     * @return the normalized absolute data path
     * @throws IOException if the path is absolute or escapes the data directory
     */
    Path dataPath(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !path.getParent().equals(root.resolve("data"))) {
            throw new IOException("Invalid catalog data path: " + relative);
        }
        return path;
    }

    /**
     * Publishes a file by replacing its target, preferring an atomic move when supported.
     *
     * @param source the file to publish
     * @param target the final destination
     * @throws IOException if neither an atomic nor regular replacement move succeeds
     */
    static void publish(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Validates the names and types in an ordered table schema.
     *
     * @param name the table name
     * @param columns the ordered column definitions
     * @throws IllegalArgumentException if the name or columns are absent, a column is incomplete,
     *                                  or column names are duplicated
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
     * Validates a complete table snapshot and all nested file metadata.
     *
     * @param table the snapshot to validate
     * @throws IOException if any catalog invariant is violated
     */
    private void validate(Table table) throws IOException {
        try {
            if (table == null || table.version() != 1) throw new IllegalArgumentException("Unsupported catalog version");
            if (!UUID.fromString(table.id()).toString().equals(table.id())) throw new IllegalArgumentException("Invalid table ID");
            validateSchema(table.name(), table.columns());
            if (table.files().size() > 1) throw new IllegalArgumentException("Multiple copies are unsupported");
            for (DataFile file : table.files()) {
                if (!file.path().equals("data/" + table.id() + "-0.bin")) throw new IllegalArgumentException("Invalid data filename");
                dataPath(file.path());
                long end = BinaryColumnCodec.HEADER_SIZE;
                for (Partition partition : file.partitions()) {
                    if (partition.rowCount() < 1 || partition.chunks().size() != table.columns().size()) {
                        throw new IllegalArgumentException("Invalid partition shape");
                    }
                    for (int i = 0; i < partition.chunks().size(); i++) {
                        Chunk chunk = partition.chunks().get(i);
                        ColumnType type = table.columns().get(i).type();
                        long minimumLength = (long) partition.rowCount() * (type == ColumnType.STRING ? 4 : 8);
                        if (chunk.offset() != end || chunk.length() < minimumLength
                                || (type != ColumnType.STRING && chunk.length() != minimumLength)) {
                            throw new IllegalArgumentException("Invalid chunk bounds");
                        }
                        end = Math.addExact(end, chunk.length());
                        Object min = type.parse(chunk.statistics().min());
                        Object max = type.parse(chunk.statistics().max());
                        if (type.compare(min, max) > 0) throw new IllegalArgumentException("Invalid statistics range");
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid catalog: " + e.getMessage(), e);
        }
    }
}

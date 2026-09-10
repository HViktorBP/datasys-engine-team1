package dk.itu.datasys;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Catalogs are immutable snapshots; publishing one is the copy's commit point. */
final class CatalogStore {
    record Chunk(long offset, long length, PartitionStatistics statistics) { }
    record Partition(int rowCount, List<Chunk> chunks) { }
    record DataFile(String path, List<Partition> partitions) { }
    record Table(int version, String id, String name, List<ColumnSpec> columns, List<DataFile> files) { }

    private final Path root;
    private final Path catalogs;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    CatalogStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        catalogs = this.root.resolve("catalog");
        Files.createDirectories(catalogs);
        Files.createDirectories(this.root.resolve("data"));
    }

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

    Path dataPath(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !path.getParent().equals(root.resolve("data"))) {
            throw new IOException("Invalid catalog data path: " + relative);
        }
        return path;
    }

    static void publish(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

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

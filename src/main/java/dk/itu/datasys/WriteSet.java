package dk.itu.datasys;

import dk.itu.datasys.CatalogStore.DataFile;
import dk.itu.datasys.CatalogStore.Partition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Describes how a changing statement changes a table's partition list, relative to its base.
 *
 * <p>A {@code COPY} only appends new partition files. A {@code DELETE} replaces some of its base's
 * partitions: each one it touched maps to the file holding its surviving rows, or to nothing when
 * every row matched. Partitions the statement carried over are not recorded, because it did not
 * change them.
 *
 * <p>{@link #applyTo(List)} replays the change on the partition list committed when the statement
 * validates. Without a concurrent commit that list is the base's own. After one, the change is
 * rebased onto the newer list, unless that commit already replaced a partition this statement
 * replaced too, which is a true conflict.
 *
 * @author Team 1
 * @version 0.8
 * @since 0.8
 */
final class WriteSet {
    /**
     * Identifies one partition by its file and its exact metadata, including chunk offsets.
     *
     * <p>Partition files have unique names that are never reused, and a file that holds several
     * partitions stores them at distinct offsets, so equal keys denote the same stored bytes.
     *
     * @param path the data file path relative to the storage root
     * @param partition the partition metadata within that file
     */
    record PartitionKey(String path, Partition partition) { }

    /** Replaced partitions in base order, each mapped to its replacement files; empty means removed. */
    private final Map<PartitionKey, List<DataFile>> replacements;
    /** New partition files to add after every existing one. */
    private final List<DataFile> appended;

    /**
     * Creates a write set from replaced partitions and appended files.
     *
     * @param replacements each replaced partition mapped to its replacement files, in base order;
     *                     an empty list removes the partition
     * @param appended new partition files to add at the end, in order
     */
    WriteSet(Map<PartitionKey, List<DataFile>> replacements, List<DataFile> appended) {
        var copy = new LinkedHashMap<PartitionKey, List<DataFile>>();
        replacements.forEach((key, files) -> copy.put(key, List.copyOf(files)));
        this.replacements = Collections.unmodifiableMap(copy);
        this.appended = List.copyOf(appended);
    }

    /**
     * Creates the write set of a statement that only adds partition files, such as {@code COPY}.
     *
     * @param files the new partition files, in order
     * @return a write set that replaces nothing
     */
    static WriteSet append(List<DataFile> files) {
        return new WriteSet(Map.of(), files);
    }

    /**
     * Replays this change on a committed partition list.
     *
     * <p>Untouched files keep their position and identity. In a touched file, each replaced
     * partition is substituted by its replacement files at the same position, or dropped, and the
     * file's other partitions remain as single-partition references. Appended files follow last.
     *
     * @param current the committed partition list at validation
     * @return the resulting partition list, or empty if a partition this change replaces is no
     *         longer part of {@code current}, because another commit already replaced it
     */
    Optional<List<DataFile>> applyTo(List<DataFile> current) {
        Set<PartitionKey> pending = new HashSet<>(replacements.keySet());
        List<DataFile> result = new ArrayList<>();
        for (DataFile file : current) {
            boolean touched = file.partitions().stream()
                    .anyMatch(partition -> replacements.containsKey(new PartitionKey(file.path(), partition)));
            if (!touched) {
                result.add(file);
                continue;
            }
            for (Partition partition : file.partitions()) {
                var key = new PartitionKey(file.path(), partition);
                var replacement = replacements.get(key);
                if (replacement == null) {
                    result.add(new DataFile(file.path(), List.of(partition)));
                } else {
                    result.addAll(replacement);
                    pending.remove(key);
                }
            }
        }
        if (!pending.isEmpty()) return Optional.empty();
        result.addAll(appended);
        return Optional.of(List.copyOf(result));
    }
}

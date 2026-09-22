package dk.itu.datasys;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads every row from a caller-supplied list of partitions and never applies a predicate.
 *
 * @author Team 1
 * @version 0.4
 * @since 0.4
 */
public final class ScanOperator implements Operator {
    /** Binary file containing the handed partitions, or {@code null} when none are scanned. */
    private final Path dataFile;
    /** Ordered column definitions used to decode chunks. */
    private final List<ColumnSpec> columns;
    /** Partitions to read, in catalog order. */
    private final List<CatalogStore.Partition> partitions;
    /** Open data file, or {@code null} when the scan is empty or closed. */
    private RandomAccessFile input;
    /** Index of the next partition to load. */
    private int partitionIndex;
    /** Decoded column vectors for the current partition. */
    private Object[][] currentColumns;
    /** Next row index within the current partition. */
    private int rowIndex;
    /** Number of rows in the current partition. */
    private int rowCount;

    /**
     * Creates a scan over the supplied partitions of one data file.
     *
     * @param dataFile the published binary file, ignored when {@code partitions} is empty
     * @param columns the table schema in column order
     * @param partitions the partitions to read, which may be empty
     */
    ScanOperator(Path dataFile, List<ColumnSpec> columns, List<CatalogStore.Partition> partitions) {
        this.dataFile = dataFile;
        this.columns = List.copyOf(columns);
        this.partitions = List.copyOf(partitions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void open() {
        partitionIndex = 0;
        currentColumns = null;
        rowIndex = 0;
        rowCount = 0;
        if (partitions.isEmpty()) {
            return;
        }
        try {
            input = new RandomAccessFile(dataFile.toFile(), "r");
            BinaryColumnCodec.checkHeader(input);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dataFile, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] next() {
        while (true) {
            if (currentColumns != null && rowIndex < rowCount) {
                Object[] row = new Object[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    row[i] = currentColumns[i][rowIndex];
                }
                rowIndex++;
                return row;
            }
            if (!loadNextPartition()) {
                return null;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (input != null) {
            try {
                input.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot close " + dataFile, e);
            } finally {
                input = null;
            }
        }
    }

    /**
     * Loads the next handed partition into {@link #currentColumns}.
     *
     * @return {@code true} when a partition was loaded
     */
    private boolean loadNextPartition() {
        if (input == null || partitionIndex >= partitions.size()) {
            currentColumns = null;
            return false;
        }
        var partition = partitions.get(partitionIndex++);
        try {
            currentColumns = new Object[columns.size()][];
            for (int i = 0; i < columns.size(); i++) {
                var chunk = partition.chunks().get(i);
                currentColumns[i] = BinaryColumnCodec.readChunk(input, columns.get(i).type(),
                        chunk.offset(), chunk.length(), partition.rowCount());
            }
            rowCount = partition.rowCount();
            rowIndex = 0;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dataFile, e);
        }
    }
}

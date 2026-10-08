package dk.itu.datasys;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads every row from a caller-supplied list of partitions and never applies a predicate.
 *
 * <p>The first close after each open attempt logs actual scan progress at debug, even when
 * opening or reading failed. The summary counts fully decoded partitions and rows returned
 * by this scan before any parent filter, with elapsed milliseconds from open through close.
 *
 * @author Team 1
 * @version 0.5
 * @since 0.4
 */
public final class ScanOperator implements Operator {
    /** Diagnostic logger for completed or partial scan summaries. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ScanOperator.class);
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
    /** Number of partitions fully decoded during the current open attempt. */
    private int partitionsRead;
    /** Number of rows returned during the current open attempt, before filtering. */
    private long rowsOut;
    /** Monotonic start time of the current open attempt in nanoseconds. */
    private long startNanos;
    /** Whether the current open attempt still needs its one close summary. */
    private boolean summaryPending;

    /**
     * Creates a scan over the supplied partitions of one data file.
     *
     * @param dataFile the published binary file, ignored when {@code partitions} is empty
     * @param columns the table schema in column order
     * @param partitions the partitions to read, which may be empty
     * @since 0.4
     * @version 0.5
     */
    ScanOperator(Path dataFile, List<ColumnSpec> columns, List<CatalogStore.Partition> partitions) {
        this.dataFile = dataFile;
        this.columns = List.copyOf(columns);
        this.partitions = List.copyOf(partitions);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resets progress counters and starts the summary timer, including for empty scans
     * and attempts that fail to open or validate the file.
     *
     * @since 0.4
     * @version 0.5
     */
    @Override
    public void open() {
        startNanos = System.nanoTime();
        summaryPending = true;
        partitionsRead = 0;
        rowsOut = 0;
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
     *
     * @since 0.4
     * @version 0.5
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
                rowsOut++;
                return row;
            }
            if (!loadNextPartition()) {
                return null;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The first call after each open attempt logs {@code operation=scan}, the sanitized
     * file path, {@code partitionsRead}, {@code rowsOut}, and {@code durationMs} at debug,
     * including partial progress after a failure. Subsequent closes do not log again;
     * closing before any open does not log. A close failure still produces the summary.
     *
     * @throws UncheckedIOException if the data file cannot be closed
     * @since 0.4
     * @version 0.5
     */
    @Override
    public void close() {
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close " + dataFile, e);
        } finally {
            input = null;
            if (summaryPending) {
                summaryPending = false;
                LOGGER.debug("operation=scan file={} partitionsRead={} rowsOut={} durationMs={}",
                        StorageEngine.clean(dataFile), partitionsRead, rowsOut,
                        (System.nanoTime() - startNanos) / 1_000_000);
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
            partitionsRead++;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dataFile, e);
        }
    }
}

package dk.itu.datasys;

/**
 * Summarizes the partition activity of a successful storage scan.
 *
 * @param partitionsTotal the number of partitions considered
 * @param partitionsRead the number of partitions read from storage
 * @param partitionsPruned the number of partitions skipped using statistics
 * @author Team 1
 * @version 0.3
 * @since 0.2
 */
public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) { }

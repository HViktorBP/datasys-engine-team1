package dk.itu.datasys;

import java.util.List;

/**
 * Stores a partition's minimum and maximum values in canonical catalog text form.
 *
 * <p>Long and double values use their standard decimal string representations; string values are
 * stored unchanged. The column type reconstructs the typed values when pruning a scan.
 *
 * @param min the minimum value encoded as text
 * @param max the maximum value encoded as text
 */
record PartitionStatistics(String min, String max) {
    /**
     * Computes the minimum and maximum over a non-empty list of typed values.
     *
     * @param type the common type of all values
     * @param values the values to summarize
     * @return the computed statistics encoded as text
     * @throws IllegalArgumentException if {@code values} is empty
     */
    static PartitionStatistics compute(ColumnType type, List<Object> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Empty partition");
        Object min = values.getFirst();
        Object max = min;
        for (Object value : values) {
            if (type.compare(value, min) < 0) min = value;
            if (type.compare(value, max) > 0) max = value;
        }
        return new PartitionStatistics(min.toString(), max.toString());
    }
}

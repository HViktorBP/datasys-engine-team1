package dk.itu.datasys;

import java.util.List;

record PartitionStatistics(String min, String max) {
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

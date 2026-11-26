package dk.itu.datasys;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/**
 * Stores a partition's minimum, maximum, and optional distinct-value count in catalog text form.
 *
 * <p>Long and double values use their standard decimal string representations; string values are
 * stored unchanged. The column type reconstructs the typed values when pruning a scan.
 * {@code ndv} is the exact number of distinct values in this partition, or {@code null} when the
 * partition was written before NDV was collected. Catalog JSON may omit {@code ndv}; a custom
 * deserializer accepts that so v0.10 catalogs still load under
 * {@code FAIL_ON_MISSING_CREATOR_PROPERTIES}.
 *
 * @param min the minimum value encoded as text
 * @param max the maximum value encoded as text
 * @param ndv the exact distinct-value count, or {@code null} when absent
 * @author Team 1
 * @version 0.11
 * @since 0.2
 */
@JsonDeserialize(using = PartitionStatistics.JsonReader.class)
record PartitionStatistics(String min, String max, Long ndv) {
    /**
     * Creates statistics without NDV, as in catalogs written before v0.11.
     *
     * @param min the minimum value encoded as text
     * @param max the maximum value encoded as text
     * @since 0.2
     * @version 0.11
     */
    PartitionStatistics(String min, String max) {
        this(min, max, null);
    }

    /**
     * Computes the minimum, maximum, and exact NDV over a non-empty list of typed values.
     *
     * <p>{@code -0.0} and {@code 0.0} count as one distinct double, matching join-key equality.
     *
     * @param type the common type of all values
     * @param values the values to summarize
     * @return the computed statistics encoded as text, with {@code ndv} set
     * @throws IllegalArgumentException if {@code values} is empty
     * @since 0.2
     * @version 0.11
     */
    static PartitionStatistics compute(ColumnType type, List<Object> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Empty partition");
        Object min = values.getFirst();
        Object max = min;
        var distinct = new HashSet<Object>();
        for (Object value : values) {
            if (type.compare(value, min) < 0) min = value;
            if (type.compare(value, max) > 0) max = value;
            distinct.add(distinctKey(type, value));
        }
        return new PartitionStatistics(min.toString(), max.toString(), (long) distinct.size());
    }

    /**
     * Normalizes a value so SQL-equal numbers share one distinct slot.
     *
     * @param type the column type
     * @param value a typed column value
     * @return the value, with {@code -0.0} replaced by {@code 0.0}
     */
    private static Object distinctKey(ColumnType type, Object value) {
        if (type == ColumnType.DOUBLE && value instanceof Double number && number == 0.0) {
            return 0.0;
        }
        return value;
    }

    /**
     * Reads catalog statistics so that a missing {@code ndv} field means {@code null}.
     *
     * @since 0.11
     */
    static final class JsonReader extends JsonDeserializer<PartitionStatistics> {
        /**
         * Creates a reader that treats a missing {@code ndv} field as {@code null}.
         */
        JsonReader() { }

        /**
         * {@inheritDoc}
         *
         * @param parser the JSON parser positioned at the statistics object
         * @param context the Jackson deserialization context
         * @return statistics with {@code ndv} set only when the field is present and non-null
         * @throws IOException if the JSON cannot be read
         */
        @Override
        public PartitionStatistics deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() == JsonToken.START_OBJECT) {
                parser.nextToken();
            }
            String min = null;
            String max = null;
            Long ndv = null;
            while (parser.currentToken() != null && parser.currentToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    parser.nextToken();
                    switch (field) {
                        case "min" -> min = parser.getValueAsString();
                        case "max" -> max = parser.getValueAsString();
                        case "ndv" -> {
                            if (parser.currentToken() != JsonToken.VALUE_NULL) {
                                ndv = parser.getLongValue();
                            }
                        }
                        default -> parser.skipChildren();
                    }
                }
                parser.nextToken();
            }
            if (min == null || max == null) {
                throw new IOException("statistics require min and max");
            }
            return new PartitionStatistics(min, max, ndv);
        }
    }
}

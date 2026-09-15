package dk.itu.datasys;

/**
 * Identifies the scalar value types supported by table columns and SQL literals.
 *
 * @author Team 1
 * @version 0.3
 * @since 0.2
 */
public enum ColumnType {
    /** An ASCII string value represented by {@link String}. */
    STRING,

    /** A signed 64-bit integer value represented by {@link Long}. */
    LONG,

    /** A double-precision floating-point value represented by {@link Double}. */
    DOUBLE;

    /**
     * Parses a textual field as a value of this type.
     *
     * @param value the text to parse
     * @return the parsed {@link String}, {@link Long}, or {@link Double} value
     * @throws IllegalArgumentException if the text is not valid for this type
     */
    Object parse(String value) {
        return switch (this) {
            case STRING -> { requireAscii(value); yield value; }
            case LONG -> Long.parseLong(value);
            case DOUBLE -> Double.parseDouble(value);
        };
    }

    /**
     * Tests whether a value has the exact Java representation required by this type.
     *
     * @param value the candidate value
     * @return {@code true} when the value is non-null and has the required runtime class
     */
    boolean accepts(Object value) {
        return value != null && value.getClass() == switch (this) {
            case STRING -> String.class;
            case LONG -> Long.class;
            case DOUBLE -> Double.class;
        };
    }

    /**
     * Compares two values represented by this type.
     *
     * @param left the left operand
     * @param right the right operand
     * @return a negative value, zero, or a positive value as {@code left} is less than, equal to,
     *         or greater than {@code right}
     */
    int compare(Object left, Object right) {
        return switch (this) {
            case STRING -> ((String) left).compareTo((String) right);
            case LONG -> Long.compare((Long) left, (Long) right);
            // Signed zeros compare numerically equal while retaining their encoded
            // sign. Double.compare supplies a consistent order for NaN/infinities.
            case DOUBLE -> {
                double a = (Double) left;
                double b = (Double) right;
                yield a == b ? 0 : Double.compare(a, b);
            }
        };
    }

    /**
     * Rejects text containing characters outside the ASCII range.
     *
     * @param value the text to validate
     * @throws IllegalArgumentException if {@code value} contains a non-ASCII character
     */
    static void requireAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) throw new IllegalArgumentException("Non-ASCII value");
        }
    }
}

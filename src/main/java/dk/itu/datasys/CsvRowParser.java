package dk.itu.datasys;

import java.util.List;

/**
 * Parses the headerless, comma-delimited ASCII rows accepted by the storage engine.
 *
 * <p>Fields are positional and unquoted, and trailing empty fields are preserved.
 */
final class CsvRowParser {
    /** Prevents utility-class instantiation. */
    private CsvRowParser() { }

    /**
     * Parses one input line according to an ordered table schema.
     *
     * @param line the raw CSV line
     * @param schema the ordered table schema
     * @param source the source description included in validation errors
     * @param lineNumber the one-based input line number
     * @return the typed field values in schema order
     * @throws IllegalArgumentException if the line is non-ASCII, has the wrong number of fields,
     *                                  or contains a value invalid for its column type
     */
    static Object[] parse(String line, List<ColumnSpec> schema, String source, long lineNumber) {
        try {
            ColumnType.requireAscii(line);
            String[] fields = line.split(",", -1);
            if (fields.length != schema.size()) {
                throw new IllegalArgumentException("Expected " + schema.size() + " fields but found " + fields.length);
            }
            Object[] row = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) row[i] = schema.get(i).type().parse(fields[i]);
            return row;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + " line " + lineNumber + ": " + e.getMessage(), e);
        }
    }
}

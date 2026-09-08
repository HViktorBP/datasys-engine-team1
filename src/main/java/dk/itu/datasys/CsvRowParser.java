package dk.itu.datasys;

import java.util.List;

final class CsvRowParser {
    private CsvRowParser() { }

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

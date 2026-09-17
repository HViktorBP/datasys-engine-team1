package dk.itu.datasys;

import java.io.PrintStream;

/**
 * Writes headerless CSV rows for {@code SELECT} results.
 *
 * @author Team 1
 * @version 0.4
 * @since 0.4
 */
final class ResultCsv {
    /** Prevents utility-class instantiation. */
    private ResultCsv() { }

    /**
     * Writes one row as a single CSV line terminated by {@code \n}.
     *
     * @param out the destination stream
     * @param row the field values in schema order
     */
    static void writeRow(PrintStream out, Object[] row) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(field(row[i]));
        }
        out.print(line);
        out.print('\n');
    }

    /**
     * Formats one CSV field.
     *
     * @param value the field value
     * @return the encoded field text
     */
    private static String field(Object value) {
        if (value instanceof String text) {
            return quote(text);
        }
        return String.valueOf(value);
    }

    /**
     * Quotes a string when it contains a comma, quote, or newline.
     *
     * @param text the string field
     * @return the possibly quoted field
     */
    private static String quote(String text) {
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!quote) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}

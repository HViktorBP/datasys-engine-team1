package dk.itu.datasys;

/**
 * Reports a lexical, syntactic, or literal-conversion error at a SQL source location.
 *
 * @author Team 1
 * @version 0.3
 * @since 0.3
 */
public final class SqlParseException extends RuntimeException {
    /** One-based source line containing the error. */
    private final int line;
    /** Zero-based source column containing the error. */
    private final int column;

    /**
     * Creates a parse exception at a zero-based column within a one-based line.
     *
     * @param message the error description
     * @param line the one-based source line
     * @param column the zero-based source column
     * @param cause the underlying failure, or {@code null} when none exists
     */
    SqlParseException(String message, int line, int column, Throwable cause) {
        super(message, cause);
        this.line = line;
        this.column = column;
    }

    /**
     * Returns the one-based source line containing the error.
     *
     * @return the source line number
     */
    public int line() {
        return line;
    }

    /**
     * Returns the zero-based source column containing the error.
     *
     * @return the source column number
     */
    public int column() {
        return column;
    }
}

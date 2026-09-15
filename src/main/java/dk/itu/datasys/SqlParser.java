package dk.itu.datasys;

import dk.itu.datasys.sql.SqlLexer;
import java.util.List;
import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the supported SQL subset into immutable statement objects.
 *
 * <p>The grammar accepts {@code CREATE TABLE}, {@code COPY ... FROM}, and {@code SELECT *} with
 * one optional {@code WHERE} comparison using {@code =}, {@code <}, or {@code >}. Keywords are
 * case-insensitive, identifier spelling is preserved, and string literals are single-quoted ASCII
 * without escapes.
 *
 * @author Team 1
 * @version 0.3
 * @see Statement
 * @since 0.3
 */
public final class SqlParser {
    /** Diagnostic logger for parse outcomes and timing. */
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);
    /** Shared stateless listener that turns ANTLR errors into exceptions. */
    private static final ANTLRErrorListener ERRORS = new ThrowingErrorListener();

    /** Creates a parser for the engine's SQL grammar. */
    public SqlParser() { }

    /**
     * Parses a non-empty script of semicolon-terminated statements.
     *
     * <p>Whitespace and {@code --} line comments are ignored. The first lexical, syntactic, or
     * numeric-conversion error aborts parsing and reports a one-based line and zero-based column.
     *
     * @param sqlText the SQL script to parse
     * @return the parsed statements in source order
     * @throws SqlParseException if the input is {@code null} or contains invalid SQL
     */
    public List<Statement> parse(String sqlText) {
        long start = System.nanoTime();
        try {
            if (sqlText == null) {
                throw new SqlParseException("SQL text is required", 1, 0, null);
            }
            var lexer = new SqlLexer(CharStreams.fromString(sqlText));
            lexer.removeErrorListeners();
            lexer.addErrorListener(ERRORS);

            var generated = new dk.itu.datasys.sql.SqlParser(
                    new CommonTokenStream(lexer));
            generated.removeErrorListeners();
            generated.addErrorListener(ERRORS);

            var statements = new SqlAstBuilder().build(generated.script());
            LOGGER.debug("statements={} durationMs={}", statements.size(), elapsed(start));
            return statements;
        } catch (SqlParseException error) {
            LOGGER.error("failed line={} col={} durationMs={}",
                    error.line(), error.column(), elapsed(start));
            throw error;
        }
    }

    /**
     * Computes elapsed whole milliseconds from a monotonic start time.
     *
     * @param start the starting value from {@link System#nanoTime()}
     * @return the elapsed time in milliseconds
     */
    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Converts ANTLR error callbacks into source-located {@link SqlParseException}s. */
    private static final class ThrowingErrorListener extends BaseErrorListener {
        /** Creates an error listener. */
        private ThrowingErrorListener() { }

        /** {@inheritDoc} */
        @Override public void syntaxError(Recognizer<?, ?> recognizer,
                                          Object offendingSymbol,
                                          int line,
                                          int charPositionInLine,
                                          String message,
                                          RecognitionException cause) {
            throw new SqlParseException(
                    "line " + line + ":" + charPositionInLine + " " + message,
                    line, charPositionInLine, cause);
        }
    }
}

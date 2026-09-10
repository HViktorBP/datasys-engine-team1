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

public final class SqlParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqlParser.class);
    private static final ANTLRErrorListener ERRORS = new ThrowingErrorListener();

    /** Parses a whole script of semicolon-terminated statements. */
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

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
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

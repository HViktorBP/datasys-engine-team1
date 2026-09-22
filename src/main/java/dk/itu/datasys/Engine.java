package dk.itu.datasys;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Command-line SQL front door for the Team 1 query engine.
 *
 * @author Team 1
 * @version 0.4
 * @since 0.1
 */
public final class Engine {
    /** Diagnostic logger for engine start and stop. */
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    /** Creates the command-line entry point. */
    public Engine() { }

    /**
     * Runs the front door against {@code data/} using the process streams.
     *
     * @param args no arguments for usage, one SQL statement, or {@code -f} and a script path
     */
    public static void main(String[] args) {
        run(args, Path.of("data"), System.out, System.err);
    }

    /**
     * Runs the front door against an explicit data directory and streams.
     *
     * @param args the command-line arguments
     * @param dataDirectory the storage root
     * @param out the stream for usage text or {@code SELECT} CSV
     * @param err the stream for usage errors and execution failures
     */
    static void run(String[] args, Path dataDirectory, PrintStream out, PrintStream err) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("operation=start");
        try {
            if (args == null || args.length == 0) {
                out.print(usage());
                return;
            }
            String sql;
            try {
                sql = script(args);
            } catch (IllegalArgumentException error) {
                err.println(error.getMessage());
                return;
            }
            if (sql == null) {
                err.print(usage());
                return;
            }
            var statements = parseScript(sql, err);
            if (statements == null) {
                return;
            }
            try {
                new Executor(new StorageEngine(dataDirectory)).execute(statements, out);
            } catch (RuntimeException error) {
                err.println(error.getMessage());
            } finally {
                MDC.put("statementNumber", "0");
            }
        } finally {
            LOGGER.debug("operation=stop");
            MDC.remove("sessionId");
            MDC.remove("statementNumber");
        }
    }

    /**
     * Returns the team label used by usage text and tests.
     *
     * @return the team label
     */
    String teamName() {
        return "Team 1";
    }

    /**
     * Resolves command-line arguments to a SQL script, or {@code null} when they are invalid.
     *
     * @param args the command-line arguments
     * @return the SQL script text, or {@code null} when usage should be printed to stderr
     * @throws IllegalArgumentException if {@code -f} names a file that cannot be read
     */
    private static String script(String[] args) {
        if (args.length == 1) {
            String sql = args[0].trim();
            return sql.endsWith(";") ? sql : sql + ";";
        }
        if (args.length == 2 && "-f".equals(args[0])) {
            return readScript(args[1]);
        }
        return null;
    }

    /**
     * Reads a UTF-8 SQL file.
     *
     * @param path the file path
     * @return the file contents
     * @throws IllegalArgumentException if the file cannot be read
     */
    private static String readScript(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read SQL file " + path, e);
        }
    }

    /**
     * Parses a script, printing parse failures to {@code err}.
     *
     * @param sql the script text
     * @param err the error stream
     * @return the statements, or {@code null} when parsing failed
     */
    private static java.util.List<Statement> parseScript(String sql, PrintStream err) {
        try {
            return new SqlParser().parse(sql);
        } catch (SqlParseException error) {
            err.println(error.getMessage());
            return null;
        }
    }

    /**
     * Builds the no-argument usage text, including the team name.
     *
     * @return usage text
     */
    private static String usage() {
        return """
                Team 1

                Usage:
                  mvn -q compile exec:java
                      Print this help.

                  mvn -q compile exec:java -Dexec.args="'SELECT * FROM trips'"
                      Execute one SQL statement. Maven splits -Dexec.args on spaces,
                      so the statement needs a second layer of quotes.

                  mvn -q compile exec:java -Dexec.args="-f script.sql"
                      Execute every statement in a UTF-8 SQL script.

                The data directory is data/ under the working directory.
                SELECT rows are printed as headerless CSV on stdout.
                Logs and errors go to stderr.
                """;
    }
}

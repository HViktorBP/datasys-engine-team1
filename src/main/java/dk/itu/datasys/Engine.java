package dk.itu.datasys;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Command-line SQL front door for the Team 1 query engine.
 *
 * @author Team 1
 * @version 0.11
 * @since 0.1
 */
public final class Engine {
    /** Diagnostic logger for engine start and stop. */
    private static final Logger LOGGER = LoggerFactory.getLogger(Engine.class);

    /** Prevents instantiation of the command-line entry point. */
    private Engine() { }

    /**
     * Runs the front door against {@code data/} using the process streams.
     *
     * <p>Failures print a diagnostic to stderr and exit with status 1. No arguments print help
     * and return normally. A single positional SQL argument remains supported.
     *
     * @param args no arguments for usage, {@code -c} and SQL, or {@code -f} and a script path
     * @since 0.1
     * @version 0.5
     */
    public static void main(String[] args) {
        if (run(args, Path.of("data"), System.out, System.err) != 0) {
            System.exit(1);
        }
    }

    /**
     * Runs the front door against an explicit data directory and streams.
     *
     * @param args the command-line arguments
     * @param dataDirectory the storage root
     * @param out the stream for usage text or {@code SELECT} CSV
     * @param err the stream for usage errors and execution failures
     * @return 0 for help or successful execution, or 1 for an argument, parse, or runtime failure
     * @since 0.4
     * @version 0.11
     */
    static int run(String[] args, Path dataDirectory, PrintStream out, PrintStream err) {
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        LOGGER.debug("operation=start");
        try {
            if (args == null || args.length == 0) {
                out.print(usage());
                return 0;
            }
            Invocation invocation;
            try {
                invocation = invocation(args);
            } catch (IllegalArgumentException error) {
                LOGGER.error("operation=arguments error={}", StorageEngine.clean(error.getMessage()));
                err.println(error.getMessage());
                return 1;
            }
            if (invocation == null) {
                LOGGER.error("operation=arguments error=Invalid command-line arguments");
                err.print(usage());
                return 1;
            }
            var statements = parseScript(invocation.sql(), err);
            if (statements == null) {
                return 1;
            }
            try {
                new Executor(new StorageEngine(dataDirectory), invocation.mode())
                        .execute(statements, out);
            } catch (RuntimeException error) {
                LOGGER.error("operation=execute errorType={} error={}",
                        error.getClass().getSimpleName(), StorageEngine.clean(error.getMessage()));
                err.println(error.getMessage());
                return 1;
            } finally {
                MDC.put("statementNumber", "0");
            }
            return 0;
        } finally {
            LOGGER.debug("operation=stop");
            MDC.remove("sessionId");
            MDC.remove("statementNumber");
        }
    }

    /**
     * Parsed front-door arguments.
     *
     * @param mode the join-order mode
     * @param sql the SQL script to run
     */
    private record Invocation(JoinMode mode, String sql) { }

    /**
     * Resolves command-line arguments, or {@code null} when they are invalid.
     *
     * @param args the command-line arguments
     * @return the join mode and SQL script, or {@code null} when usage should be printed to stderr
     * @throws IllegalArgumentException if {@code -f} names a file that cannot be read, or {@code -j}
     *                                  is not {@code optimized} or {@code syntax}
     * @since 0.4
     * @version 0.11
     */
    private static Invocation invocation(String[] args) {
        JoinMode mode = JoinMode.OPTIMIZED;
        String sql = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-j".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Join mode must be optimized or syntax");
                }
                mode = JoinMode.parse(args[++i]);
            } else if ("-c".equals(arg)) {
                if (sql != null || i + 1 >= args.length) {
                    return null;
                }
                sql = args[++i].trim();
                if (!sql.endsWith(";")) {
                    sql += ";";
                }
            } else if ("-f".equals(arg)) {
                if (sql != null || i + 1 >= args.length) {
                    return null;
                }
                sql = readScript(args[++i]);
            } else if (!arg.startsWith("-") && sql == null) {
                sql = arg.trim();
                if (!sql.endsWith(";")) {
                    sql += ";";
                }
            } else {
                return null;
            }
        }
        if (sql == null) {
            return null;
        }
        return new Invocation(mode, sql);
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
    private static List<Statement> parseScript(String sql, PrintStream err) {
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
                  ./engine
                      Print this help.

                  ./engine -c "SELECT * FROM trips WHERE city = 'Odense'"
                      Execute one SQL statement.

                  ./engine -f script.sql
                      Execute every statement in a UTF-8 SQL script.

                  ./engine -j syntax -c "SELECT * FROM orders, customers WHERE o_custname = c_name"
                      Keep FROM-list join order (week 10). -j optimized is the default.

                The data directory is data/ under the working directory.
                SELECT rows are printed as headerless CSV on stdout.
                Logs and errors go to stderr.
                The CSV log is logs/engine.log under the working directory.
                Set ENGINE_JAVA_OPTS to pass JVM options such as -Xmx64m.
                """;
    }
}

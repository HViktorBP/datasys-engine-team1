import dk.itu.datasys.CopyStatement;
import dk.itu.datasys.Executor;
import dk.itu.datasys.SqlParser;
import dk.itu.datasys.Statement;
import dk.itu.datasys.StorageEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes one experiment workload using the engine's existing statement pipeline.
 *
 * <p>The Python runner starts a new JVM for every invocation and redirects stdout to the
 * operating system's null device. CSV formatting and writing remain inside the Executor's
 * statement timing. Storage construction and SQL parsing happen before that timing.
 *
 * @author Team 1
 * @version 0.6
 * @since 0.6
 * @see Executor
 * @see StorageEngine
 */
public final class ExperimentWorkload {
    /** Logger for the workload's session and process boundaries. */
    private static final Logger LOGGER = LoggerFactory.getLogger(ExperimentWorkload.class);

    /** Prevents construction of this command-line launcher. */
    private ExperimentWorkload() { }

    /**
     * Imports the generated trip schema or executes its distance-filtered query once.
     *
     * <p>Arguments are {@code import partitionRows databaseDirectory csvFile} or
     * {@code select partitionRows databaseDirectory}. The copy statement is constructed with
     * the supplied path directly because the SQL grammar cannot escape apostrophes in paths.
     * Both workloads execute through the same binder, planner, and Executor used by the CLI.
     * Execution failures propagate and cause a nonzero process exit.
     *
     * @param args the workload, positive partition size, database directory, and optional CSV path
     * @throws IllegalArgumentException if the arguments or engine inputs are invalid
     * @throws java.io.UncheckedIOException if storage initialization or execution fails
     * @since 0.6
     * @version 0.6
     */
    public static void main(String[] args) {
        if (args == null || args.length < 3
                || !(args[0].equals("import") && args.length == 4
                     || args[0].equals("select") && args.length == 3)) {
            throw new IllegalArgumentException(
                    "Use import partitionRows databaseDirectory csvFile or select partitionRows databaseDirectory");
        }
        int partitionRows = Integer.parseInt(args[1]);
        MDC.put("sessionId", UUID.randomUUID().toString());
        MDC.put("statementNumber", "0");
        try {
            LOGGER.debug("operation=start workload={} processId={}", args[0], ProcessHandle.current().pid());
            var storage = new StorageEngine(Path.of(args[2]), partitionRows);
            var parser = new SqlParser();
            List<Statement> statements;
            if (args[0].equals("import")) {
                statements = new ArrayList<>(parser.parse(
                        "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);"));
                statements.add(new CopyStatement("trips", args[3]));
            } else {
                statements = parser.parse("SELECT * FROM trips WHERE distance > 900;");
            }
            new Executor(storage).execute(statements, System.out);
        } finally {
            MDC.put("statementNumber", "0");
            LOGGER.debug("operation=stop");
            MDC.remove("sessionId");
            MDC.remove("statementNumber");
        }
    }
}

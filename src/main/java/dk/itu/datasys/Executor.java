package dk.itu.datasys;

import dk.itu.datasys.CatalogStore.Table;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Binds, plans, and executes statements against a {@link StorageEngine}.
 *
 * <p>{@code CREATE TABLE}, {@code COPY} and {@code DELETE} call storage directly.
 * Each table-dependent statement captures immutable metadata before binding. {@code SELECT}
 * drains a planned Volcano pipeline and writes headerless CSV rows. A changing statement's
 * summary reports the version it actually committed, which is later than its base's next version
 * when the statement was rebased onto a concurrent commit.
 *
 * @author Team 1
 * @version 0.8
 * @since 0.4
 */
public final class Executor {
    /** Diagnostic logger for per-statement summaries. */
    private static final Logger LOGGER = LoggerFactory.getLogger(Executor.class);
    /** Storage engine that receives DDL, COPY, and planned scans. */
    private final StorageEngine engine;
    /** Catalog-backed binder applied before execution. */
    private final Binder binder;
    /** Planner used for {@code SELECT} statements. */
    private final Planner planner;

    /**
     * Creates an executor over the supplied storage engine.
     *
     * @param engine the storage engine
     * @throws IllegalArgumentException if {@code engine} is {@code null}
     * @since 0.4
     * @version 0.7
     */
    public Executor(StorageEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        this.engine = engine;
        this.binder = new Binder(engine);
        this.planner = new Planner(engine);
    }

    /**
     * Executes statements in order, stopping at the first error.
     *
     * <p>The first statement is number 1 in the MDC. The caller restores {@code statementNumber}
     * to {@code 0} after the script.
     *
     * @param statements the bound-ready statements in source order
     * @param rowsOut the stream that receives {@code SELECT} CSV rows
     * @throws IllegalArgumentException if {@code statements} or {@code rowsOut} is {@code null}, or
     *                                  a statement fails catalog or API validation
     * @throws UncheckedIOException if storage I/O fails
     * @throws ArithmeticException if a changing statement would overflow its table version
     * @throws ConflictException if a changing statement loses a write conflict to a concurrent
     *                           commit; the table is unchanged and the statement can be rerun
     * @since 0.4
     * @version 0.8
     */
    public void execute(List<Statement> statements, PrintStream rowsOut) {
        if (statements == null) {
            throw new IllegalArgumentException("statements are required");
        }
        if (rowsOut == null) {
            throw new IllegalArgumentException("rowsOut is required");
        }
        int number = 0;
        for (Statement statement : statements) {
            number++;
            MDC.put("statementNumber", String.valueOf(number));
            long start = System.nanoTime();
            var summary = execute(statement, rowsOut);
            LOGGER.debug("statement={} table={} {} version={} durationMs={}",
                    summary.kind(), StorageEngine.clean(summary.table()), summary.counts(), summary.version(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    /**
     * Describes a successful statement for the shared diagnostic log.
     *
     * @param kind the statement kind used in logs
     * @param table the unsanitized table name
     * @param version the committed or read table version
     * @param counts the statement-specific counters as ASCII {@code key=value} pairs
     * @since 0.7
     * @version 0.7
     */
    private record Summary(String kind, String table, long version, String counts) { }

    /**
     * Captures metadata, binds and executes one statement, and returns its summary.
     *
     * @param statement the statement to bind and execute
     * @param rowsOut the stream that receives {@code SELECT} CSV rows
     * @return counters and the resulting version after successful execution
     * @throws IllegalArgumentException if catalog or API validation fails
     * @throws UncheckedIOException if storage I/O fails
     * @throws ArithmeticException if a changing statement would overflow its table version
     * @throws ConflictException if a changing statement loses a write conflict
     * @since 0.7
     * @version 0.8
     */
    private Summary execute(Statement statement, PrintStream rowsOut) {
        var snapshot = switch (statement) {
            case CreateTableStatement ignored -> null;
            case CopyStatement copy -> engine.requireTable(copy.tableName());
            case SelectStatement select -> engine.requireTable(select.tableName());
            case DeleteStatement delete -> engine.requireTable(delete.tableName());
        };
        binder.bind(statement, snapshot);
        return switch (statement) {
            case CreateTableStatement create -> {
                engine.createTable(create.tableName(), create.columns());
                yield new Summary("CREATE_TABLE", create.tableName(), 0, "rowsOut=0");
            }
            case CopyStatement copy -> {
                var result = engine.copyFile(snapshot, copy.csvFilePath());
                yield new Summary("COPY", copy.tableName(), result.version(),
                        "rows=" + result.rows() + " partitions=" + result.partitions());
            }
            case SelectStatement select -> {
                int rows = executeSelect(select, snapshot, rowsOut);
                yield new Summary("SELECT", select.tableName(), snapshot.current().tableVersion(),
                        "rowsOut=" + rows);
            }
            case DeleteStatement delete -> {
                var result = engine.delete(snapshot, delete.where());
                yield new Summary("DELETE", delete.tableName(), result.version(),
                        "rowsDeleted=" + result.rowsDeleted()
                                + " partitionsRewritten=" + result.partitionsRewritten()
                                + " partitionsCarried=" + result.partitionsCarried()
                                + " partitionsRemoved=" + result.partitionsRemoved());
            }
        };
    }

    /**
     * Plans and drains a select pipeline, logging partition counts and writing rows as CSV.
     *
     * @param statement the select statement
     * @param snapshot the table metadata captured before binding
     * @param rowsOut the CSV destination
     * @return the number of emitted rows
     */
    private int executeSelect(SelectStatement statement, Table snapshot, PrintStream rowsOut) {
        var plan = planner.plan(statement, snapshot);
        LOGGER.debug("table={} partitionsTotal={} partitionsRead={} partitionsPruned={}",
                StorageEngine.clean(statement.tableName()), plan.stats().partitionsTotal(),
                plan.stats().partitionsRead(), plan.stats().partitionsPruned());
        Operator root = plan.root();
        try {
            root.open();
            int rows = 0;
            Object[] row;
            while ((row = root.next()) != null) {
                ResultCsv.writeRow(rowsOut, row);
                rows++;
            }
            return rows;
        } finally {
            root.close();
        }
    }
}

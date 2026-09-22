package dk.itu.datasys;

import java.io.PrintStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Binds, plans, and executes statements against a {@link StorageEngine}.
 *
 * <p>{@code CREATE TABLE} and {@code COPY} call storage directly. {@code SELECT} drains a planned
 * Volcano pipeline and writes headerless CSV rows.
 *
 * @author Team 1
 * @version 0.4
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
     * @throws java.io.UncheckedIOException if storage I/O fails
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
            int rows = 0;
            String kind;
            String table;
            binder.bind(statement);
            switch (statement) {
                case CreateTableStatement create -> {
                    kind = "CREATE_TABLE";
                    table = create.tableName();
                    engine.createTable(create.tableName(), create.columns());
                }
                case CopyStatement copy -> {
                    kind = "COPY";
                    table = copy.tableName();
                    engine.copyFile(copy.tableName(), copy.csvFilePath());
                }
                case SelectStatement select -> {
                    kind = "SELECT";
                    table = select.tableName();
                    rows = executeSelect(select, rowsOut);
                }
            }
            LOGGER.debug("statement={} table={} rowsOut={} durationMs={}",
                    kind, StorageEngine.clean(table), rows, (System.nanoTime() - start) / 1_000_000);
        }
    }

    /**
     * Plans and drains a select pipeline, writing matching rows as CSV.
     *
     * @param statement the select statement
     * @param rowsOut the CSV destination
     * @return the number of emitted rows
     */
    private int executeSelect(SelectStatement statement, PrintStream rowsOut) {
        var plan = planner.plan(statement);
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

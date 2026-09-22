# Exercise 4 Volcano Pipeline and SQL Front Door

**Date:** 2026-09-17
**Status:** Draft, pending review
**Spec:** `Exercises/Exercise4.md`
**Prior plan style:** `docs/superpowers/plans/2026-09-10-exercise-3-sql-front-end.md`
**Week 3 feedback:** [docs/feedback/exercise-3.md](../../feedback/exercise-3.md) — keep the existing tests when adding the Week 4 executor; they are the regression baseline.

## Goal

Execute the bound Exercise 3 ASTs. `SELECT` runs on a Volcano pipeline (`Scan` then optional `Filter`). Partition pruning moves to a planner that decides `READ` or `PRUNED` before any data file opens. `Engine.main` becomes a SQL front door: it runs one statement or a `.sql` script and prints `SELECT` rows as headerless CSV on stdout.

## Non-Goals

- Column lists, grammar changes, and `ProjectOperator` (Exercise 4 optional extension). These can be appended later.
- GitHub Actions, pull-request review or merge, and the `v0.4` release tag.
- New SQL (joins, compounds, `<=` / `>=` / `<>`, nulls, aliases, aggregation, `ORDER BY`).
- Changing `StorageEngine.select`'s public signature.
- Weakening, deleting, or rewriting the Week 3 parser, AST, printer, binder, or storage tests.

## Constraints

- Java 25, Maven, JUnit 6.1.2, SLF4J 2.0.18 with Log4j2, ANTLR 4.13.2.
- All new production types live in `dk.itu.datasys`. Do not add an `exec` package.
- `PartitionPruner` stays package-private. `Comparison.matches` stays package-private; `FilterOperator` uses it from the same package.
- Reuse `ColumnSpec`, `ColumnType`, `Comparison`, `Predicate`, `SelectStatement`, `ScanStats`, and `CatalogStore.Partition`. Do not duplicate them.
- `StorageEngineIT` must remain byte-for-byte unchanged and green.
- `SqlAstTest`, `SqlParserTest`, `SqlPrinterTest`, `BinderIT`, and `StorageHelpersTest` stay as-is, including the extra Week 3 cases (round trips, positioned syntax errors, reserved names, invalid printable values, binder boundaries, signed-zero, ASCII, integer overflow).
- `EngineTest.teamName` stays. The no-argument pretty-print demo test is the only existing test that must change, because `Engine.main` with no args becomes usage rather than SQL printing.
- TDD for behavioral work. Javadoc is part of the implementation (`@since 0.4` / `@version 0.4` on new public and protected APIs).
- `data/` remains the default storage root and stays gitignored.

## Architecture

The runtime for a script is `parse → bind → plan → execute`, statement by statement, stopping at the first error.

```
SQL text
  -> SqlParser.parse
  -> Binder.bind (per statement)
  -> CREATE TABLE / COPY: StorageEngine.createTable / copyFile
  -> SELECT: Planner.plan -> Operator.open/next/close
  -> SELECT rows: headerless CSV on stdout
```

`CREATE TABLE` and `COPY` have no operator tree. Only `SELECT` builds a pipeline.

`StorageEngine.select(table, column, comparison, constant)` keeps its week-2 signature. Internally it builds the equivalent `SelectStatement` with a `WHERE`, plans `Filter(Scan(survivingPartitions))`, records the planner's `ScanStats` on `getLastScanStats()`, and drains the pipeline. Existing storage integration tests therefore keep calling the same Java API.

The refactor is done when those tests still pass and `decision=READ|PRUNED` log lines are emitted by `Planner` before any data file is opened. `ScanOperator` never sees a predicate and never decides whether to read a partition.

## Components

### `Operator`

```java
public interface Operator {
    void open();
    Object[] next(); // one row in schema column order, or null when exhausted
    void close();
}
```

Callers use `open`, then `next` until `null`, then `close`. `close` is idempotent enough to call after a failure in `open`/`next` (best-effort resource release). `next` without `open` is not supported.

### `ScanOperator`

Package-visible constructor:

```java
ScanOperator(Path dataFile, List<ColumnSpec> columns, List<CatalogStore.Partition> partitions)
```

- Yields every row of the handed partitions, in partition order and in-file row order, fields in schema order.
- Does not receive a predicate and does not consult min/max.
- Empty `partitions`: `open` does not open a file; the first `next` returns `null`. This is the fully pruned case, including a table with no published data file.
- Non-empty `partitions` require a non-null `dataFile`. Part 1 still has at most one data file per table; the planner concatenates that file's partitions in catalog order.
- Reads with `BinaryColumnCodec.checkHeader` / `readChunk` the same way `StorageEngine.select` does today.
- Does not write `ScanStats` or `decision=` lines.

### `FilterOperator`

Package-visible constructor:

```java
FilterOperator(Operator child, int columnIndex, ColumnType type, Comparison comparison, Object constant)
```

- Pulls rows from `child` and emits those for which `comparison.matches(type.compare(row[columnIndex], constant))` is true.
- Semantics identical to week 2, including signed-zero numeric equality already implemented in `ColumnType.compare`.
- On `close()`, after closing the child, logs `rowsIn` (rows pulled) and `rowsOut` (rows emitted) at debug, structured as `rowsIn={} rowsOut={}`.
- Does not prune partitions.

### `TestListOperator`

Test-only helper in `src/test/java`. Serves an immutable list of `Object[]` rows. Used to unit-test `FilterOperator` without storage.

### `Planner`

```java
public final class Planner {
    public Planner(StorageEngine engine) { }
    public QueryPlan plan(SelectStatement statement) { }
}

public record QueryPlan(Operator root, ScanStats stats) { }
```

`Planner` rejects a null engine. `plan` looks up the table through package-visible storage accessors (below). It never opens a data file.

For `SELECT` with `WHERE`:

1. Resolve the predicate column index and type from `engine.schema` (unknown table/column already failed in `Binder` for the SQL path; the Java `select` path still validates as today).
2. Walk every catalog partition in order. For each, call `PartitionPruner.canPrune(type, statistics, comparison, constant)`.
3. Log the existing decision line **before** any later operator can open a file:

   `table={} column={} comparison={} const={} partition={} min={} max={} decision={}`

   `partition` is the zero-based catalog index (same as today's `StorageEngine` loop). `decision` is `PRUNED` or `READ`. Values go through the same comma/newline sanitizer used by storage logs so week-5 log ingestion still parses.
4. Keep partitions that cannot be proven empty.
5. `stats = ScanStats(partitionsTotal, partitionsRead, partitionsPruned)` where `partitionsTotal` is the catalog partition count, `partitionsRead` is the surviving count, and `partitionsPruned` is the difference. A table with no files yields `ScanStats(0, 0, 0)`.
6. Return `Filter(Scan(dataFile, columns, surviving), columnIndex, type, comparison, constant)`.

For `SELECT` without `WHERE`:

- Every partition survives. No `decision=` lines (there is no predicate). `stats = ScanStats(n, n, 0)` for `n` catalog partitions.
- Return a bare `Scan(dataFile, columns, allPartitions)`.

`StorageEngine.select` always has a predicate, so it always receives a `Filter` over `Scan`.

### Storage access for planning and scanning

`StorageEngine` adds package-visible methods so `Planner` and `ScanOperator` do not reach into `CatalogStore` themselves:

```java
synchronized CatalogStore.Table requireTable(String tableName)
synchronized Path publishedDataFile(String tableName) // null when the table has no files
```

`requireTable` is the existing private `table(...)` lookup and still throws `IllegalArgumentException` for an unknown table. `publishedDataFile` resolves `catalogs.dataPath(file.path())` for the table's single published file, or returns `null` when `files` is empty. I/O from path resolution is thrown as `UncheckedIOException`.

`StorageEngine.select` stops pruning and filtering inline. It plans, drains the operator, and only then assigns `lastScanStats` from `QueryPlan.stats()`. A drain failure (for example a corrupt data file) must leave the previous stats in place, which is what `StorageEngineIT.rejectsCorruptDataAndKeepsLastSuccessfulStats` asserts. The existing `operation=select ...` debug summary stays on `StorageEngine`.

### `Executor`

```java
public final class Executor {
    public Executor(StorageEngine engine) { }
    public void execute(List<Statement> statements, PrintStream rowsOut) { }
}
```

The constructor rejects a null engine. `execute` binds, plans, and runs statements in order, writes `SELECT` rows as CSV, and increments `statementNumber` from 1 for each statement.

Per statement:

1. Increment a counter starting at 1 and `MDC.put("statementNumber", String.valueOf(n))`.
2. `binder.bind(statement)`.
3. Switch:
   - `CreateTableStatement` → `engine.createTable(name, columns)`
   - `CopyStatement` → `engine.copyFile(name, csvFilePath)` (path is cwd-relative, unchanged)
   - `SelectStatement` → `plan`, `open`, drain `next` into CSV on `rowsOut`, `close`
4. Log a debug summary: `statement={CREATE_TABLE|COPY|SELECT} table={} rowsOut={} durationMs={}` (`rowsOut` is `0` for non-select).
5. On any exception: close an open operator if any, leave MDC at the failing statement number for that error log, then propagate. The caller restores `statementNumber` to `0` after the script.

`execute` does not parse. Parsing stays in `Engine.main` / `SqlParser` so script-level parse failures keep `statementNumber=0`.

### Result CSV

Package-visible `ResultCsv` writes one row per line to a `PrintStream`:

- Headerless. No extra blank lines. Unix `\n` newlines.
- Fields separated by `,`.
- `STRING`: raw ASCII text when the value contains no comma, quote, or newline; otherwise RFC 4180 quoting (`"` around the field, `"` doubled inside). Golden city names are unquoted.
- `LONG`: `Long.toString`.
- `DOUBLE`: `Double.toString` (plain decimal for the golden values; preserves `-0.0`).

This is the DuckDB `duckdb -csv -noheader` oracle for the supported types and the golden `trips` data. The front-door test commits expected CSV bytes for that dataset rather than calling DuckDB from CI.

`SqlPrinter` is not used for result rows.

### `Engine.main`

`main(String[] args)` delegates to a package-visible

```java
static void run(String[] args, Path dataDirectory, PrintStream out, PrintStream err)
```

so tests can pass a `@TempDir` without changing the process working directory. `main` calls `run(args, Path.of("data"), System.out, System.err)`.

Startup still puts a random `sessionId` and `statementNumber=0` in the MDC, then logs engine start.

| Args | Behavior |
| --- | --- |
| none | Print team name and usage to stdout. Not an execution mode. Do not open `data/`. |
| one argument | Execution mode. Treat the argument as a SQL script. If the trimmed text does not end with `;`, append `;` so the Exercise 4 Maven examples parse. |
| `-f` and a path | Execution mode. Read the file as UTF-8 and parse it unchanged. |
| anything else | Print usage to stderr and stop without executing. |

Execution mode:

1. Open `new StorageEngine(Path.of("data"))` (default eight rows per partition).
2. Parse. Parse errors are logged and printed to stderr; stdout stays empty; `statementNumber` remains `0`.
3. `new Executor(engine).execute(statements, System.out)`.
4. `MDC.put("statementNumber", "0")`.
5. Log engine stop.

In execution mode, stdout contains only CSV from successful `SELECT`s that already ran. Rows already written cannot be recalled; the required failing-script test therefore fails before any `SELECT` output so stdout is empty. Console logging stays on stderr (existing Log4j2 `SYSTEM_ERR` appender). Error messages from the front door also go to stderr.

`COPY` source paths in SQL remain as written (`'trips.csv'` is cwd-relative). Table data still lives under `data/`.

Usage text includes the team name, the no-arg / one-arg / `-f file.sql` forms, and a note that Maven needs a second quote layer for `-Dexec.args`.

## Error handling

Stop at the first error. Do not run later statements.

| Failure | Type | Front door |
| --- | --- | --- |
| Lexer/parser/literal conversion | `SqlParseException` | Message on stderr; stdout empty; `statementNumber=0` |
| Bind / unknown table or column / bad constant | `IllegalArgumentException` | Message on stderr; no CSV from the failing statement |
| CSV / API / schema | `IllegalArgumentException` | Same |
| Published data I/O | `UncheckedIOException` | Same |
| Missing or unreadable `-f` file | `IllegalArgumentException` whose message contains the path | Message on stderr; stdout empty |

No new exit-code contract. Tests assert stream contents, not `System.exit`.

## Testing

Required new tests (JUnit 6), plus the frozen Week 3 suite:

1. **`FilterOperatorTest`:** `TestListOperator` child; rows that pass, rows that fail, empty child. Comparison semantics match week 2 for at least `=` / `<` / `>` on `LONG`.
2. **`ScanOperatorTest`:** After a real copy (`maxRowsPerPartition = 2`), constructing a scan with a subset of partitions returns exactly those partitions' rows in order. An empty partition list returns no rows and does not require the data file to exist.
3. **`PlannerTest` pruning:** Sorted golden CSV used by `StorageEngineIT.sortedScanPrunesThreePartitions...` (`distance > 200`, `maxRowsPerPartition = 2`) yields `ScanStats(4, 1, 3)` from `QueryPlan.stats()`.
4. **`PlannerTest` shapes:** `WHERE` → root is `FilterOperator` whose child is `ScanOperator`. No `WHERE` → root is `ScanOperator` over all catalog partitions.
5. **`StorageEngineIT`:** file untouched; all methods still green through the new `select` implementation.
6. **`EngineIT`:** invoke `Engine.run` with `-f` and a `.sql` script, a `@TempDir` data directory, and captured streams; stdout equals committed headerless CSV bytes. A second invocation runs a script that fails on bind or parse before any `SELECT`; stderr contains the message; stdout is empty. The `-f` file is allowed to live outside the data directory; `COPY` paths in the script point at test resources.

`EngineTest` no-arg case: stdout contains the team name and usage; it no longer prints the four pretty-printed statements.

Do not remove Week 3 extra tests. They are the lecture-confirmed regression baseline for the executor.

## File map

Create:

- `src/main/java/dk/itu/datasys/Operator.java`
- `src/main/java/dk/itu/datasys/ScanOperator.java`
- `src/main/java/dk/itu/datasys/FilterOperator.java`
- `src/main/java/dk/itu/datasys/QueryPlan.java`
- `src/main/java/dk/itu/datasys/Planner.java`
- `src/main/java/dk/itu/datasys/Executor.java`
- `src/main/java/dk/itu/datasys/ResultCsv.java`
- `src/test/java/dk/itu/datasys/TestListOperator.java`
- `src/test/java/dk/itu/datasys/FilterOperatorTest.java`
- `src/test/java/dk/itu/datasys/ScanOperatorTest.java`
- `src/test/java/dk/itu/datasys/PlannerTest.java`
- `src/test/java/dk/itu/datasys/EngineIT.java`
- `src/test/resources/front-door.sql` and matching expected CSV

Modify:

- `src/main/java/dk/itu/datasys/StorageEngine.java` — package-visible table/data accessors; `select` plans and drains; prune/decision logging removed from this class
- `src/main/java/dk/itu/datasys/Engine.java` — SQL front door
- `src/test/java/dk/itu/datasys/EngineTest.java` — no-arg usage contract
- `src/main/java/dk/itu/datasys/package-info.java` — execution is now in scope
- `README.md` — front-door commands instead of the pretty-print demo

Do not modify:

- `src/test/java/dk/itu/datasys/StorageEngineIT.java`
- `src/test/java/dk/itu/datasys/SqlAstTest.java`
- `src/test/java/dk/itu/datasys/SqlParserTest.java`
- `src/test/java/dk/itu/datasys/SqlPrinterTest.java`
- `src/test/java/dk/itu/datasys/BinderIT.java`
- `src/test/java/dk/itu/datasys/StorageHelpersTest.java`
- `src/main/antlr4/dk/itu/datasys/sql/Sql.g4`

## Implementation plan style

The follow-up plan lives at `docs/superpowers/plans/2026-09-17-exercise-4-volcano-pipeline.md` and matches the Exercise 3 plan: header, global constraints, file map, bite-sized TDD tasks with full code, exact Maven commands, focused Conventional Commits, final verify task, and explicit non-goals. Local definition of done excludes CI, PR review, and tagging `v0.4`.

# Exercise 5 ownership and release checklist

## Reproduce the log analysis

```bash
mvn -B verify
scripts/log-analysis.sh
```

The demo prints a retained temporary evidence directory. It includes the SQL
scripts, stdout CSV, stderr diagnostics, persistent tables, and live log. The
first session fails at statement 7; statement 8 is never executed. A second
session succeeds. `ingest.sql` creates the seven-column log schema and imports
the active log through `COPY`. The imported byte boundary excludes records
appended during that copy. The engine then produces `session.csv`,
`statement-7.csv`, and `errors.csv` from the corresponding SQL queries. Statement
7 is selected across all imported sessions; the UUID distinguishes sessions.

Local validation on 2026-10-04 produced 25 records for the failing session, one
statement-7 record, and one `ERROR` record. The packaged integration test also
verifies all three queries and imports a log containing escaped Unicode diagnostics.

## Trace one filtered SELECT in a debugger

Each team member must personally do this walkthrough. Automated tests or an
assistant's trace cannot satisfy the course's individual ownership requirement.

1. Build the engine and start debugging `dk.itu.datasys.Engine` with arguments
   `-f script.sql`, using an empty working directory. Copy
   `src/test/resources/front-door.sql` and `trips.csv` into that directory and
   update its `COPY` path, or use absolute paths. This prevents conflicts with
   existing tables. Set a breakpoint on `Executor.execute`'s statement loop
   or `binder.bind(statement)`.
2. Stop on the third statement, `SELECT * FROM trips WHERE distance > 100`.
   The parser has already processed the whole script. Inspect the
   `SelectStatement`, its `Optional<Predicate>`, column `distance`, comparison
   `GREATER_THAN`, and the boxed `Long` constant `100L`. To observe text becoming
   an AST, rerun with breakpoints in `SqlParser.parse` and
   `SqlAstBuilder.visitSelect` / `visitPredicate` / `visitLiteral`.
3. Step into `Binder.bind` and `bindSelect`. `StorageEngine.schema("trips")`
   resolves the persisted schema. The binder finds `distance` and checks that
   its constant is a `Long`; it does not open data files.
4. Step through `Planner.plan`: catalog min/max values go to
   `PartitionPruner.canPrune`. The eight-row fixture fits in one partition under
   the 1,000-row default. Its distance range is 12–299, so this predicate keeps it.
   With two-row partitions, the fixture
   ranges are 12–187, 95–140, 31–210, and 88–299, and all four survive because
   each maximum is above 100. The plan is `FilterOperator(ScanOperator(...))`.
5. Step into `Executor.executeSelect`, `FilterOperator.open`, and
   `ScanOperator.open`. The scan opens the binary file and checks its header.
   Each `next` loads the next surviving partition's column chunks, reconstructs
   a row, and lets the filter compare its distance with 100.
6. Follow a passing row into `ResultCsv.writeRow` and stdout. The output rows
   have distances 187, 140, 210, and 299 in that input order. Exhaustion returns
   `null`; the executor's `finally` closes the filter and scan, then records
   the statement summary. Inspect the session UUID and statement number 3 in MDC.
7. Record the team member's name, date, and one observation from each of parsing,
   binding, pruning, iteration, and output in the PR or team notes. No team-member
   signoffs have been supplied by this implementation.

For a terminal debugger, run from your isolated working directory:

```bash
jdb -classpath /absolute/path/to/repo/target/engine.jar dk.itu.datasys.Engine -f script.sql
# At the jdb prompt:
stop in dk.itu.datasys.Executor.execute
run
# Use step, next, locals, print, where, and cont as you follow the path above.
```

An assistant-run `jdb` trace on 2026-10-04 stopped in the parser, AST builder,
executor's statement loop, binder, planner, scan `open`/`next`/`close`, and CSV
writer. It inspected statement number 3 and the `distance` predicate with a
`Long` constant, observed `decision=READ` for range 12–299, and inspected the
first emitted row (`Aarhus`, distance 187). Output distances were 187, 140,
210, and 299; the closing filter logged `rowsIn=8 rowsOut=4` and the executor
logged `statement=SELECT rowsOut=4` for `trips`. Debugger pauses inflate log
durations, so this trace is ownership evidence only and is excluded from the
experiment. Every human team member still needs their own trace and signoff.

## Reachability cleanup

The main path is `Engine → SqlParser/SqlAstBuilder → Executor → Binder →
StorageEngine` for DDL/import, and `Executor → Planner → Filter/Scan → ResultCsv`
for queries. Catalogs, codecs, typed comparisons, CSV parsing, statistics, and
snapshot input are called from that path.

Removed `SqlPrinter` and its tests, `Engine.teamName` and its test, the unused
public `Engine` constructor, `FilterOperator.child` and the test-only inspection,
and `StorageEngine.select`, `drain`, `getLastScanStats`, and their stored state.
The materializing query path had no caller from `main`. Storage regression tests
now use a test-local collector over the production binder/planner pipeline;
obsolete validation and last-successful-statistics assertions were removed.
`Executor` consumes and logs `QueryPlan.stats()` in the active query path.

Earlier Exercise 4 documents preserve the decisions made for that release.
Exercise 5 deliberately supersedes their requirement to retain the storage
selection API and pretty-printer tests. The current storage design and README
describe the implemented contract.

## Review feedback: partition decisions and scan summaries

Queries without `WHERE` now log one `decision=READ reason=noPredicate` record
per catalog partition in the planner, before any data file opens. Empty tables
have no partitions to log. This supersedes the Exercise 4 design's decision to
omit these records. The historical validation counts above predate this change;
rerunning the demo produces additional records for unfiltered queries and scans.

`ScanOperator.close` now logs the file, actual fully decoded partitions, rows
emitted before filtering, and elapsed milliseconds from open through close.
Each open attempt gets one summary on close, including empty, partial, or failed
scans; repeated closes do not duplicate it. Reopening resets the counters.
These `DEBUG` progress records complement the front door's `ERROR` failure
records and the executor's planned partition counts. For a filtered query,
the scan summary precedes the filter and successful statement summaries.

Local validation on 2026-10-08 passed 29 unit tests and 28 integration tests,
plus private-inclusive Javadocs with full doclint and no warnings. The log
analysis demo now imports 30 records for the failing session, including the
unfiltered partition decision and four scan summaries. It still finds exactly
one statement-7 record and one `ERROR` record.

## Release v0.5

Local verification covers unit tests, integration tests, packaged subprocesses,
live-log ingestion, and private-inclusive Javadocs. CI runs `mvn -B verify`,
which also runs the packaged subprocess tests after shading the JAR.
The clean local build on 2026-10-04 passed 23 unit tests and 28 integration
tests. Private Javadocs passed full doclint without warnings. Independent review
identified the Unicode-ingestion bug and a misplaced statistics assertion; both
were corrected, with the Unicode regression observed failing before its fix.

To reproduce the documentation check after building:

```bash
javadoc -private -Xdoclint:all -classpath target/engine.jar \
  -sourcepath src/main/java -d target/site/apidocs dk.itu.datasys
```

The implementation still needs the team's debugger signoffs and a reviewed PR
merged into `main`. Create the tag only on that reviewed, verified commit:

```bash
git checkout main
git pull --ff-only
mvn -B verify
git tag -a v0.5 -m "Exercise 5: packaging, log ingestion, and code cleanup"
git push origin v0.5
```

Before pushing any commits created with AI assistance, verify their attribution
trailers per `AGENTS.md`. If no verified GitHub-linked canonical identity is
available, use `AI-Assisted-by: OpenAI/Codex/GPT-6`; GitHub contributor linking
cannot be guaranteed with that trailer. The release has not been tagged or pushed
by this implementation.

The optional two-million-row low-heap experiment is separate from the planned
week 6 pruning experiment and has not been run. Query results now stream; memory
still grows with all loaded catalog metadata, the planner's partition lists, and
COPY's accumulated metadata. Decoded row values are bounded by partition size.
`OutOfMemoryError` is not a `RuntimeException` and can escape without an `ERROR`
record; the engine makes no recovery guarantee for JVM resource exhaustion.

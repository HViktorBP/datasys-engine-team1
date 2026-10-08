# datasys-engine-team1

How to Build Data Systems – Fall 2026. Team 1 query engine.

Requires JDK 25 or newer and Maven. Build and run the JUnit 6 unit and integration
tests with `mvn -B verify`. Packaging produces the self-contained
`target/engine.jar`, including the logging backend.

```bash
mvn -B package
./engine
./engine -c "SELECT * FROM trips WHERE city = 'Odense'"
./engine -f script.sql > ours.csv
ENGINE_JAVA_OPTS=-Xmx64m ./engine -f script.sql
```

`-c` accepts SQL with or without a final semicolon; `-f` reads a UTF-8 script
whose statements end with semicolons. A single positional SQL argument is also
supported. Scripts stop at the first failure, retain earlier successful
statements and output, and exit with status 1. Successful execution and help
exit with status 0. `SELECT` writes headerless CSV to stdout. Diagnostics go to
stderr and `logs/engine.log`; data and logs are relative to the working directory.
The launcher locates the JAR relative to itself, so it works from other directories.
`ENGINE_JAVA_OPTS` accepts space-separated JVM options.

The seven log columns are timestamp, sessionId, statementNumber, threadId,
logLevel, className, and logMessage. Normal records use `DEBUG`; failures use
`ERROR`. Messages are ASCII without commas, double quotes, or line breaks;
non-ASCII diagnostic characters use hexadecimal escapes. A session
has a UUID; executed statements are numbered from 1. Argument, parsing, startup,
start, and stop records use statement 0. Ordinary execution failures retain the
failing statement's number. Fatal JVM errors are not handled as ordinary failures.

Run `scripts/log-analysis.sh` after packaging for an isolated demonstration:
it generates successful and failing sessions, imports a snapshot of the active
log, and saves the engine's session, statement 7, and error query results. `COPY`
captures the source's byte length when opened and excludes subsequent appends,
including its own log records. It does not protect against in-place edits or
truncation. Logs rotate at 10 MB, so a snapshot includes only the active file.

Storage uses `new StorageEngine(Path.of("data"))`; the optional second constructor
argument sets the maximum partition size (default: 1,000). This setting applies
to future imports; existing data retains its persisted partition boundaries.
The front door executes
`parse → bind → plan → open/next/close → CSV`; it streams query results. Exercise 5
removed the test-only `StorageEngine.select` materializing API, its scan-statistics
getter, and `SqlPrinter`. `QueryPlan.stats()` and planner `decision=` records
describe partition activity.

Use one writer engine per directory; reopen it to reload catalogs written by
another instance. Part 1 supports one successful copy per table. Generated state
belongs under the Git-ignored `data/` directory. See the
[storage design](docs/storage-design.md),
[debugger walkthrough and release checklist](docs/exercise-5.md), and
[experiment design](docs/experiment-design.md).

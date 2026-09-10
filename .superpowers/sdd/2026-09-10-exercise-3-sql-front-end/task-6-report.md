# Task 6 Report: Pretty-Print Round-Trippable SQL

## Implementation
- Added `SqlPrinter` with `print(Statement)` support for `CreateTableStatement`, `CopyStatement`, and `SelectStatement`.
- Printer emits normalized SQL, preserves identifier case from the AST, and terminates every statement with `;`.
- String literals are ASCII-only and reject quotes/newlines/carriage returns instead of emitting SQL outside the restricted grammar.
- Double literals reject `NaN` and infinities. Exponent output from `Double.toString()` is converted to plain decimal with `BigDecimal.valueOf(...).toPlainString()`.

## RED evidence
- Wrote `SqlPrinterTest` before production implementation.
- `mvn -B -Dtest=SqlPrinterTest test` failed as expected because `SqlPrinter` did not exist:
  - `cannot find symbol class SqlPrinter`
  - `cannot find symbol class SqlPrinter`

## GREEN evidence
- `mvn -B -Dtest=SqlParserTest,SqlPrinterTest test` passed.
- Focused result: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`.

## Final evidence
- `mvn -B verify` completed with exit code 0.
- Surefire/failsafe report evidence:
  - `SqlPrinterTest`: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`
  - `SqlParserTest`: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`
  - Failsafe summary: `completed=21`, `errors=0`, `failures=0`, `skipped=0`, `flakes=0`

## Exponent and negative-zero results
- `1.0e20` prints as `100000000000000000000.0` and round-trips through `SqlParser`.
- `1.0e-7` prints as `0.00000010` and round-trips through `SqlParser`.
- `-0.0` prints as `-0.0` and round-trips with the signed-zero `Double` value preserved.

## Files
- Created `src/main/java/dk/itu/datasys/SqlPrinter.java`.
- Created `src/test/java/dk/itu/datasys/SqlPrinterTest.java`.
- Created `.superpowers/sdd/2026-09-10-exercise-3-sql-front-end/task-6-report.md`.

## Commit
- Subject: `feat: pretty-print SQL statements (#4)`
- Trailer: `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

## Self-review
- Verified each supported AST variant is covered by round-trip tests.
- Verified stable normalized examples for create/copy/select output.
- Verified unsupported strings and non-finite doubles fail explicitly.
- Checked implementation is isolated to printer functionality and tests.

## Completion / blockers
- Implementation complete.
- No code/test blockers found.
- Todo update attempted after completion; see final status response for result.

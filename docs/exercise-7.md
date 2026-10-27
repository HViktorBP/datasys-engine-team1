# Exercise 7 release notes

Release target: v0.7. Implements the agreed
[transaction design](transaction-design.md).

- Repeated COPY appends partitions and commits the next table version, including
  empty imports. CREATE commits version 0.
- DELETE accepts the same optional typed WHERE comparison as SELECT. It carries
  pruned and false-positive partitions unchanged, rewrites partial matches,
  removes whole matches, and preserves row order. Unfiltered DELETE counts rows
  from metadata without decoding files. No matches leave history unchanged.
- Statements pin immutable schema and file/partition metadata before binding.
  Scans preserve file identity. Complete each query before starting another
  statement, using one engine per directory.
- Catalog format 2 persists current and previous snapshots. Older formats are
  explicitly unsupported. Binary HTBD format 1 and big-endian encoding remain.
- New partition files have unique UUID names. The atomic catalog move is the
  commit point; unsupported atomic moves fail without a regular-move fallback.
  Startup loads and validates every catalog before cleaning recognized orphan
  files. Cleanup retains the union of both versions across all tables and logs
  failures without undoing commits.
- Successful statement summaries include the committed/read version. DELETE
  summaries include row and rewritten/carried/removed partition counts. DML
  writes no result rows; SELECT keeps headerless CSV output and seven-field logs.

The exercise calls the design deliverable `docs/transactions-design.md`.
This repository intentionally uses the user-selected singular filename
`docs/transaction-design.md`, linked from the README.

## Verification

Run `mvn -B verify` for unit, storage, launcher and JVM-recovery tests. The
transaction tests cover versions 0–3, empty COPY, false positives, selective
pruning, all nine typed comparisons and signed zero, row ordering, immutable
metadata, pinned plans, retained/shared files, publication failure, overflow and
cleanup errors. Recovery tests terminate actual child JVMs immediately before
and after atomic catalog publication, then verify restart visibility and orphan
cleanup. Packaged integration tests run CREATE, two COPYs, DELETE and SELECT
through `./engine -f`, and verify a failed second COPY stops its script while
preserving the first commit.

Generate private-inclusive documentation after packaging:

```sh
javadoc -private -Xdoclint:all -classpath target/engine.jar \
  -d target/apidocs $(rg --files src/main/java -g '*.java')
```

The implementation environment has neither the DuckDB CLI nor its Python
module. The launcher script matches explicit expected CSV rows, but a DuckDB
comparison has **not** been run. To compare when DuckDB is available, use its
VARCHAR/BIGINT types for this engine's STRING/LONG schema and specify headerless
CSV for COPY. The data manipulation statements and predicates are otherwise the
same.

## Supported failure boundary

Statement failures and Java process crashes while the operating system remains
running are covered. OS crashes, sudden power loss, concurrent statements,
multiple engines on one directory, migration, time-travel SQL and restore
commands are deferred. Publication does not force files or directory entries
for power-loss durability. This implementation does not create or push a v0.7
release tag.

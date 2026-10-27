# Exercise 7 Transactions Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task in the existing Exercise 7 branch.

**Goal:** Implement statement auto-commit, repeated COPY, immutable statement snapshots, and copy-on-write DELETE.

**Architecture:** Catalog format 2 stores current and previous immutable snapshots. Each new partition has a unique binary file; the atomic catalog replacement publishes the candidate. Binding, planning and execution share captured metadata, while cleanup retains the union across all catalogs and both versions.

**Tech Stack:** Java 25, ANTLR, Jackson, JUnit 6, Maven.

**Spec:** `docs/transaction-design.md`.

## Global constraints

- Sequential statements and one engine per directory; consume and close queries before the next statement.
- Binary format 1, catalog format 2; reject older catalogs explicitly.
- Exactly current and previous committed states; no-op DELETE retains history, empty COPY increments version.
- Atomic move required; cleanup cannot change a successful commit into failure.
- New API release 0.7; preserve historical introduction versions.

## Review focus

- False-positive min/max ranges must carry unchanged partitions without rewriting.
- Partial staging failures must remove only newly created files, preserving earlier COPY commits.
- Shared files remain live while either retained version references any partition.
- Invalid catalogs stop startup before any orphan cleanup.
- Overflow fails without publishing; temporary catalog cleanup after publication is best effort.

### Task 1: Immutable versioned catalogs and repeated COPY

Files: `CatalogStore.java`, `StorageEngine.java`, new `StorageIo.java`, `TransactionTest.java`, `StorageEngineIT.java`.

Interfaces: `CatalogStore.Snapshot(long tableVersion, List<DataFile> files)`, `Table.current()/previous()/files()`, `StorageEngine.requireTable(String)`; package-private injectable filesystem operations for deterministic failure tests.

- [x] Add failing version, repeated/empty COPY, rollback, retention and publication tests; run them.
- [x] Implement defensive metadata copies, format validation, strict atomic save, unique partition staging and two-version commits with cleanup. Preserve shared file offsets and reject version overflow.
- [x] Verify focused tests and adapt old assertions for format 2 and one file per partition.

### Task 2: SQL DELETE and pinned read/write pipelines

Files: grammar, new `DeleteStatement.java`, `Statement.java`, `SqlAstBuilder.java`, `Binder.java`, `Planner.java`, `ScanOperator.java`, `Executor.java`, `StorageEngine.java`.

Interfaces: snapshot-aware `Binder.bind(Statement, Table)` and `Planner.plan(SelectStatement, Table)`; `ScanOperator.PartitionRef(Path, Partition)`; `StorageEngine.delete(String, Optional<Predicate>)` with deletion counters. Existing bind/plan entry points capture once and delegate.

- [x] Add failing parser, binder, version 0–3, row ordering, pinned-plan, selective pruning, false-positive and unfiltered DELETE tests.
- [x] Implement grammar/AST, shared predicate binding, file-aware scanning, snapshot execution and bounded DELETE rewrites. Include versions and deletion counters in summaries.
- [x] Verify focused tests, including all typed predicate comparisons and restart.

### Task 3: Recovery, front door and release documentation

Files: transaction and packaged integration tests, README, storage design, release notes.

- [x] Test orphan cleanup after validated startup, failed cleanup after commit, shared-file retention and process termination around publication.
- [x] Run launcher script with CREATE, two COPYs, DELETE and SELECT; compare to DuckDB if available and explicitly report absence otherwise.
- [x] Document sequential auto-commit, retention, failure limits and the singular transaction-design filename.
- [x] Generate private-inclusive Javadocs with `-Xdoclint:all`; run `mvn -B verify`; inspect final diff.

## Execution ledger

- User requested implementation of the supplied design; use it as the approved specification and execute inline without repeating design approval.
- Existing feature branch has only untracked user-provided design/exercise material. Work in place and preserve those files. No commit, release tag or push is required for implementation.

- Task 1: complete — versioned catalogs, append COPY, rollback, retention and atomic-publication tests pass.
- Task 2: complete — DELETE grammar/binding/execution and captured file-aware scans pass.
- Task 3: complete — JVM termination, launcher, failure and documentation checks pass. DuckDB is absent; comparison is not counted as successful.
- Independent read-only review found no Critical or Important issues. Added coverage for its two minor gaps: changing DELETE overflow and rollback after a later-partition read failure.
- Cleanup ruling: catch ordinary unchecked filesystem failures as well as checked I/O errors; catalog-temporary deletion and expired-file deletion cannot undo successful publication.
- Final diff review found the CLI discarded the unsupported-format cause. A regression test reproduces that diagnostic gap; include the cause in the storage-load message so the front door reports the unsupported format clearly.

- Final verification: `mvn -B verify` passes (47 unit tests and 32 integration tests; no failures, errors or skips). Private-inclusive `javadoc -Xdoclint:all` exits 0 without warnings. Final diff and `git diff --check` inspected.

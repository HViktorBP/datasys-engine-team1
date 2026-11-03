# Exercise 8 release notes

Release target: v0.8. Implements the Exercise 8 section of the
[transaction design](transaction-design.md), including the optional rebase.

- Statements may run concurrently within one engine. COPY and DELETE parse,
  scan and stage new partition files without a lock; only the commit section
  (validate, swap, save) is serialized. The package-private test hook
  `StorageEngine.beforeCommit` runs right before that section, outside the lock.
- Every commit validates its base version. Changes are replayed on the version
  committed by then at partition grain: COPY/COPY, DELETE/COPY and DELETEs on
  different partitions rebase and commit the next version after the current one.
  Two DELETEs that replace or remove the same partition are a true conflict: the
  later committer throws `ConflictException`, logs
  `conflict table=… base=… current=…` at ERROR, removes its staged files and
  leaves the table unchanged. The caller reruns the statement.
- A DELETE rebased onto a COPY keeps the COPY's rows, even rows that match its
  predicate. This equals running the DELETE first and the COPY second.
- A rebase logs `rebase table=… base=… current=… version=…` at INFO. COPY
  summaries now report `rows=… partitions=…` and the version actually committed;
  DELETE summaries also report the committed version, which can be later than
  base + 1.
- Unreferenced partition files are deleted only when an engine opens the data
  directory, no longer after every commit. An open scan therefore keeps its files
  across any number of commits, and a parked writer keeps its staged files while
  another writer commits. Catalogs still retain the current and previous version;
  retired files stay on disk until the next start.

## Verification

Run `mvn -B verify`. `ConcurrencyTest` forces each race deterministically by
parking a writer in `beforeCommit` with two latches, or by nesting the second
statement in the hook on one thread; every wait has a timeout. It covers a
snapshot read across one and several commits, DELETE/COPY and COPY/COPY rebases
(also after a restart), the same-partition DELETE conflict with its rerun,
disjoint DELETEs and the conflict's ERROR log line. `WriteSetTest` covers how a
change is replayed: in-place substitution, removal, appended files, a list that
grew, splitting a shared file and a missing partition.

## Supported failure boundary

Concurrent statements within one engine are supported. Several engines on one
data directory, for example in several processes, are not: they share no lock,
and one engine's startup cleanup can delete another engine's staged files. OS
crashes and power loss remain outside the contract, as in Exercise 7. This
implementation does not create or push a v0.8 release tag.

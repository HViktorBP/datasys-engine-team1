
- **Versioning:** each table gets a version number that a committed `COPY` or `DELETE` increments. Where does the counter live, and what exactly is a version in your metadata: one rewritten catalog file, one catalog file per version, or metadata held in memory only and flushed when the engine exits? The in-memory variant keeps all live versions of all tables in data structures that are safe under concurrent modification. It is the cheapest to commit and the one that loses the most if the engine crashes.

- We would want to go with one rewritten catalog file.
- **Commit:** a commit swaps the table's partition list and increments the version, atomically. Atomic how, in one JVM and on disk? (Writing a temporary file and renaming it over `catalog.json` is atomic on POSIX; an in-memory swap is one reference assignment.)
- Yes lets keep the version in catalog.json

- **Snapshot:** a statement pins the version it reads when it starts and never re-reads metadata. What exactly does it pin: a reference to a catalog version, or its own copy of the partition list with offsets and counts? And when: at statement start, or when the plan opens?
- So we would want to go with copy of the partition list. The idea that we have is that once we perfrorm it in a DuckDB way of doing things, from the start os statemnet, we do the copy of the existing data, perform our statennts and than commit them by writting to the disk. We omit concurenccy for now.

- **Retention:** how many versions back does the engine keep? A version must not be dropped while a `SELECT` or a `DELETE` still reads it, so how does the engine know that no statement still uses it? Reference counting, a list of running statements, and a fixed number of retained versions are all defensible. Write down which one you pick, and what a statement sees when it asks for a version that is gone.
- We go with fixed number of 2.


- **The rewrite set:** `DELETE` must rewrite exactly the partitions that may contain matches, which is the complement of week 4's pruning decision. Where does the shared helper live so that the planner and `DELETE` decide from the same code? And what happens to a partition that may contain a match but turns out to contain none: do you write an identical copy, or carry the original over?
- It should resuse the logic of the pruning in the planer and carry over the original pratition if it doesnt contain any matches.

- **New version:** does a `DELETE` that matches nothing still create a new version?
- For the sake of optimisation we wouldnt want to create a new version

- **Partition placement:** do new partitions go at the end of the table's single data file, or into a file of their own? Either answer can be defended; write yours down.
- file of their own

## Exercise 8: concurrent writers

These answers extend the Exercise 7 design above.

- **Concurrency control:** optimistic. A writer pins its base version, reads its
  rewrite set and stages new partition files without holding any lock. Only the
  commit is serialized: validation, the swap of the table's partition list and the
  catalog save run in one critical section on a single engine lock. The test hook
  `beforeCommit` runs right before that section, outside the lock, so a writer
  parked there blocks no one.

- **Validation and conflict granularity:** relaxed, at partition grain. Rejecting
  every commit whose base is no longer current would abort writers that never
  touched the same data, such as two appending COPYs. Each writer records its
  change relative to its base: the partitions it replaced, each with its
  replacement file or with nothing if all its rows were deleted, and the files it
  appended. A partition is identified by its file path and its exact
  metadata, including chunk offsets. At commit the change is replayed on the
  version committed by then. If a commit in between already replaced or removed a
  partition this writer also replaced, the writer loses. Otherwise it is rebased:
  replacements are substituted in place, appended files go last, and the result
  becomes the next version after the current one.

  | Race | Outcome |
  |---|---|
  | COPY / COPY | both commit; the later one is rebased |
  | DELETE / COPY, either order | both commit |
  | DELETE / DELETE, different partitions | both commit |
  | DELETE / DELETE, same partition (even different rows) | the later committer aborts |

- **Semantics of a rebased DELETE:** a DELETE that is rebased onto a COPY does not
  delete rows the COPY added, even if they match its predicate, because it only
  examined the partitions of its own snapshot. The result equals running the DELETE
  first and the COPY second. Concurrent statements are correct when their result
  equals running them one after the other in some order, so this is allowed. A
  reader of the COPY's version briefly sees the COPY without the DELETE.

- **On a conflict:** the losing statement throws `ConflictException` (a
  `RuntimeException`), and the engine logs `conflict table=… base=… current=…` at
  ERROR. Nothing it wrote is reachable, because no catalog references its staged
  files; it deletes them before returning, and a crash would leave orphans that the
  next startup removes. The engine does not retry by itself: the caller reruns the
  statement, which then starts from the newest version and decides again which rows
  match.

- **Concurrent appends to one file:** cannot happen. Two writers that appended to
  the same data file could overwrite each other's bytes or interleave them, even if
  their catalog commits were then serialized correctly. Here every partition is
  written to a new file of its own, so writers never share a file they write.

- **Retention and cleanup (changed):** Exercise 7 kept the current and previous
  version and deleted every other partition file after each commit. A running
  statement could therefore lose files it still needed: a scan pinned to a version
  failed with `Cannot read …` after two later commits, and a writer parked before
  its commit lost its staged files to the winner's cleanup. Catalogs still retain
  the current and previous version, but unreferenced files are now deleted only when
  an engine opens the directory. A running engine deletes nothing except a failed
  statement's own staged files, so a statement can never ask for a version that is
  gone while it runs. The cost is that retired files stay on disk until the next
  start.

- **Not supported:** several engines, for example in several processes, on one data
  directory. They share no lock, and one engine's startup cleanup can delete another
  engine's staged files. A directory lock was deferred, because the tests reopen the
  same directory in one JVM to check restarts.

- **Testing:** races are forced, not hoped for. A test parks one writer in
  `beforeCommit` with two latches while another statement commits on the test
  thread, or runs the second statement nested in the hook on the same thread, which
  needs no second thread at all. Every wait has a timeout, so a broken engine fails
  a test instead of hanging the build.

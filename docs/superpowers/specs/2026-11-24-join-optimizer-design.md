# Exercise 11 Join Optimizer

**Date:** 2026-11-24
**Status:** Approved for implementation (Exercise 11; user requested spec + implementation)
**Spec:** `Exercises/Exercise11.md`
**Course design:** [`docs/optimizer-design.md`](../../optimizer-design.md)
**Prior notes:** [`docs/exercise-10.md`](../../exercise-10.md)

## Goal

Collect per-column NDV during `COPY`, estimate base-table, filter, and equi-join
cardinalities from catalog statistics, and replace week 10's `FROM`-list join
order with greedy smallest-intermediate-first. `-j syntax` keeps the week 10
plan. Every executed hash join logs `estRows` and `rowsOut`. `SELECT *` column
order stays `FROM`-list order.

## Non-Goals

- `GROUP BY`, `min`/`max`/`count`/`sum`, and column-list `SELECT` (optional
  extension).
- HyperLogLog, histograms, Selinger dynamic programming, bushy trees.
- Catalog format 3 or rewriting old partitions on open.
- Creating or pushing tag `v0.11`.
- Changing binary file format 1.

## Constraints

- Java 25, Maven, JUnit 6, SLF4J, Jackson 2.18.3, ANTLR 4.13.2.
- New production types stay in `dk.itu.datasys`.
- TDD. Javadoc is part of the implementation (`@since 0.11` / `@version 0.11`
  on new public and protected APIs; preserve historical `@since`).
- Catalog format remains 2. `ndv` is optional JSON on `PartitionStatistics`.
- `-j optimized` is the default; `-j syntax` is bit-compatible with week 10's
  join order and build-side rule.
- Log records stay 7 ASCII CSV fields. New messages must not introduce commas,
  quotes, or line breaks.
- `StorageEngineIT` stays green; update the statistic-line count when NDV lines
  are added, do not rewrite unrelated assertions.
- Existing single-table `Planner` tests stay valid under either mode.

## Architecture

```
COPY / DELETE rewrite
  -> HashSet NDV per column in writePartition
  -> catalog Chunk.statistics.ndv (nullable)

SELECT
  parse -> bind -> Planner.plan(mode)
    per table: prune, estimate surviving rows and column NDVs, scan+filter
    syntax: left-deep FROM order, left always builds
    optimized: greedy on Cardinality.equiJoin, smaller side builds
    HashJoin(estRows, joinIndex) ... Project to FROM column order
  Executor logs joinOrder and mode
  HashJoin.close logs join, tables, estRows, rowsBuild, keys, rowsProbe, rowsOut
```

## Components

### `PartitionStatistics`

```java
record PartitionStatistics(String min, String max, Long ndv)
```

- Two-argument constructor `(min, max)` keeps `ndv == null` for tests and old
  catalogs.
- `compute` fills exact NDV; `-0.0` and `0.0` count as one distinct double.
- Jackson: `@JsonProperty(required = false)` on `ndv` so
  `FAIL_ON_MISSING_CREATOR_PROPERTIES` still loads v0.10 catalogs.
- `COPY` logs the existing min/max line, then
  `table={} partition={} column={} rows={} ndv={}`.

### `Cardinality`

Package-private pure functions (see optimizer-design.md for formulas):

- `tableNdv(List<Long> partitionNdvs)` → max; rejects empty/null/non-positive.
- `baseTable(long survivingRows)`
- `equalityFilter(long inputRows, long ndv)`
- `rangeFilter(long inputRows)` — Selinger 1/3
- `equiJoin(long leftRows, long rightRows, long leftNdv, long rightNdv)`

Missing catalog NDV becomes `1` **before** `tableNdv`.

### `JoinMode`

```java
public enum JoinMode { OPTIMIZED, SYNTAX }
```

`JoinMode.parse(String)` accepts `optimized` and `syntax` only.

### `JoinOrderer`

Package-private. Inputs: per-table row estimates, per-table per-column NDV,
join edges in `WHERE` order, mode. Output: `joinOrder` (FROM indexes), hash
steps (added table, partner table and columns, estimates, `leftBuilds`), and
the list of candidates checked (for log lines).

- Optimized: seed = cheapest edge; grow by cheapest connected table; no cross
  product while a connected choice exists.
- Syntax: FROM order; first connecting predicate in `WHERE` order; `leftBuilds`
  always true; reject if table `i` has no edge to `{0..i-1}`.

### `ProjectOperator`

Package-private Volcano operator. `next` emits `row[columns[i]]`. Identity
permutations are skipped by the planner.

### `HashJoinOperator`

Gains `estRows` and `join` (1-based bottom-up). `close` logs:

```
join={} tables={} estRows={} rowsBuild={} keys={} rowsProbe={} rowsOut={}
```

`tables` is semicolon-separated composition-prefix names so the CSV log stays seven fields.

### `Planner` / `Executor` / `Engine`

- `Planner(StorageEngine)` defaults to `OPTIMIZED`.
- `Planner(StorageEngine, JoinMode)` for `-j syntax` and week-10 tests.
- `QueryPlan` carries `joinOrder` (exposed names) and `mode`.
- The planner logs `joinOrder={} mode={}` on every `SELECT`.
- `Engine` accepts `-j optimized|syntax` before or after `-c`/`-f`. Invalid
  `-j` prints the error to stderr and exits 1. Default is optimized.

### Column order

Physical rows stay build‖probe. After the last join, `ProjectOperator` maps to
the concatenation of table schemas in `FROM`-list order. Filters below joins
are unchanged.

## Tests (JUnit 6)

1. NDV: known duplicates → exact per-partition count; `tableNdv` is max;
   missing `ndv` in catalog JSON still loads.
2. Estimator: hand-made numbers for base, equality, range 1/3, join formula.
3. Ordering: statistics where `FROM` order is worst; optimized picks the
   documented order; never a cross product while connected.
4. Build side: smaller estimated input builds (`-j optimized`); syntax keeps
   left-builds.
5. Column order: rearranged plan, `SELECT *` still `FROM` order.
6. Metamorphic: testbed three- and four-table queries under both flags,
   identical sorted rows, different `joinOrder`, every join log has `estRows`
   and `rowsOut`.

## Documentation

- `docs/optimizer-design.md` — course design (statistics, merge, estimator
  sources, heuristic, guardrail).
- `docs/exercise-11.md` — short implementation notes, like exercise-10.
- README: `-j`, optimizer default, pointer to the design doc.

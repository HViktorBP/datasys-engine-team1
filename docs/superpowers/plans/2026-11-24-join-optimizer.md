# Exercise 11 Join Optimizer Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task on branch `exercice-11`.

**Goal:** Persist NDV, estimate join cardinalities, and greedily order hash joins while keeping week 10 available behind `-j syntax`.

**Architecture:** Exact per-partition NDV next to min/max; `Cardinality` as pure catalog arithmetic; `JoinOrderer` as the heuristic; `Planner` builds left-deep hash joins with the smaller estimated input as build and a final `ProjectOperator` for `FROM`-list column order.

**Tech Stack:** Java 25, Maven, JUnit 6, Jackson 2.18.3, SLF4J.

**Spec:** `docs/superpowers/specs/2026-11-24-join-optimizer-design.md` and `docs/optimizer-design.md`.

## Global constraints

- Catalog format 2; `ndv` optional; v0.10 catalogs must load.
- `-j optimized` default; `-j syntax` is week 10's order and left-builds rule.
- Log lines: 7 ASCII CSV fields, no commas in the message.
- New APIs `@since 0.11` / `@version 0.11`; do not retag historical `@since`.
- No `GROUP BY`. No `v0.11` tag from this plan.
- TDD: failing test before production code for each task.

## File map

- Create: `Cardinality.java`, `JoinMode.java`, `JoinOrderer.java`, `ProjectOperator.java`
- Create tests: `CardinalityTest.java`, `JoinOrdererTest.java`, `ProjectOperatorTest.java`
- Modify: `PartitionStatistics.java`, `StorageEngine.java`, `CatalogStore.java`, `HashJoinOperator.java`, `Planner.java`, `QueryPlan.java`, `Executor.java`, `Engine.java`
- Modify tests: `StorageHelpersTest.java`, `StorageEngineIT.java`, `HashJoinOperatorTest.java`, `PlannerTest.java`, `JoinTestbedIT.java`, `EngineTest.java`
- Docs: `docs/exercise-11.md`, `README.md`

---

### Task 1: NDV on partitions

**Files:** `PartitionStatistics.java`, `StorageEngine.java`, `CatalogStore.java`, `StorageHelpersTest.java`, `StorageEngineIT.java`

**Produces:** `PartitionStatistics(String min, String max, Long ndv)`, `compute` fills NDV, `Cardinality.tableNdv` exists after Task 2 — this task only adds `tableNdv` if tests live here; prefer putting the merge helper in `Cardinality` in Task 2. This task logs NDV and persists it.

- [ ] Failing tests: `computesTypedStatistics` expects NDV; new test exact duplicates; new test catalog JSON without `ndv` loads; `logsEveryStatistic…` expects the extra NDV lines.
- [ ] Implement `ndv` on the record (optional JSON), `HashSet` in `compute` with `-0.0`/`0.0` collapsed, second log line, catalog validation `1 <= ndv <= rowCount` when present.
- [ ] `mvn -B -Dtest=StorageHelpersTest,StorageEngineIT test`

`tableNdv` can land in Task 2 with `Cardinality`; if Task 1 needs it for a unit test, put a static helper on `PartitionStatistics` and move it in Task 2. Prefer Task 2 only.

---

### Task 2: Estimator

**Files:** Create `Cardinality.java`, `CardinalityTest.java`

**Produces:**

```java
static long tableNdv(List<Long> partitionNdvs)
static long baseTable(long survivingRows)
static long equalityFilter(long inputRows, long ndv)
static long rangeFilter(long inputRows)
static long equiJoin(long leftRows, long rightRows, long leftNdv, long rightNdv)
static long missingNdv() // 1
```

- [ ] Failing tests for max-merge, empty/invalid lists, base table, `|R|/ndv`, `|R|/3`, `|R|·|S|/max(ndv)`, zero inputs, overflow saturation.
- [ ] Minimal implementation matching `docs/optimizer-design.md`.
- [ ] `mvn -B -Dtest=CardinalityTest test`

---

### Task 3: Join orderer

**Files:** Create `JoinMode.java`, `JoinOrderer.java`, `JoinOrdererTest.java`

**Produces:** `JoinMode.{OPTIMIZED,SYNTAX}`, `JoinOrderer.optimized` / `syntax` returning join order (FROM indexes), hash steps, and checked candidates.

Hand-made case for the required ordering test:

| Table | Rows | Join NDVs |
|---|---|---|
| 0 `a` | 100 | `a_k` NDV 1 |
| 1 `b` | 10 | `b_ak` NDV 1, `b_ck` NDV 10 |
| 2 `c` | 2 | `c_k` NDV 2 |

Edges in WHERE order: `a.a_k = b.b_ak`, `b.b_ck = c.c_k`.

- `a ⋈ b` est = 100×10/max(1,1) = 1000
- `b ⋈ c` est = 10×2/max(10,2) = 2

Optimized seed is `b,c` (c smaller → joinOrder starts `c,b`), then `a`. Syntax is `a,b,c`. Cross-product guard: no edge to a leftover table while another connected table exists.

- [ ] Failing tests for that order, syntax FROM order, left-builds in syntax, smaller-builds in optimized, disconnected rejection.
- [ ] Implement.
- [ ] `mvn -B -Dtest=JoinOrdererTest test`

---

### Task 4: Project, hash-join log, planner wiring

**Files:** `ProjectOperator.java`, `HashJoinOperator.java`, `Planner.java`, `QueryPlan.java`, `Executor.java`, matching tests

**Produces:** `ProjectOperator(Operator child, int[] columns)`; `HashJoinOperator` constructor gains `long estRows, int join`; log line with `join`, `estRows`; planner uses `JoinOrderer`; root project to FROM order; `QueryPlan(root, stats, joinOrderNames, mode)`; executor `joinOrder={} mode={}`.

- [ ] Failing `ProjectOperatorTest` permutation.
- [ ] Failing `HashJoinOperatorTest.closeLogsTheJoinSummary` new format.
- [ ] Failing `PlannerTest`: syntax still left-deep FROM; optimized on testbed starts `customers,cities` (cheapest edge); `SELECT *` column order; disconnected syntax still rejected; optimized accepts `FROM orders, cities, customers` with connecting predicates.
- [ ] Implement; keep `Planner(StorageEngine)` as optimized; week-10 order tests pass `JoinMode.SYNTAX`.
- [ ] `mvn -B -Dtest=ProjectOperatorTest,HashJoinOperatorTest,PlannerTest test`

---

### Task 5: `-j` flag and metamorphic testbed

**Files:** `Engine.java`, `EngineTest.java`, `JoinTestbedIT.java`, `README.md`, `docs/exercise-11.md`

- [ ] Failing usage/`-j` tests: default optimized, `-j syntax`, invalid `-j`.
- [ ] Failing `JoinTestbedIT` metamorphic: three- and four-table queries, both flags, identical sorted rows, different `joinOrder`, every `HashJoinOperator` line has `estRows` and `rowsOut`. Existing DuckDB comparison stays (engine default optimized, sorted rows unchanged).
- [ ] Implement `Engine` flag parsing: `-j` may sit before or after `-c`/`-f`.
- [ ] `mvn -B verify`
- [ ] Private-inclusive Javadoc `-Xdoclint:all`
- [ ] README and `docs/exercise-11.md`

---

## Execution ledger

- User asked to solve Exercise 11 and write the superpowers files. Implemented inline. Do not commit unless asked. Do not tag `v0.11`.
- `mvn -B clean verify`: 109 unit tests and 38 integration tests, all green.
- Private-inclusive Javadoc with `-Xdoclint:all` exits 0; remaining warnings are pre-existing `@version` on methods and generated ANTLR sources.

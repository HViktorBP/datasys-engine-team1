# Exercise 10: Multi-table hash joins

Release target: v0.10. The engine joins any number of tables with `SELECT *`, an
implicit-join `FROM` list and `AND`. This release also includes the optional
extension for qualified names and table aliases. Column lists are not
supported because the engine has no projection operator.

## Grammar

```antlr
select    : SELECT '*' FROM tableRef (',' tableRef)* (WHERE condition)? ;
tableRef  : table=IDENTIFIER (alias=IDENTIFIER)? ;
delete    : DELETE FROM IDENTIFIER (WHERE filter)? ;
condition : predicate (AND predicate)* ;
predicate : filter | join ;
filter    : columnRef comparison=('=' | '<' | '>') literal ;
join      : left=columnRef '=' right=columnRef ;
columnRef : (qualifier=IDENTIFIER '.')? name=IDENTIFIER ;
```

- **Reserved words:** `AND` is now a reserved word.
- **Aliases:** an alias is written without `AS`, as in `customers c1`.
- **Not supported:** `OR` and parentheses.
- **`DELETE`:** still compares one column with one constant. It may qualify that column with its own table name (`trips.distance`).

### Syntax tree

The syntax tree builder sorts each condition into one of two lists:

- **Filters:** a column compared with a constant goes into `SelectStatement.filters`.
- **Joins:** a column compared with a column goes into `SelectStatement.joins`.

`SqlPrinter` prints the joins first and then the filters, so reparsing printed SQL rebuilds the same two lists and the round trip `parse(print(s)) = s` holds.

**Where `SqlPrinter` lives:** Exercise 5 removed `SqlPrinter` because only tests used it, and production code must be reachable from `main`. It now lives in test sources, as a helper for the round-trip test. This satisfies that test without bringing back unreachable production code.

## Name resolution

`ColumnResolver` is the one helper that both the binder and the planner use. The joined row is the table schemas concatenated in `FROM`-list order. The resolver maps a column reference to its `FROM` entry, to its position inside that table, and to its position in the joined row.

| Statement | Our engine | DuckDB |
|---|---|---|
| `... WHERE o_custname = c_namee` | `Unknown column: c_namee` | not found |
| `FROM t1, t2 WHERE id = 1` | `Ambiguous column: id; qualify it ...` | ambiguous |
| `FROM orders, products WHERE o_quantity = p_price` | `Join needs two columns of one type: ...` | casts and runs |
| `FROM orders, customers WHERE o_custname = o_prodname` | `Join needs two tables: ...` | cross join plus filter |
| `FROM customers, customers` | `Duplicate table name or alias in FROM: ...` | duplicate alias |
| `FROM customers c1 WHERE customers.c_name = 'x'` | `Unknown table or alias: customers` | not found |

### Qualifiers

A qualifier matches the alias of a `FROM` entry, or its table name when it has no alias. An alias therefore hides its table name, as in DuckDB.

### Duplicate names

Two entries may not expose names that differ only in case, because DuckDB rejects such duplicate aliases.

### Case sensitivity

**Known divergence:** column and table names are otherwise case-sensitive, as everywhere else in the engine. As a result, `city` in one table and `City` in another are not reported as ambiguous, while DuckDB would report them.

### Join type rule

The binder rejects joins between columns of different types. The hash join matches keys with Java `equals`, so `3L` and `3.0` would never match: the query would silently return no rows.

## Planning and execution

### Pipelines

The planner builds one pipeline per `FROM` entry:

- a scan over the partitions that survive pruning;
- one filter per `WHERE` filter on that table.

Filters therefore sit below the joins, and min/max pruning keeps working per table.

A partition is pruned when any filter on its table proves that it holds no match. Each check logs one `decision=` line, in the format used before, stopping at the first `PRUNED`. The `table=` field holds the alias when the entry has one, so the two sides of a self-join can be told apart.

**Snapshots:** a statement pins one snapshot per distinct table at statement start, so both sides of a self-join read the same version.

### Join order

The plan is left-deep in `FROM`-list order. The result so far builds the hash table and the next table probes it:

```java
new HashJoinOperator(joinedSoFar, newTable, buildKey, probeKey, tables)
```

The fifth parameter is a deliberate addition to the exercise's signature. It labels the join in its log line.

Output rows are the build columns followed by the probe columns. As a result, `SELECT *` returns columns in `FROM`-list order, which matches DuckDB.

### Connecting predicates

- **No connecting predicate:** a table with no join predicate to a table before it is rejected, because the join would be a cross product:

  ```text
  No join predicate connects cities to the tables before it; reorder the FROM list
  ```

  DuckDB runs such a query because its optimizer reorders the joins.
- **More than one connecting predicate:** the first in `WHERE` order becomes the hash-join key. Every further one runs as a `ColumnEqualityFilterOperator` directly above that join. For example:

  ```sql
  SELECT * FROM customers c1, customers c2
  WHERE c1.c_city = c2.c_city AND c1.c_name = c2.c_name
  ```

  This hash-joins on `c_city` and then keeps the 3 rows whose names are equal.

### Key equality

Join keys normalize `-0.0` to `0.0`. SQL, DuckDB and this engine's filters all treat the two as equal, but `Double.equals` does not.

## Log lines

Each hash join logs one summary line when it closes. It closes its children first, so the joins of a plan log from the bottom up:

```text
HashJoinOperator,tables=orders;customers rowsBuild=4 keys=3 rowsProbe=3 rowsOut=4
```

`rowsOut` is the size of the intermediate result. The `Executor` logs:

- one line per `FROM` entry: `table=<alias or name> partitionsTotal=… partitionsRead=… partitionsPruned=…`;
- one SELECT summary listing all entries and their read versions, such as `table=orders;customers version=4;2`.

Single-table statements log exactly as before.

## DuckDB oracle

A hash join emits rows in probe order, and DuckDB promises no order either, so compare sorted output. DuckDB must load the testbed CSVs with `HEADER false`. Otherwise its sniffer takes the first line of the all-`STRING` `customers.csv` as a header and silently drops Anna. DuckDB's CSV mode also ends lines with CRLF, which `tr` strips. From the repository root, after `mvn -B package`:

```sh
cd src/test/resources/joins
Q="SELECT * FROM orders, customers, products, cities
   WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city;"
rm -rf data && ../../../../engine -f setup.sql
printf '%s\n' "$Q" > /tmp/q.sql && ../../../../engine -f /tmp/q.sql | sort > /tmp/ours.csv
{ sed "s/';\$/' (HEADER false);/" setup.sql; printf '.mode csv\n.headers off\n%s\n' "$Q"; } \
  | duckdb | tr -d '\r' | sort > /tmp/theirs.csv
diff /tmp/ours.csv /tmp/theirs.csv && rm -rf data logs
```

`JoinTestbedIT` runs the four testbed queries and a self-join through `./engine -f`. It compares their sorted output with the DuckDB output pasted in from this procedure. DuckDB returns 4, 4, 4 and 3 rows for the testbed queries and 5 for the self-join.

## Tests

| Area | Coverage |
|---|---|
| **Parser** (`SqlParserTest`, `SqlPrinterTest`) | Table lists, `AND`, sorting into filters and joins, aliases, qualified names, rejected `OR`/parentheses, and the round trip for every statement shape. |
| **Resolution** (`ColumnResolverTest`, `BinderIT`) | Positions in the joined row, unknown, ambiguous and duplicate names, qualifiers and aliases, same-table and mismatched-type joins, qualified `DELETE`. |
| **Operators** (`HashJoinOperatorTest`, `ColumnEqualityFilterOperatorTest`) | The cursor call by call, duplicates multiplying, unmatched rows, empty build and probe sides, `-0.0`, reopening, and the summary log line. |
| **Planner** (`PlannerTest`) | Three left-deep joins in `FROM`-list order with the slide row counts, filters below the joins, per-table `ScanStats`, the rejected cross product, and an extra predicate applied as a filter. |
| **End to end** (`JoinTestbedIT`) | The testbed through the packaged launcher. |

This implementation does not create or push the v0.10 tag.

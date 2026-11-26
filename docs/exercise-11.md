# Exercise 11: Statistics and the join optimizer

Release target: v0.11. Design: [optimizer-design.md](optimizer-design.md).
This release does not create or push the tag.

## What changed

- `COPY` (and rewriting `DELETE`) stores an exact per-column NDV next to min/max.
  Old catalogs without `ndv` still load; missing NDV is estimated as 1.
- `-j optimized` (default) orders equi-joins by smallest estimated intermediate
  result and builds the smaller side. `-j syntax` is week 10's `FROM`-list plan.
- `SELECT *` column order stays `FROM`-list order after a `ProjectOperator`.
- Each hash join logs `estRows` and `rowsOut`. The planner logs `joinOrder` and
  `mode`. Table lists use semicolons so the CSV log stays seven fields.

```bash
./engine -j syntax -c "SELECT * FROM orders, customers WHERE o_custname = c_name"
./engine -j optimized -f query.sql
```

`GROUP BY` was not implemented.

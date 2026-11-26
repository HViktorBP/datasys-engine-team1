# Join optimizer design

## Statistics

Per partition: `rowCount`, min/max per column, and NDV per column (including
`STRING`), collected during `COPY` with a `HashSet`.

Per table, after pruning: surviving row count, and one NDV per column from the
surviving partitions. Min/max stay for pruning only.

## Where they live and how they merge

NDV sits next to min/max on each catalog chunk. Catalog format stays 2;
`ndv` is optional JSON so v0.10 catalogs still load.

Merge: `tableNdv = max(partition NDVs)`. Max is a lower bound of the true table
NDV, sum is an upper bound. The join formula divides by NDV, so a low NDV
overestimates join size and a high NDV underestimates it. Leis et al. (VLDB
2015) found underestimates cause the bad plans, so we take max.

At a billion rows we would store HyperLogLog instead of a `HashSet`; it merges
exactly. Course scale stays exact.

## Roll forward

Missing NDV means "not collected". The estimator substitutes `1` before
merging, which overestimates joins rather than pretending the column is unique.

New `COPY` and rewriting `DELETE` write NDV. Old partitions keep `ndv` absent
until rewritten. Format stays 2, and a v0.10.1 patch version was created that can
ignore unknown attributes, meaning catalogs written by v0.11 can be read by v0.10.1.
v0.11 can also read tables without it. No rewrite on open.

## The estimator

- **Base table:** \|R\| = sum of `rowCount` over partitions that survive
  pruning (lecture 10).
- **Equality filter:** \|R\| / ndv(column). Uniform frequencies, System R /
  Selinger.
- **Range filter (`<`, `>`):** \|R\| / 3. Selinger, SIGMOD 1979. We do not
  interpolate from min/max.
- **Equi-join:** \|R ⋈ S\| ≈ \|R\| · \|S\| / max(ndv(R.a), ndv(S.b)) (lecture
  10). Only the hash-join predicate is estimated.

After a join, a column's NDV is still the NDV of the base table that contributed
it.

## The heuristic

Greedy smallest-intermediate-first (course baseline / Fegaras GOO):

1. Estimate each table after pruning and filters.
2. Start with the join whose estimated output is smallest.
3. Add the connected table whose estimated join output is smallest. Never pick
   a cross product while a connected choice exists.
4. The smaller estimated input builds the hash table.

Exhaustive search is cheap at four tables. We still use greedy because week 12
compares it to `-j syntax`, and because Leis et al. blame estimates, not search.

`-j optimized` is the default. `-j syntax` keeps week 10's `FROM`-list order.
`SELECT *` columns stay in `FROM`-list order.

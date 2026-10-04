# Experiment design: selectivity and partition pruning

Recorded before performance measurements. This document specifies the week 6
experiment; the Exercise 5 functional log demo is not a measurement of it.

## Question and plot

How does predicate selectivity affect the fraction of partitions read, and how
does sorting the input on the predicate column change that relationship?

Sweep `SELECT * FROM observations WHERE id < k` over 65,536 unique consecutive
IDs, from 0 through 65,535. Use `k = 0, 64, 1024, 4096, 16384, 32768, 49152,
65536`; the exact selectivity is `k / 65536`. Keep partition size fixed at the
front door's default of 1,000 rows, giving 66 partitions: 65 full partitions and
a final 536-row partition. Sorting is a controlled comparison; selectivity is
the swept dimension. Import into fresh tables so their partitioning uses this
default rather than boundaries persisted under an earlier setting.

Plot x = selectivity (%) on a linear scale from 0% to 100%, y = partitions read
/ partitions considered, from 0 to 1. One curve represents sorted input; the
other represents the same rows shuffled with seed 2026. Use the median of five
retained repetitions at each point. This is an I/O-work metric, not elapsed time;
do not use `durationMs` to substantiate a latency claim.

## Data and procedure

1. Package once with `mvn -B verify`. Record the commit SHA, `java -version`,
   `mvn -version`, OS, CPU, available RAM, heap flags, and generator Python version.
   The planned machine is an Intel i7-11800H (16 logical CPUs), x86_64 WSL2 Linux
   6.18.33.1 with 7.6 GiB allocated to the VM, Oracle JDK 26.0.2, and Maven 3.8.7.
   Use `ENGINE_JAVA_OPTS='-Xms512m -Xmx512m'` for every engine process. Record any
   machine or JVM replacement before running and use it for the entire sweep.
2. Run `python3 scripts/generate-experiment-data.py /tmp/engine-experiment-input`.
   The committed generator writes headerless ASCII `sorted.csv` and
   `shuffled.csv`, each with schema `(id LONG, label STRING, value DOUBLE)`.
   Both contain identical rows; only their order differs. Record SHA-256 hashes.
3. Create separate fresh working directories for sorted and shuffled storage.
   In each, use the absolute repository launcher path with `-c` to create
   `observations`, then copy the corresponding input. Only one successful copy
   per table is supported. Reuse those catalogs for the sweep and keep generation,
   `CREATE`, `COPY`, and log-analysis work outside the measured statement session.
4. For each `k` and each layout, execute one discarded first run and five retained
   repetitions. Each is a fresh JVM invocation with one `-c` statement. Discard
   the first as a cache warm-up; subsequent JVMs still have fresh JIT state, and
   the OS cache is not forcibly cleared. Use ascending `k` in repetitions 1, 3,
   and 5 and descending `k` in 2 and 4, alternating which layout runs first.
5. Between invocations, while no engine process is running, move any existing
   active log and rotated logs out of the working directory into an evidence
   directory. Then run the single query, redirect stdout to a result file and
   stderr to a separate file, and require exit status 0. After process exit,
   preserve its new `logs/engine.log` unchanged as that repetition's raw evidence.
   Every measured file must contain only one session, no `ERROR` lines, one
   successful `statement=SELECT` summary, and exactly 66 `decision=` records.
   Record the session UUID and statement number 1. Do not accidentally include
   decisions produced by later log-analysis queries or discard rotated evidence.
6. In a separate analysis directory, create a new seven-column `logs` table for
   each raw file and `COPY` that file. Through the engine, export
   `SELECT * FROM logs WHERE sessionId = '<recorded UUID>'` to CSV and separately
   query `logLevel = 'ERROR'`. Python's CSV reader can then count exported column 7
   messages containing the exact `decision=READ` or `decision=PRUNED` token. The
   SQL subset has no aggregation or compound predicates. Require that their sum
   is 66; calculate `READ / (READ + PRUNED)` and store layout, `k`, repetition,
   session UUID, counts, ratio, and output row count. Require exactly `k` query
   output rows and equal result sets across layouts. Preserve raw and exported CSV.
7. Plot the specified curves and report all five retained values, their median,
   and observed spread. The structural counts should repeat exactly; differences
   indicate an evidence-selection or execution error and need investigation.

## Hypothesis stated before the first run

For sorted input, a partition is read exactly when its minimum ID is below `k`.
The predicted fraction is `ceil(k / 1000) / 66`, with zero read partitions at
`k = 0`. This is a staircase because each full partition covers 1,000 consecutive
IDs; it is not exactly the row selectivity, especially near zero.

For shuffled input, approximate independent placement predicts a read fraction
`(65 * (1 - (1 - s)^1000) + (1 - (1 - s)^536)) / 66`, where `s = k / 65536`.
This accounts for the shorter final partition. The exact expectation without
replacement is the average of 65 full-partition probabilities
`1 - C(65536 - k, 1000) / C(65536, 1000)` and one final-partition probability
`1 - C(65536 - k, 536) / C(65536, 536)`. Treat the numerator as zero when fewer
nonmatching IDs remain than the partition's row count. The fixed seeded shuffle
may differ from the expectation.

At `k = 64`, sorted input should read exactly 1/66 = 1.5152%; the exact shuffled
expectation is about 62.3104%. At `k = 1024`, sorted input should read exactly
2/66 = 3.0303%, while shuffled input should read almost 100% (about 99.9997%
in expectation). At `k = 32768`, sorted input should read 33/66 = 50%, while
shuffled input should read approximately 100%. Both should read 0% at `k = 0`
and 100% at `k = 65536`. These are predictions, not observed results.

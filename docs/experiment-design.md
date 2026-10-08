# Experiment design

## Question and hypothesis

How does maxRowsPerPartition affect CREATE + COPY and filtered SELECT duration
as dataset size changes?

Our hypothesis is that doubling partition size should halve the running time
of operations. We will test partition sizes of 256, 512, 4096, 16384, and
65536 rows, using the same procedure on three machines chosen by the team.

## Data

Generate CSV files with target sizes of 8 KB, 1 MB, 50 MB, 100 MB, and 500 MB.
Use decimal units: 1 KB = 1,000 bytes and 1 MB = 1,000,000 bytes. Commit the
generator and its fixed seed before taking measurements, and record each
file's actual byte size and row count.

Generate distance as a LONG, uniformly choosing integers from 0 to 999.
The predicate distance > 900 should match approximately 10% of rows; record
the actual matching count as well.
For each dataset size, create two files containing exactly the same rows:
one shuffled using a fixed seed, and one sorted by distance. Reuse these files
for every partition size and machine. Generate and sort them before measuring
any operations.

## Measurements

The first experiment measures CREATE + COPY on shuffled input. For each run,
add durationMs from the Executor's CREATE_TABLE and COPY statement summaries.
The storage-operation timings are nested within these measurements, so adding
them would count that time twice.

The second experiment measures
`SELECT * FROM trips WHERE distance > 900` on both sorted and shuffled input.
Take durationMs from the Executor's SELECT statement summary. Keep
partitionsTotal, partitionsRead, partitionsPruned, and rowsOut from the logs
to support the analysis. Check the returned row count against the expected
count for every partition size and both input orders.

The sorted files need importing before SELECT can run. Those imports are
preparation, and their duration is excluded from the CREATE + COPY comparison.
Each partition size needs its own import because partitions are formed when
the data is written. Use a fresh database directory for every import, including
SELECT preparation.

Run each configuration at least five times on each machine, using only cold
runs. Calculate the arithmetic mean separately for each combination of
machine, operation, dataset size, partition size, and input order. Report the
median and the minimum and maximum from those same samples. Exercise 6
requires the median and spread alongside our chosen mean.

## Cold run procedure

Start a fresh JVM for every measured repetition, with no warm-up operations
or warm comparison runs. For CREATE + COPY, use a fresh database directory
and measure the JVM's first CREATE + COPY workload. For SELECT, import the
table in a separate process, then measure the first SELECT in a fresh JVM.
Do not run COPY in that SELECT measurement process.

Restarting Java leaves the operating system's file cache intact. Before each
repetition, apply a documented cache-control method appropriate to that
machine's operating system. For CREATE + COPY, control the cache for the
source CSV. For SELECT, control it for the prepared table's catalog and binary
data. This step comes after data generation or import preparation and before
starting the measured JVM. Waiting or restarting the process alone does not
reset the file cache.

Record the process and filesystem-cache conditions and the cache-control
method for every trial. If we cannot establish control over the file cache,
keep the record separately and label it as a fresh JVM with an uncontrolled
filesystem cache. Exclude it from averages of uncached reads and collect at
least five qualifying cold samples for each point. Describe what the method
controls; it does not establish that every hardware cache is empty.

Keep the data, partition size, and query fixed across repetitions of a
configuration. Use the same output destination and logging settings for all
configurations, save the raw engine logs including rotated files, and record
the execution order. Data generation and result analysis happen outside the
measured operations.

Statement durationMs excludes JVM startup and catalog loading during
StorageEngine construction. It measures statement execution, so the reported
times do not cover the whole process from launch to exit.

## Execution conditions

The team chooses the machines, operating systems, and heap settings. Each
machine must meet the repository's runtime requirements and run the same set
of workloads and configurations. Keep its environment fixed throughout the
sweep and record the following once per machine run:

- Machine/run identifier and actual hardware specifications.
- Operating system and version.
- JVM implementation and version, heap size, and JVM options.
- Engine commit and benchmark script revision.
- Dataset checksums, seed, and any deviations from the procedure.

When comparing machines, document the differences in their environments.
The team will supply the hardware observations, describe how the engine ran,
and explain the measured differences after the experiments. We make no
prediction about which machine will be faster. The report's analysis and
reflections will come from the team's own observations, as Exercise 6 requires.

## Charts

Plot maxRowsPerPartition in rows on the x axis, using a logarithmic scale.
The y axis shows execution duration in milliseconds. Use a logarithmic y axis
where positive durations span orders of magnitude.

Each curve represents a dataset size. Each point shows the mean of at least
five cold runs, with whiskers extending from the minimum to the maximum.
Include a results table with the median from the same samples.

Use separate panels for CREATE + COPY, SELECT on sorted input, and SELECT on
shuffled input. Keep each machine's results identifiable, with its own mean,
median, and spread. Each caption must identify the operation, input order,
dataset size, machine run, process and cache conditions, and repetition count,
and explain what the whiskers show.

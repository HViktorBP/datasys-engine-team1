# Running the experiment

The design is in [experiment-design.md](experiment-design.md). The commands
below generate its data, run it on one machine, and turn the engine logs into
tables and charts. Run them from the repository root.

## Setup

Use JDK 25 or newer, Maven, and Python 3.10 or newer. Build the engine first:

```bash
mvn -B verify
```

Generation, execution, and table export use the Python standard library.
Charts also need matplotlib. Install it in a local environment:

```bash
python3 -m venv data/experiment/venv
data/experiment/venv/bin/python -m pip install -r scripts/experiment-requirements.txt
```

If your Python installation lacks venv/ensurepip and you have uv, use:

```bash
uv venv data/experiment/venv
uv pip install --python data/experiment/venv/bin/python -r scripts/experiment-requirements.txt
```

On Windows, the environment's Python executable is under `Scripts` instead of
`bin`. The experiment CLI itself uses Python subprocess argument lists and
platform-specific null output and classpath handling.

Commit the generator, runner, design, and other benchmark changes before the
final measurements. Build the JAR from that commit on each machine. The runner
records the commit, working-tree status, JAR checksum and runtime-content
fingerprint, and script checksums,
so uncommitted changes remain visible in the evidence.

## Generate the data once

```bash
python3 scripts/experiment.py generate --output data/experiment/datasets
```

Defaults are seed 42 and all five target sizes. Each target gets a shuffled
and a sorted CSV with identical rows. The schema is city STRING, distance LONG,
and price DOUBLE. Distance is uniformly drawn from integers 0 through 999;
distance > 900 matches 99 of the 1,000 possible values. Each file is headerless
ASCII with LF line endings. Rows are 28 bytes, so files end at a complete row
and fall less than 28 bytes below their decimal byte target.

The generator uses a temporary SQLite database for sorting, with disk-backed
temporary storage and a small page cache. It does not retain the full dataset
as Python objects. Allow space for both CSV orders and temporary sorting data.
The complete CSV set is approximately 1.3 GB.

The generated manifest records the seed, schema, file sizes, row counts,
expected matching counts, and SHA-256 checksums. Transfer the dataset directory
unchanged to the other machines. The runner verifies all its files before
starting a sweep. Existing output directories are never overwritten; choose
a new directory when regenerating or rerunning.

## Supply the machine's conditions

Create an environment JSON file with `hardware` and `os` descriptions filled
in by the team. Other fields are retained, so you can add notes. For example,
replace the strings in this format with the actual conditions:

```json
{
  "hardware": "CPU/chip, RAM, and storage recorded by the team",
  "os": "Operating system and version recorded by the team",
  "notes": "Other execution conditions, if relevant"
}
```

Pass that file with `--environment`. Choose the maximum heap with `--heap`;
the example below uses 2g as an explicit setting to replace with your choice.
The runner records the actual Java/compiler versions, JVM arguments, and
ambient JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, and _JAVA_OPTIONS. Additional JVM
arguments use repeated `--jvm-option=-XX:...` options. ENGINE_JAVA_OPTS belongs
to the regular engine launcher and is not used by this runner.

## Provide cache control

The runner accepts a JSON array of command arguments through `--cache-command`.
It appends absolute input-file paths and invokes the command without a shell,
after preparation and before starting the measured JVM. The command receives
the CSV for import, or the catalog and binary files for SELECT. It also receives
EXPERIMENT_WORKLOAD and EXPERIMENT_TRIAL_DIR environment variables.

The team must supply a command that controls the relevant filesystem cache on
that machine, including any required privileges, and describe its method with
`--cache-method`. Document its scope and limitations in the execution notes.
A successful exit is recorded as the team's declaration that the method ran;
the runner cannot independently prove the filesystem-cache or hardware-cache
state. A command that only prints a message would not establish cold reads.

Cache-command stdout, stderr, arguments, method description, and exit status
are preserved for each measured trial. A nonzero exit or timeout aborts the
sweep before that trial's measured JVM starts. No cache-clearing command runs
implicitly, and the tooling contains no privileged cache-reset implementation.

## Run one machine's sweep

After replacing the environment path, cache command, method description, and
heap with your team's choices:

```bash
python3 scripts/experiment.py run \
  --datasets data/experiment/datasets \
  --output data/experiment/machine-a \
  --machine-id machine-a \
  --environment data/experiment/environment-a.json \
  --heap 2g \
  --cache-command '["/absolute/path/to/your-cache-command"]' \
  --cache-method 'Description supplied by the team'
```

The default matrix is five dataset sizes, five partition sizes (256, 512, 4096,
16384, 65536), and five repetitions. Each repetition has a measured shuffled
CREATE + COPY, a measured shuffled SELECT, a preparation-only sorted import,
and a measured sorted SELECT. All four operations run in separate JVMs.
There are 375 measured samples and 125 preparation imports per machine.
Cache control runs before each of the three measured workloads.

Execution order is shuffled with a recorded seed (default 42). Subsets can be
selected with `--sizes`, `--partition-sizes`, and `--repetitions`; the run
manifest records those deviations. `--timeout` sets the per-command timeout
in seconds, default 3600. Compilation happens once before measurement.

Every import uses a new database directory. After a successful SELECT, that
trial's imported database is removed to avoid accumulating all copies of the
large datasets. Logs and metadata remain. Use `--keep-databases` if you need
the tables too; failed trial databases are retained for investigation.

## Check the pipeline without clearing caches

For a small development check, generate only 8 KB into a new directory and
explicitly allow uncontrolled filesystem caches:

```bash
python3 scripts/experiment.py generate \
  --output data/experiment/smoke-datasets --sizes 8KB
python3 scripts/experiment.py run \
  --datasets data/experiment/smoke-datasets \
  --output data/experiment/smoke-run \
  --machine-id development-check \
  --environment data/experiment/environment-a.json \
  --heap 128m --partition-sizes 256 --repetitions 1 \
  --allow-uncontrolled
data/experiment/venv/bin/python scripts/experiment.py analyze \
  data/experiment/smoke-run --output data/experiment/smoke-analysis --diagnostic
```

These samples are fresh JVMs with uncontrolled filesystem caches. They never
enter the official cold averages. Diagnostic summaries and charts have a
`diagnostic-` filename prefix and explicit diagnostic labels. They may have
fewer than five repetitions. Use `--no-plots` to check table export without
installing matplotlib.

## Analyze the measurements

For one machine:

```bash
data/experiment/venv/bin/python scripts/experiment.py analyze \
  data/experiment/machine-a --output data/experiment/analysis-a
```

For three machines, copy their entire run directories to one machine and pass
all three paths:

```bash
data/experiment/venv/bin/python scripts/experiment.py analyze \
  data/experiment/machine-a data/experiment/machine-b data/experiment/machine-c \
  --output data/experiment/comparison
```

Analysis rereads the active and rotated engine logs. CREATE + COPY is the sum
of the two Executor statement durations; SELECT uses its one statement
duration. Parsing and nested storage durations are excluded. Import and SELECT
row counts and partition counts must agree with the dataset manifest and
configured partition size. Errors or missing/duplicate summaries are rejected.
Combining runs requires the same engine commit, runtime-content fingerprint,
and benchmark script checksums, and identical checksums and counts for any
overlapping datasets. JAR fingerprints ignore ZIP timestamps and generated
Maven metadata while checking the runtime files; source fingerprints normalize
Windows/POSIX paths and CRLF/LF line endings. Machine
environments may differ and remain recorded separately.

Official summaries require at least five successful, fresh-JVM samples with
declared filesystem-cache control per point. Machine/run identifiers, input
order, dataset size, operation, partition size, and cache conditions remain
separate. Uncontrolled, failed, and undersampled records go to `excluded.csv`.
If no point qualifies, analysis saves the samples and exclusions and exits
with an error instead of producing a cold average.

The summary table has count, mean_ms, median_ms, min_ms, and max_ms. Each
machine run gets a chart with the three workload panels, exported as PDF, SVG,
and PNG. The x axis is logarithmic. A panel's y axis is logarithmic when its
positive measurement range spans at least two orders of magnitude; panels
containing zero use a linear y axis. Zero durationMs values reflect the log's
whole-millisecond resolution and are retained in the tables.

Charts show means, with whiskers from minimum to maximum. Captions identify
the conditions and repetitions. The team supplies the verdict on the
hypothesis, explanations of the results, and report reflections.

## Evidence and verification

Each run contains `run.json`, `compilation.log`, and `trials/`. Trial folders
contain `trial.json`, derived `sample.json`, `stderr.log`, the engine's `logs/`
directory including rotations, and cache-command output where applicable.
Failures leave their evidence in place. Analysis adds `samples.csv`,
`excluded.csv`, `summary.csv` (or `diagnostic-summary.csv`), `analysis.json`,
and chart exports. Generated files belong under the Git-ignored `data/` tree.

Run the script tests after packaging the engine:

```bash
PYTHONPATH=scripts data/experiment/venv/bin/python -m unittest discover -s scripts/tests -v
mvn -B verify
```

The tests use small datasets and diagnostic runs. They do not clear the
development machine's OS caches or produce final benchmark observations.

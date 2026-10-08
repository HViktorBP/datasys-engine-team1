# Experiment runner implementation plan

> For agentic workers: use superpowers:executing-plans to implement these tasks in order. Track completion with the checkboxes below.

**Goal:** Implement the reproducible cold-run experiment in `docs/experiment-design.md`.

**Architecture:** A Python CLI generates paired CSVs, launches each measured workload in a new JVM, and extracts the engine's existing log records. A separate Java launcher uses the existing configurable StorageEngine and Executor. Analysis groups qualifying cold samples and renders charts without writing the team's conclusions.

**Tech stack:** Python standard library, SQLite for bounded-memory dataset sorting, JDK 25, the packaged engine JAR, and matplotlib for exportable charts.

**Spec:** `docs/experiment-design.md`.

## Global constraints

- Partition sizes: 256, 512, 4096, 16384, 65536 rows.
- CSV targets: 8 KB, 1 MB, 50 MB, 100 MB, 500 MB in decimal units.
- Uniform distance in [0, 1000); SELECT uses distance > 900.
- Identical rows in sorted and shuffled files, with committed fixed seed defaults.
- At least five independent cold samples per point; mean, median, minimum, maximum.
- Each measured workload gets a fresh JVM. SELECT import preparation runs separately.
- Machine details and cache-control method are supplied by the team. Uncontrolled filesystem-cache samples are excluded from cold summaries.
- Preserve logs, environment, revisions, checksums, execution order, failures, and timing boundaries.
- Keep engine behavior unchanged; document the benchmark Java launcher for release 0.6, which Exercise 6 introduces after the existing 0.5 design and implementation.

## Review focus

- Large datasets must sort without retaining all rows in Python memory; generator tests check paired rows and deterministic bytes.
- An edited CSV must fail checksum validation before any measured JVM starts; runner tests cover this.
- Rotated logs and nested timing records must not cause double counting; extraction tests use both.
- Cache-hook failure must preserve evidence and abort before the measured workload; runner tests exercise a failing external command.
- Diagnostic and undersampled groups must never become primary cold averages; summary tests cover both and real JVM smoke tests verify independent processes.

## Tasks

### 1. Deterministic datasets

Files: `scripts/experiment.py`, `scripts/experiments/__init__.py`, `scripts/experiments/datasets.py`, `scripts/tests/test_datasets.py`.

Interfaces: `generate(output: Path, sizes: list[str], seed: int) -> dict`, `load_manifest(directory: Path) -> dict`, `verify_manifest(directory: Path, manifest: dict) -> None`.

- [x] Write and run failing tests for byte-for-byte regeneration, equal sorted/shuffled multisets, strict predicate counts, target sizes, and checksum rejection.
- [x] Implement a disk-backed generator with fixed-width ASCII rows and manifests recording schema, seed, row counts, file sizes, and SHA-256 checksums.
- [x] Run the dataset tests and inspect a generated small dataset.

### 2. Log extraction and cold-only summaries

Files: `scripts/experiments/results.py`, `scripts/tests/test_results.py`.

Interfaces: `extract_trial(directory: Path, metadata: dict) -> dict`, `summarize(samples: list[dict], minimum: int = 5, diagnostic: bool = False) -> tuple[list[dict], list[dict]]`, `analyze(runs: list[Path], output: Path, diagnostic: bool = False, plots: bool = True) -> list[dict]`.

- [x] Write and run failing tests for exact Executor timing extraction, rotated logs, row-count failures, error records, separate-machine grouping, and cold/diagnostic sample eligibility.
- [x] Implement strict extraction, CSV summaries with provenance, and separate excluded-sample records.
- [x] Run extraction and aggregation tests.

### 3. Independent JVM runner and cache hook

Files: `scripts/experiments/runner.py`, `scripts/experiments/artifacts.py`, `scripts/experiments/ExperimentWorkload.java`, `scripts/experiment.py`, `scripts/tests/test_runner.py`, `scripts/tests/test_artifacts.py`.

Interfaces: `run_experiment(args: argparse.Namespace) -> Path`; Java launcher accepts `import partitionRows databaseDirectory csvFile` or `select partitionRows databaseDirectory`.

- [x] Write and run failing tests for altered inputs, failed cache hooks, paths containing spaces/apostrophes, fresh JVMs, partition counts, and actual SELECT output counts.
- [x] Compile the documented Java launcher against the engine JAR before measurement; use existing Executor logging and discard stdout consistently.
- [x] Implement the complete default matrix with optional recorded subsets, seeded execution order, explicit heap/environment, a user-provided cache-command argument vector, and preserved per-trial metadata/logs.
- [x] Run runner tests against the packaged engine. Do not clear this development machine's filesystem caches.

### 4. Charts, usage, and full verification

Files: `scripts/experiments/plotting.py`, `scripts/experiment-requirements.txt`, `scripts/tests/test_plotting.py`, `docs/experiment-running.md`, `README.md`, `.github/workflows/ci.yml`, `.gitignore`.

Interface: `plot_summaries(summaries: list[dict], output: Path, diagnostic: bool = False) -> list[Path]`.

- [x] Test exported charts using a small supplied summary, including zero millisecond durations and multiple machines.
- [x] Render separate workload panels with labelled units, log axes where applicable, means and minimum/maximum whiskers, and captions. Export PDF, SVG, and PNG.
- [x] Document generation, per-machine environment/cache hooks, cold execution, diagnostic smoke runs, combining machines, output layout, and the requirement to commit scripts before final measurements.
- [x] Run all Python tests, an end-to-end diagnostic smoke experiment and analysis, private-inclusive Javadocs with `-Xdoclint:all`, and `mvn -B verify`; inspect the complete diff.

## Execution notes

- Work in the user's existing `exercise-6` checkout and leave changes reviewable without committing or publishing.
- The Java launcher lives with the experiment scripts; it adds no engine feature or public engine configuration.
- A successful cache command is the team's declared control method, not independent proof that hardware caches were cleared. Record its arguments and description.
- Full performance measurements are left for the team's three machines. Development smoke results are explicitly diagnostic and never enter the official cold summary.
- Ruling: use LONG distances uniformly drawn from 0 through 999. Binder requires exact literal types, and the agreed SQL uses the LONG literal 900. This preserves the query and approximately 10% selectivity; it uses discrete rather than continuous distances.

## Verification record

- The initial Maven baseline passed before implementation.
- Dataset, extraction, runner, and chart tests were observed failing before their implementations, then passing. The final complete Python suite passed with 21 tests, including the review regressions.
- A real diagnostic smoke sweep used 8 KB input at partition sizes 256 and 512, producing six log-derived summary points and PDF/SVG/PNG exports. No filesystem caches were cleared and these samples are excluded from official cold averages.
- Private-inclusive Javadocs for all engine sources and the benchmark launcher passed with `-Xdoclint:all`.
- `mvn -B verify` passed after implementation.
- A final real diagnostic sweep used both 8 KB and 1 MB input at partition sizes 256 and 65536, producing 12 log-derived points and PDF/SVG/PNG exports. The recorded engine runtime-content fingerprint still matched the JAR after Maven verification.

## Independent review

- Final review: read-only independent review by the code-review agent. Three Important findings were fixed in one pass, with regression tests observed failing before the fixes and passing afterward. No Critical or Minor findings were reported.
- Fixed: executed engine identity now compares runtime JAR contents, ignoring ZIP timestamps and generated Maven metadata while retaining full archive checksums.
- Fixed: source fingerprints use POSIX relative paths and normalized LF text; comparison also normalizes existing Windows path keys and excludes test-only changes consistently.
- Fixed: dataset checksum verification uses streaming SHA-256 supported on Python 3.10, without the Python 3.11-only file_digest helper.
- Final ruling: cache-hook effectiveness remains the team's documented responsibility because portable tooling cannot independently prove OS or hardware cache state. A false declaration would invalidate cold-read claims; diagnostic runs remain excluded.
- Final ruling: the full 500 MB sweep is left for the team's machines. Small real-data smoke checks validate the pipeline, not full-size runtime or resource consumption.
- Final ruling: hardware observations and interpretation of the hypothesis remain with the team, as explicitly requested.

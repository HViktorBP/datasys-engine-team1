#!/usr/bin/env bash
# Profiles one import and one SELECT to show where the engine spends its time.
#
# Each workload runs in a fresh JVM under Java Flight Recorder (JFR), exactly as the
# experiment runner launches it. The script reports, per workload:
#   - the engine's own statement durationMs from logs/engine.log;
#   - the process's user and system (kernel) CPU time, independent of the profiler;
#   - the most frequent top stack frames, separately for Java-code samples and
#     native-code samples, because JFR takes them at different intervals;
#   - the estimated share of sampled thread time spent inside RandomAccessFile calls.
#
# This is exploratory evidence, not a controlled experiment: one run per workload,
# warm filesystem cache, one machine. Evidence stays in a temporary directory.
#
# Usage: scripts/profile-io.sh [CSV] [PARTITION_ROWS]
#   CSV             defaults to data/experiment/datasets/50MB-shuffled.csv
#   PARTITION_ROWS  defaults to 4096
# Requires target/engine.jar (mvn -B package), a JDK with java, javac and jfr on PATH,
# and python3.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
csv="$(cd -- "$(dirname -- "${1:-$repo_root/data/experiment/datasets/50MB-shuffled.csv}")" && pwd)/$(basename -- "${1:-50MB-shuffled.csv}")"
partition_rows="${2:-4096}"
jar="$repo_root/target/engine.jar"
if [[ ! -f "$jar" ]]; then
    echo "Missing $jar; build it with: mvn -B package" >&2
    exit 1
fi
if [[ ! -f "$csv" ]]; then
    echo "Missing $csv; generate it with: python3 scripts/experiment.py generate --output data/experiment/datasets" >&2
    exit 1
fi

evidence="$(mktemp -d "${TMPDIR:-/tmp}/engine-io-profile.XXXXXX")"
cd -- "$evidence"
mkdir launcher
javac -d launcher -cp "$jar" "$repo_root/scripts/experiments/ExperimentWorkload.java"
classpath="launcher:$jar"
TIMEFORMAT=$'user %U\nsys %S'

# Runs one workload in a fresh JVM under JFR; CPU times go to <name>.cpu.
profile() {
    local name="$1"
    shift
    { time java -Xmx2g -XX:StartFlightRecording="filename=$name.jfr,settings=profile" \
        -cp "$classpath" ExperimentWorkload "$@" > /dev/null 2> "$name.stderr"; } 2> "$name.cpu"
    jfr print --json --events jdk.ExecutionSample,jdk.NativeMethodSample --stack-depth 1 \
        "$name.jfr" > "$name.samples.json"
}

profile import import "$partition_rows" "$evidence/database" "$csv"
profile select select "$partition_rows" "$evidence/database"

python3 -I - "$evidence" "$csv" "$partition_rows" > summary.txt <<'PY'
import collections, json, re, sys
from pathlib import Path

evidence, csv, partition_rows = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
# Sampling intervals from the JDK's profile.jfc; native code is sampled half as often.
PERIOD_MS = {"jdk.ExecutionSample": 10, "jdk.NativeMethodSample": 20}

durations = collections.defaultdict(list)
for line in (evidence / "logs" / "engine.log").read_text().splitlines():
    match = re.search(r",Executor,statement=(\w+) .*durationMs=(\d+)", line)
    if match:
        durations[match.group(1)].append(int(match.group(2)))

print(f"Input: {csv}\nRows per partition: {partition_rows}\nEvidence: {evidence}\n")
for name, statements in (("import", ("CREATE_TABLE", "COPY")), ("select", ("SELECT",))):
    cpu = dict(line.split() for line in (evidence / f"{name}.cpu").read_text().splitlines())
    user, kernel = float(cpu["user"]), float(cpu["sys"])
    engine_ms = sum(sum(durations[statement]) for statement in statements)
    print(f"== {name}: engine durationMs {engine_ms} ({' + '.join(statements)})")
    print(f"   process CPU: user {user:.2f} s, system {kernel:.2f} s "
          f"({100 * kernel / (user + kernel):.0f}% in the kernel)")

    events = json.loads((evidence / f"{name}.samples.json").read_text())["recording"]["events"]
    frames = collections.defaultdict(collections.Counter)
    for event in events:
        top = event["values"]["stackTrace"]["frames"][0]["method"]
        frames[event["type"]][f"{top['type']['name'].replace('/', '.')}.{top['name']}"] += 1
    weighted_total = weighted_io = 0
    for event_type, counter in sorted(frames.items()):
        total = sum(counter.values())
        weighted_total += total * PERIOD_MS[event_type]
        weighted_io += PERIOD_MS[event_type] * sum(
            count for frame, count in counter.items() if frame.startswith("java.io.RandomAccessFile."))
        print(f"   {event_type} (every {PERIOD_MS[event_type]} ms): {total} samples")
        for frame, count in counter.most_common(5):
            print(f"     {100 * count / total:5.1f}%  {frame}")
    if weighted_total:
        print(f"   estimated share of sampled thread time in RandomAccessFile calls: "
              f"{100 * weighted_io / weighted_total:.0f}%\n")
PY
cat summary.txt

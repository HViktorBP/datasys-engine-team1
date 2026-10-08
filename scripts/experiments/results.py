"""Extract engine-owned timings and summarize independently verified cold trials."""

import csv
import json
import math
import re
import statistics
from collections import defaultdict
from pathlib import Path

from .datasets import write_json

GROUP_FIELDS = ("machine_id", "run_id", "workload", "dataset", "input_order",
                "partition_rows", "cache_state")


def _messages(directory):
    """Read all active and rotated logs, rejecting errors and mixed sessions."""
    records = []
    sessions = set()
    paths = sorted((directory / "logs").glob("engine*.log"))
    if not paths:
        raise ValueError(f"Missing engine logs: {directory}")
    for path in paths:
        with path.open(encoding="utf-8", newline="") as stream:
            for row in csv.reader(stream):
                if len(row) != 7:
                    raise ValueError(f"Malformed engine log record in {path}")
                if row[4] == "ERROR":
                    raise ValueError(f"Engine ERROR in {path}: {row[6]}")
                sessions.add(row[1])
                fields = dict(re.findall(r"(\w+)=(\S+)", row[6]))
                records.append((row[5], fields))
    if len(sessions) != 1 or not next(iter(sessions)):
        raise ValueError(f"Expected one nonempty engine session: {directory}")
    return records


def _one(records, class_name, **fields):
    matches = [values for name, values in records if name == class_name
               and all(values.get(key) == value for key, value in fields.items())]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one {class_name} {fields} record; found {len(matches)}")
    return matches[0]


def extract_trial(directory: Path, metadata: dict) -> dict:
    """Extract statement timings and validate output and partition counts.

    Parsing and nested storage timings are never included in duration_ms.
    Each trial directory belongs to exactly one JVM, including rotated logs.
    """
    records = _messages(directory)
    expected_partitions = math.ceil(metadata["expected_import_rows"] / metadata["partition_rows"])
    sample = dict(metadata, log_directory=str(directory.resolve()))
    if metadata["workload"] == "create_copy":
        create = _one(records, "Executor", statement="CREATE_TABLE")
        copy = _one(records, "Executor", statement="COPY")
        imported = _one(records, "StorageEngine", operation="copyFile", success="true")
        if int(imported["rows"]) != metadata["expected_import_rows"]:
            raise ValueError("Imported row count does not match the dataset manifest")
        if int(imported["partitions"]) != expected_partitions:
            raise ValueError("Imported partition count does not match the configured size")
        sample.update(duration_ms=int(create["durationMs"]) + int(copy["durationMs"]),
                      rows_imported=int(imported["rows"]), rows_out=0,
                      partitions_total=expected_partitions, partitions_read="", partitions_pruned="")
    elif metadata["workload"] == "select":
        selected = _one(records, "Executor", statement="SELECT")
        stats = [values for name, values in records if name == "Executor" and "partitionsTotal" in values]
        if len(stats) != 1:
            raise ValueError("Expected exactly one SELECT partition summary")
        stats = stats[0]
        total, read, pruned = (int(stats[key]) for key in ("partitionsTotal", "partitionsRead", "partitionsPruned"))
        if total != expected_partitions or min(read, pruned) < 0 or read + pruned != total:
            raise ValueError("SELECT partition counts are inconsistent")
        if int(selected["rowsOut"]) != metadata["expected_rows_out"]:
            raise ValueError("SELECT row count does not match the dataset manifest")
        sample.update(duration_ms=int(selected["durationMs"]), rows_out=int(selected["rowsOut"]),
                      rows_imported="", partitions_total=total, partitions_read=read, partitions_pruned=pruned)
    else:
        raise ValueError(f"Unsupported workload: {metadata['workload']}")
    if sample["duration_ms"] < 0:
        raise ValueError("Negative engine duration")
    return sample


def summarize(samples: list[dict], minimum: int = 5, diagnostic: bool = False) -> tuple[list[dict], list[dict]]:
    """Group samples without mixing machines, cache conditions, or configurations."""
    if minimum < (1 if diagnostic else 5):
        raise ValueError("Cold summaries require at least five samples")
    groups, excluded = defaultdict(list), []
    for sample in samples:
        reason = None
        if sample.get("status") != "complete":
            reason = "Trial did not complete successfully"
        elif not sample.get("fresh_jvm"):
            reason = "Trial did not use a fresh JVM"
        elif sample.get("cache_state") != "controlled" and not diagnostic:
            reason = "Filesystem cache was uncontrolled"
        if reason:
            excluded.append(dict(sample, exclusion_reason=reason))
        else:
            groups[tuple(sample[field] for field in GROUP_FIELDS)].append(sample)
    summaries = []
    for key, group in sorted(groups.items()):
        if len({sample["repetition"] for sample in group}) != len(group):
            raise ValueError(f"Duplicate repetition in configuration {key}")
        if len(group) < minimum:
            reason = "Fewer than five qualifying cold repetitions" if not diagnostic else "Insufficient repetitions"
            excluded.extend(dict(sample, exclusion_reason=reason) for sample in group)
            continue
        values = [sample["duration_ms"] for sample in group]
        summaries.append(dict(zip(GROUP_FIELDS, key), count=len(values),
                              mean_ms=statistics.mean(values), median_ms=statistics.median(values),
                              min_ms=min(values), max_ms=max(values), diagnostic=diagnostic))
    return summaries, excluded


def write_csv(path, rows):
    """Write a self-describing CSV, preserving nested metadata as JSON values."""
    fields = sorted({field for row in rows for field in row})
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: json.dumps(value, sort_keys=True) if isinstance(value, (dict, list)) else value
                             for key, value in row.items()})


def _compatible_runs(conditions):
    """Reject cross-machine comparisons with different code or overlapping datasets."""
    first = conditions[0]

    def scripts(run):
        normalized = {name.replace("\\", "/"): digest for name, digest in run["script_sha256"].items()}
        return {name: digest for name, digest in normalized.items() if not name.startswith("scripts/tests/")}

    if len(conditions) > 1:
        identities = [run.get("engine_content_sha256") for run in conditions]
        if not all(identities):
            identities = [run.get("engine_jar_sha256") for run in conditions]
        if not all(identities) or len(set(identities)) != 1:
            raise ValueError("Engine artifact contents differ or cannot be verified across input runs")
    seen = {}
    for run in conditions:
        if run["engine_commit"] != first["engine_commit"]:
            raise ValueError("Engine commits differ across input runs")
        if scripts(run) != scripts(first):
            raise ValueError("Benchmark script revisions differ across input runs")
        manifest = run["dataset_manifest"]
        if manifest["schema"] != first["dataset_manifest"]["schema"]:
            raise ValueError("Dataset schemas differ across input runs")
        for dataset in manifest["datasets"]:
            for order, file in dataset["files"].items():
                key = dataset["id"], order
                signature = dataset["rows"], dataset["expected_rows_out"], file["bytes"], file["sha256"]
                if key in seen and seen[key] != signature:
                    raise ValueError(f"Dataset files differ across input runs: {key}")
                seen[key] = signature


def analyze(runs: list[Path], output: Path, diagnostic: bool = False, plots: bool = True) -> list[dict]:
    """Re-extract raw logs from one or more machine runs and export results.

    Diagnostic summaries have separate filenames and may contain uncontrolled
    or undersampled configurations. They are never written as cold summaries.
    """
    resolved = [run.resolve() for run in runs]
    if len(set(resolved)) != len(resolved):
        raise ValueError("Each input run directory must be distinct")
    output.mkdir(parents=True, exist_ok=False)
    samples, conditions = [], []
    for run in resolved:
        manifest = json.loads((run / "run.json").read_text(encoding="utf-8"))
        if manifest.get("format_version") != 1:
            raise ValueError(f"Unsupported run manifest: {run}")
        conditions.append(manifest)
        for path in sorted((run / "trials").rglob("trial.json")):
            metadata = json.loads(path.read_text(encoding="utf-8"))
            if not metadata.get("measured"):
                continue
            if metadata.get("status") == "complete":
                samples.append(extract_trial(path.parent, metadata))
            else:
                samples.append(metadata)
    if not conditions:
        raise ValueError("Provide at least one run directory")
    _compatible_runs(conditions)
    summaries, excluded = summarize(samples, minimum=1 if diagnostic else 5, diagnostic=diagnostic)
    prefix = "diagnostic-" if diagnostic else ""
    write_csv(output / "samples.csv", samples)
    write_csv(output / "excluded.csv", excluded)
    write_csv(output / f"{prefix}summary.csv", summaries)
    write_json(output / "analysis.json", {"format_version": 1, "diagnostic": diagnostic,
                                         "source_runs": list(map(str, resolved)), "conditions": conditions,
                                         "sample_count": len(samples), "summary_points": len(summaries),
                                         "excluded_count": len(excluded)})
    if not summaries:
        raise ValueError("No qualifying sample groups; raw samples and exclusions were saved. "
                         "Cold summaries need five controlled cold trials per point. "
                         "Use --diagnostic only for labelled development results.")
    if plots:
        from .plotting import plot_summaries
        plot_summaries(summaries, output, diagnostic=diagnostic)
    return summaries

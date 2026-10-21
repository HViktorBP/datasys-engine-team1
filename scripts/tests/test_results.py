"""Use hand-checked log fixtures to protect timing and sample eligibility."""

import json
import tempfile
import unittest
from pathlib import Path

from experiments.results import _compatible_runs, analyze, extract_trial, summarize


def record(class_name, message, number=1, level="DEBUG"):
    return f"2026-10-08 00:00:00.000,fixture-session,{number},1,{level},{class_name},{message}\n"


def metadata(**changes):
    result = {"trial_id": "fixture", "machine_id": "machine-a", "run_id": "run-a",
              "workload": "select", "input_order": "sorted", "dataset": "8KB",
              "partition_rows": 256, "repetition": 1, "expected_import_rows": 300,
              "expected_rows_out": 30, "cache_state": "controlled", "fresh_jvm": True,
              "measured": True, "status": "complete"}
    result.update(changes)
    return result


class ExtractionTests(unittest.TestCase):
    def test_import_sums_executor_records_across_rotated_logs(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            logs = directory / "logs"
            logs.mkdir()
            (logs / "engine-1.log").write_text(
                record("SqlParser", "statements=2 durationMs=999")
                + record("StorageEngine", "operation=createTable success=true durationMs=100")
                + record("Executor", "statement=CREATE_TABLE table=trips rowsOut=0 durationMs=3"))
            (logs / "engine.log").write_text(
                record("StorageEngine", "operation=copyFile rows=300 partitions=2 success=true durationMs=1000", 2)
                + record("Executor", "statement=COPY table=trips rowsOut=0 durationMs=12", 2))
            sample = extract_trial(directory, metadata(workload="create_copy", input_order="shuffled"))
            self.assertEqual(sample["duration_ms"], 15)
            self.assertEqual(sample["rows_imported"], 300)
            self.assertEqual(sample["partitions_total"], 2)

    def test_select_uses_statement_time_and_validates_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "logs").mkdir()
            log = directory / "logs/engine.log"
            log.write_text(record("Executor", "table=trips partitionsTotal=2 partitionsRead=1 partitionsPruned=1")
                           + record("Executor", "statement=SELECT table=trips rowsOut=30 durationMs=7"))
            sample = extract_trial(directory, metadata())
            self.assertEqual((sample["duration_ms"], sample["rows_out"], sample["partitions_pruned"]),
                             (7, 30, 1))
            with self.assertRaisesRegex(ValueError, "row count"):
                extract_trial(directory, metadata(expected_rows_out=31))
            log.write_text(log.read_text() + record("Engine", "operation=execute error=failed", level="ERROR"))
            with self.assertRaisesRegex(ValueError, "ERROR"):
                extract_trial(directory, metadata())

    def test_missing_or_duplicate_statement_summary_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "logs").mkdir()
            log = directory / "logs/engine.log"
            log.write_text(record("Executor", "table=trips partitionsTotal=2 partitionsRead=1 partitionsPruned=1"))
            with self.assertRaisesRegex(ValueError, "statement"):
                extract_trial(directory, metadata())
            summary = record("Executor", "statement=SELECT table=trips rowsOut=30 durationMs=7")
            log.write_text(log.read_text() + summary + summary)
            with self.assertRaisesRegex(ValueError, "statement"):
                extract_trial(directory, metadata())


class SummaryTests(unittest.TestCase):
    def test_only_qualifying_cold_samples_enter_mean_median_and_spread(self):
        samples = [dict(metadata(repetition=i), duration_ms=duration)
                   for i, duration in enumerate([1, 2, 3, 4, 100], 1)]
        samples.append(dict(metadata(repetition=6, cache_state="uncontrolled"), duration_ms=9000))
        samples.append(dict(metadata(repetition=7, fresh_jvm=False), duration_ms=9000))
        summaries, excluded = summarize(samples)
        self.assertEqual(len(summaries), 1)
        summary = summaries[0]
        self.assertEqual((summary["count"], summary["mean_ms"], summary["median_ms"],
                          summary["min_ms"], summary["max_ms"]), (5, 22, 3, 1, 100))
        self.assertEqual(len(excluded), 2)

    def test_machines_and_undersampled_points_stay_separate(self):
        samples = [dict(metadata(repetition=i), duration_ms=10) for i in range(1, 6)]
        samples += [dict(metadata(machine_id="machine-b", run_id="run-b", repetition=i), duration_ms=99)
                    for i in range(1, 5)]
        summaries, excluded = summarize(samples)
        self.assertEqual([(row["machine_id"], row["mean_ms"]) for row in summaries], [("machine-a", 10)])
        self.assertEqual(len(excluded), 4)
        self.assertTrue(all("five" in row["exclusion_reason"] for row in excluded))

    def test_diagnostics_are_labelled_and_duplicate_repetitions_are_rejected(self):
        sample = dict(metadata(cache_state="uncontrolled"), duration_ms=0)
        summaries, excluded = summarize([sample], minimum=1, diagnostic=True)
        self.assertEqual(summaries[0]["cache_state"], "uncontrolled")
        self.assertTrue(summaries[0]["diagnostic"])
        self.assertEqual(excluded, [])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            summarize([sample, sample], minimum=1, diagnostic=True)


class AnalysisTests(unittest.TestCase):
    def test_different_executed_engine_contents_are_rejected_even_at_the_same_commit(self):
        common = {"engine_commit": "same-checkout", "script_sha256": {},
                  "dataset_manifest": {"schema": "fixture", "datasets": []}}
        with self.assertRaisesRegex(ValueError, "Engine artifact"):
            _compatible_runs([dict(common, engine_content_sha256="old-engine"),
                              dict(common, engine_content_sha256="new-engine")])

    def test_windows_and_posix_script_paths_compare_without_test_only_changes(self):
        common = {"engine_commit": "same-checkout", "engine_content_sha256": "same-engine",
                  "dataset_manifest": {"schema": "fixture", "datasets": []}}
        _compatible_runs([
            dict(common, script_sha256={"scripts/experiments/runner.py": "same-source",
                                       "scripts/tests/test_runner.py": "old-test"}),
            dict(common, script_sha256={r"scripts\experiments\runner.py": "same-source",
                                       r"scripts\tests\test_runner.py": "new-test"})])

    def test_different_datasets_cannot_be_compared_as_the_same_workload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = []
            for index, digest in enumerate(("first-fixture-checksum", "different-fixture-checksum")):
                run = root / str(index)
                run.mkdir()
                (run / "run.json").write_text(json.dumps({
                    "format_version": 1, "engine_commit": "fixture-commit", "script_sha256": {},
                    "engine_content_sha256": "fixture-engine",
                    "dataset_manifest": {"schema": "fixture-schema", "datasets": [
                        {"id": "8KB", "rows": 300, "expected_rows_out": 30,
                         "files": {"sorted": {"sha256": digest, "bytes": 8000}}}]}}))
                runs.append(run)
            with self.assertRaisesRegex(ValueError, "Dataset.*differ"):
                analyze(runs, root / "analysis", plots=False)


if __name__ == "__main__":
    unittest.main()

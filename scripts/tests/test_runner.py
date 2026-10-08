"""Exercise the real runner and packaged engine without clearing OS caches."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from experiments.datasets import generate
from experiments.runner import _execute

REPO = Path(__file__).resolve().parents[2]
CLI = REPO / "scripts/experiment.py"


@unittest.skipUnless((REPO / "target/engine.jar").is_file(), "Run mvn -B verify before runner tests")
class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="experiment test '")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.datasets = self.root / "datasets"
        self.manifest = generate(self.datasets, ["8KB"], 42)
        self.environment = self.root / "environment.json"
        self.environment.write_text(json.dumps({"hardware": "development test fixture",
                                                "os": "test environment; not a performance observation"}))

    def invoke(self, output, *extra):
        return subprocess.run([sys.executable, str(CLI), "run", "--datasets", str(self.datasets),
                               "--output", str(output), "--machine-id", "test-machine",
                               "--environment", str(self.environment), "--heap", "64m",
                               "--sizes", "8KB", "--partition-sizes", "256", "--repetitions", "1",
                               *extra], capture_output=True, text=True, timeout=90)

    def test_actual_workloads_use_distinct_jvms_and_exclude_uncontrolled_samples(self):
        output = self.root / "run"
        result = self.invoke(output, "--allow-uncontrolled")
        self.assertEqual(result.returncode, 0, result.stderr)
        run = json.loads((output / "run.json").read_text())
        self.assertEqual(run["status"], "complete")
        trials = [json.loads(path.read_text()) for path in (output / "trials").rglob("trial.json")]
        self.assertEqual(len(trials), 4)
        self.assertEqual(len({trial["pid"] for trial in trials}), 4)
        self.assertTrue(all(trial["fresh_jvm"] and trial["status"] == "complete" for trial in trials))
        measured = [trial for trial in trials if trial["measured"]]
        self.assertEqual(len(measured), 3)
        self.assertTrue(all(trial["cache_state"] == "uncontrolled" for trial in measured))
        for trial in trials:
            self.assertTrue((Path(trial["directory"]) / "logs/engine.log").is_file())
        self.assertFalse(list((output / "trials").rglob("database")))
        self.assertTrue((self.datasets / "8KB-shuffled.csv").is_file())
        cold = subprocess.run([sys.executable, str(CLI), "analyze", str(output), "--output",
                               str(self.root / "cold"), "--no-plots"], capture_output=True, text=True)
        self.assertNotEqual(cold.returncode, 0)
        self.assertIn("No qualifying", cold.stderr)
        diagnostic = subprocess.run([sys.executable, str(CLI), "analyze", str(output), "--output",
                                     str(self.root / "diagnostic"), "--diagnostic", "--no-plots"],
                                    capture_output=True, text=True)
        self.assertEqual(diagnostic.returncode, 0, diagnostic.stderr)
        self.assertTrue((self.root / "diagnostic/diagnostic-summary.csv").is_file())
        self.assertFalse((self.root / "diagnostic/summary.csv").exists())

    def test_cache_failure_aborts_before_measured_java_and_preserves_evidence(self):
        output = self.root / "failed"
        command = json.dumps([sys.executable, "-c", "import sys; print('cache failed', file=sys.stderr); sys.exit(7)"])
        result = self.invoke(output, "--cache-command", command, "--cache-method", "failure fixture")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads((output / "run.json").read_text())["status"], "failed")
        trials = list((output / "trials").rglob("trial.json"))
        self.assertEqual(len(trials), 1)
        self.assertNotIn("pid", json.loads(trials[0].read_text()))
        self.assertIn("cache failed", (trials[0].parent / "cache.stderr").read_text())
        self.assertFalse(list((output / "trials").rglob("engine.log")))

    def test_modified_inputs_are_rejected_without_creating_run(self):
        source = self.datasets / self.manifest["datasets"][0]["files"]["sorted"]["path"]
        source.write_bytes(source.read_bytes() + b"edited")
        output = self.root / "bad"
        result = self.invoke(output, "--allow-uncontrolled")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checksum", result.stderr)
        self.assertFalse(output.exists())

    def test_cache_control_is_required_unless_diagnostics_are_explicit(self):
        output = self.root / "bad"
        result = self.invoke(output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cache", result.stderr.lower())
        self.assertFalse(output.exists())

    def test_select_cache_hook_receives_catalog_and_binary_paths_before_java(self):
        database = self.root / "prepared"
        catalog = database / "catalog/table.json"
        binary = database / "data/table.bin"
        catalog.parent.mkdir(parents=True)
        binary.parent.mkdir(parents=True)
        catalog.write_text("fixture catalog")
        binary.write_bytes(b"fixture binary")
        command = [sys.executable, "-c",
                   "import json,sys; print(json.dumps(sys.argv[1:])); sys.exit(9)"]
        settings = dict(cache_command=command, cache_method="failing path-contract fixture",
                        cache_state="controlled", timeout=10)
        trial = self.root / "cache-contract"
        with self.assertRaisesRegex(RuntimeError, "Cache command failed"):
            _execute(trial, {"workload": "select"}, database, None, settings)
        self.assertEqual(json.loads((trial / "cache.stdout").read_text()),
                         [str(catalog), str(binary)])
        self.assertNotIn("pid", json.loads((trial / "trial.json").read_text()))


if __name__ == "__main__":
    unittest.main()

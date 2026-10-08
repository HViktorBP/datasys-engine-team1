"""Check standalone chart exports, including zero-duration measurements."""

import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from experiments.plotting import plot_summaries


@unittest.skipUnless(importlib.util.find_spec("matplotlib"), "Install scripts/experiment-requirements.txt")
class PlottingTests(unittest.TestCase):
    def test_exports_each_machine_with_units_captions_and_zero_durations(self):
        rows = []
        for machine in ("machine-a", "machine-b"):
            for workload, order in (("create_copy", "shuffled"), ("select", "sorted"), ("select", "shuffled")):
                for partition, duration in ((256, 0), (512, 2)):
                    rows.append(dict(machine_id=machine, run_id="fixture", cache_state="uncontrolled",
                                     workload=workload, input_order=order, dataset="8KB", partition_rows=partition,
                                     count=1, mean_ms=duration, median_ms=duration, min_ms=duration, max_ms=duration,
                                     diagnostic=True))
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            paths = plot_summaries(rows, directory, diagnostic=True)
            self.assertEqual(len(paths), 6)
            for path in paths:
                self.assertTrue(path.name.startswith("diagnostic-"))
                self.assertGreater(path.stat().st_size, 100)
                if path.suffix == ".svg":
                    text = " ".join(ET.parse(path).getroot().itertext())
                    for label in ("DIAGNOSTIC", "Duration (ms)", "Partition size (rows)", "minimum", "uncontrolled"):
                        self.assertIn(label, text)
            self.assertEqual({path.suffix for path in paths}, {".pdf", ".png", ".svg"})


if __name__ == "__main__":
    unittest.main()

"""Check reproducibility and the data contract used by the experiment."""

import collections
import csv
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from experiments.datasets import checksum, generate, load_manifest, verify_manifest


class DatasetTests(unittest.TestCase):
    def test_checksum_works_without_the_python_311_file_digest_helper(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bytes"
            path.write_bytes(b"abc")
            with mock.patch("hashlib.file_digest", None, create=True):
                self.assertEqual(checksum(path),
                                 "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    def test_regeneration_preserves_bytes_and_sorted_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            first, second = Path(temporary) / "first", Path(temporary) / "second"
            manifest = generate(first, ["8KB"], 42)
            generate(second, ["8KB"], 42)
            dataset = manifest["datasets"][0]
            files = dataset["files"]
            for entry in files.values():
                self.assertEqual((first / entry["path"]).read_bytes(),
                                 (second / entry["path"]).read_bytes())
                self.assertLessEqual(entry["bytes"], 8000)
                self.assertGreater(entry["bytes"], 7900)
            with (first / files["shuffled"]["path"]).open() as stream:
                shuffled = list(csv.reader(stream))
            with (first / files["sorted"]["path"]).open() as stream:
                sorted_rows = list(csv.reader(stream))
            self.assertEqual(collections.Counter(map(tuple, shuffled)),
                             collections.Counter(map(tuple, sorted_rows)))
            distances = [float(row[1]) for row in sorted_rows]
            self.assertEqual(distances, sorted(distances))
            self.assertNotEqual(shuffled, sorted_rows)
            self.assertTrue(all(0 <= value < 1000 for value in distances))
            self.assertEqual(dataset["rows"], len(shuffled))
            self.assertEqual(dataset["expected_rows_out"],
                             sum(value > 900 for value in distances))
            verify_manifest(first, load_manifest(first))

    def test_changed_csv_is_rejected_before_measurement(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "datasets"
            manifest = generate(directory, ["8KB"], 42)
            file = directory / manifest["datasets"][0]["files"]["shuffled"]["path"]
            data = file.read_bytes()
            file.write_bytes(b"X" + data[1:])
            with self.assertRaisesRegex(ValueError, "checksum"):
                verify_manifest(directory, manifest)

    def test_existing_output_and_invalid_sizes_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "datasets"
            generate(directory, ["8KB"], 42)
            with self.assertRaises(FileExistsError):
                generate(directory, ["8KB"], 42)
            for size in ["0KB", "8K", "../8KB", "1TB"]:
                with self.subTest(size=size), self.assertRaises(ValueError):
                    generate(Path(temporary) / "bad", [size], 42)


if __name__ == "__main__":
    unittest.main()

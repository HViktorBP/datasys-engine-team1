"""Verify cross-machine fingerprints reflect executed code rather than packaging."""

import tempfile
import unittest
import zipfile
from pathlib import Path

from experiments.artifacts import engine_fingerprint, source_checksum


class ArtifactTests(unittest.TestCase):
    def test_source_line_endings_do_not_change_the_benchmark_fingerprint(self):
        with tempfile.TemporaryDirectory() as temporary:
            first, second = Path(temporary) / "posix.py", Path(temporary) / "windows.py"
            first.write_bytes(b"first\nsecond\n")
            second.write_bytes(b"first\r\nsecond\r\n")
            self.assertEqual(source_checksum(first), source_checksum(second))

    def test_engine_fingerprint_ignores_zip_timestamps_but_detects_changed_bytecode(self):
        with tempfile.TemporaryDirectory() as temporary:
            archives = []
            for index, bytecode in enumerate((b"engine-v1", b"engine-v1", b"engine-v2")):
                path = Path(temporary) / f"engine-{index}.jar"
                with zipfile.ZipFile(path, "w") as archive:
                    entry = zipfile.ZipInfo("dk/itu/datasys/StorageEngine.class", (2020 + index, 1, 1, 0, 0, 0))
                    archive.writestr(entry, bytecode)
                    archive.writestr("log4j2.xml", b"same logging configuration")
                    archive.writestr("META-INF/maven/example/pom.properties", f"generated {index}".encode())
                archives.append(path)
            self.assertEqual(engine_fingerprint(archives[0]), engine_fingerprint(archives[1]))
            self.assertNotEqual(engine_fingerprint(archives[0]), engine_fingerprint(archives[2]))


if __name__ == "__main__":
    unittest.main()

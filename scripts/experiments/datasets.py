"""Generate identical randomly ordered and sorted CSVs using disk-backed sorting."""

import hashlib
import json
import random
import re
import sqlite3
import tempfile
from pathlib import Path

DEFAULT_SIZES = ["8KB", "1MB", "50MB", "100MB", "500MB"]
PARTITION_SIZES = [256, 512, 4096, 16384, 65536]
DEFAULT_SEED = 42
SCHEMA = "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);"
ROW_BYTES = 28


def write_json(path, value):
    """Publish one JSON document without exposing a partial final file."""
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def checksum(path):
    """Return a streaming SHA-256 digest."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def dataset_path(directory, relative):
    """Resolve a manifest filename without permitting paths outside its directory."""
    root = directory.resolve()
    candidate = (root / relative).resolve()
    if Path(relative).is_absolute() or candidate.parent != root:
        raise ValueError(f"Dataset path must name a file inside {root}: {relative}")
    return candidate


def size_bytes(label):
    """Interpret the experiment's decimal KB and MB size labels."""
    match = re.fullmatch(r"([1-9][0-9]*)(KB|MB)", label)
    if not match:
        raise ValueError(f"Invalid dataset size {label!r}; use a positive integer followed by KB or MB")
    return int(match[1]) * {"KB": 1000, "MB": 1_000_000}[match[2]]


def _export(connection, directory, label, order):
    """Stream the same stored records in either seeded random order or distance order."""
    filename = f"{label}-{order}.csv"
    ordering = "shuffle_key, row_id" if order == "shuffled" else "distance, row_id"
    digest = hashlib.sha256()
    count = 0
    with (directory / filename).open("wb", buffering=1024 * 1024) as output:
        for city, distance, price in connection.execute(
                f"SELECT city, distance, price FROM trips ORDER BY {ordering}"):
            row = (f"City{city:02d},{distance:010d},"
                   f"{price // 100:06d}.{price % 100:02d}\n").encode("ascii")
            output.write(row)
            digest.update(row)
            count += len(row)
    return {"path": filename, "bytes": count, "sha256": digest.hexdigest()}


def generate(output: Path, sizes: list[str], seed: int) -> dict:
    """Generate paired datasets, refusing to overwrite existing experiment data.

    Rows have fixed width, so each file is at most 27 bytes below its target.
    Distances are uniform integers in [0, 1000). SQLite stores and
    sorts the records on disk instead of building a full Python list.
    """
    if not sizes or len(set(sizes)) != len(sizes):
        raise ValueError("Choose at least one distinct dataset size")
    targets = [(label, size_bytes(label)) for label in sizes]
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"format_version": 1, "seed": seed, "schema": SCHEMA,
                "row_bytes": ROW_BYTES, "distance_unit": "integer",
                "datasets": []}
    for label, target in targets:
        rows = target // ROW_BYTES
        rng = random.Random(f"{seed}:{label}")
        matches = 0
        with tempfile.TemporaryDirectory(prefix="sort-", dir=output) as temporary:
            connection = sqlite3.connect(Path(temporary) / "rows.sqlite")
            try:
                connection.execute("PRAGMA journal_mode=OFF")
                connection.execute("PRAGMA temp_store=FILE")
                connection.execute("PRAGMA cache_size=-8192")
                connection.execute("CREATE TABLE trips (row_id INTEGER PRIMARY KEY, city INTEGER, "
                                   "distance INTEGER, price INTEGER, shuffle_key INTEGER)")
                for start in range(0, rows, 10_000):
                    batch = []
                    for row_id in range(start, min(start + 10_000, rows)):
                        city = rng.randrange(10)
                        distance = rng.randrange(1000)
                        price = rng.randrange(1_000_000)
                        batch.append((row_id, city, distance, price, rng.getrandbits(63)))
                        matches += distance > 900
                    connection.executemany("INSERT INTO trips VALUES (?, ?, ?, ?, ?)", batch)
                connection.commit()
                files = {order: _export(connection, output, label, order)
                         for order in ("shuffled", "sorted")}
            finally:
                connection.close()
        manifest["datasets"].append({"id": label, "target_bytes": target,
                                     "rows": rows, "expected_rows_out": matches, "files": files})
        print(f"Generated {label}: {rows} rows; {matches} match distance > 900", flush=True)
    write_json(output / "manifest.json", manifest)
    return manifest


def load_manifest(directory: Path) -> dict:
    """Read the versioned dataset manifest consumed by the workload launcher."""
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("format_version") != 1 or manifest.get("schema") != SCHEMA:
        raise ValueError("Unsupported dataset manifest format or schema")
    datasets = manifest.get("datasets", [])
    if not datasets or len({item["id"] for item in datasets}) != len(datasets):
        raise ValueError("Dataset manifest must contain distinct datasets")
    for item in datasets:
        size_bytes(item["id"])
        if not 0 <= item["expected_rows_out"] <= item["rows"] or item["rows"] < 1:
            raise ValueError("Invalid dataset row counts")
        if set(item["files"]) != {"shuffled", "sorted"}:
            raise ValueError("Each dataset needs both input orders")
    return manifest


def verify_manifest(directory: Path, manifest: dict) -> None:
    """Reject missing, resized, or edited CSVs before starting the experiment."""
    for item in manifest["datasets"]:
        for entry in item["files"].values():
            path = dataset_path(directory, entry["path"])
            if path.stat().st_size != entry["bytes"] or checksum(path) != entry["sha256"]:
                raise ValueError(f"Dataset checksum or size mismatch: {path}")

#!/usr/bin/env python3
"""Generate the fixed sorted and shuffled inputs specified by the experiment design."""

import argparse
from pathlib import Path
import random


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="directory for the generated CSV files")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    ids = list(range(65_536))
    for name in ("sorted", "shuffled"):
        if name == "shuffled":
            random.Random(2026).shuffle(ids)
        with (args.output / f"{name}.csv").open("w", encoding="ascii", newline="") as output:
            for row_id in ids:
                output.write(f"{row_id},row_{row_id},{row_id / 10:.1f}\n")


if __name__ == "__main__":
    main()

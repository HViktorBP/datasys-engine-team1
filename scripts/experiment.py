#!/usr/bin/env python3
"""Generate, run, and analyze the Exercise 6 experiment."""

import argparse
import subprocess
import sys
from pathlib import Path

from experiments.datasets import DEFAULT_SEED, DEFAULT_SIZES, PARTITION_SIZES, generate
from experiments.results import analyze
from experiments.runner import REPO, run_experiment


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest="command", required=True)
    generator = commands.add_parser("generate", help="Create reproducible paired CSV datasets")
    generator.add_argument("--output", type=Path, required=True)
    generator.add_argument("--sizes", nargs="+", default=DEFAULT_SIZES)
    generator.add_argument("--seed", type=int, default=DEFAULT_SEED)
    runner = commands.add_parser("run", help="Run independent JVM trials on one machine")
    runner.add_argument("--datasets", type=Path, required=True)
    runner.add_argument("--output", type=Path, required=True)
    runner.add_argument("--machine-id", required=True)
    runner.add_argument("--environment", type=Path, required=True, help="Team-supplied JSON with hardware and os")
    runner.add_argument("--heap", required=True, help="Maximum JVM heap, e.g. 128m or 2g")
    runner.add_argument("--jvm-option", action="append", default=[], help="Repeat as --jvm-option=-XX:...")
    runner.add_argument("--java", default="java")
    runner.add_argument("--javac")
    runner.add_argument("--jar", type=Path, default=REPO / "target/engine.jar")
    runner.add_argument("--sizes", nargs="+")
    runner.add_argument("--partition-sizes", type=int, nargs="+", default=PARTITION_SIZES)
    runner.add_argument("--repetitions", type=int, default=5)
    runner.add_argument("--order-seed", type=int, default=DEFAULT_SEED)
    runner.add_argument("--timeout", type=float, default=3600, help="Maximum seconds per external command")
    runner.add_argument("--cache-command", help="JSON argument array; input file paths are appended, without a shell")
    runner.add_argument("--cache-method", help="Team's description of what its cache command controls")
    runner.add_argument("--allow-uncontrolled", action="store_true", help="Record diagnostic runs without clearing caches")
    runner.add_argument("--keep-databases", action="store_true", help="Retain imported databases after successful SELECTs")
    analysis = commands.add_parser("analyze", help="Extract logs and produce summaries and charts")
    analysis.add_argument("runs", type=Path, nargs="+")
    analysis.add_argument("--output", type=Path, required=True)
    analysis.add_argument("--diagnostic", action="store_true", help="Labelled summaries permitting uncontrolled/small runs")
    analysis.add_argument("--no-plots", action="store_true", help="Export tables without requiring matplotlib")
    return cli


def main():
    args = parser().parse_args()
    try:
        if args.command == "generate":
            generate(args.output, args.sizes, args.seed)
            print(f"Dataset manifest: {args.output / 'manifest.json'}")
        elif args.command == "run":
            output = run_experiment(args)
            print(f"Run evidence: {output}")
        else:
            summaries = analyze(args.runs, args.output, diagnostic=args.diagnostic, plots=not args.no_plots)
            print(f"Saved {len(summaries)} summary points to {args.output}")
        return 0
    except (ValueError, OSError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Experiment error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

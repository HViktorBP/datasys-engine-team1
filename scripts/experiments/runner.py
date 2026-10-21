"""Launch independent JVM workloads and retain enough evidence to audit each trial."""

import itertools
import json
import os
import random
import re
import shutil
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .artifacts import engine_fingerprint, source_checksum
from .datasets import (DEFAULT_SIZES, PARTITION_SIZES, checksum, dataset_path,
                       load_manifest, verify_manifest, write_json)
from .results import extract_trial

REPO = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name("ExperimentWorkload.java")


def _capture(command):
    return subprocess.run(command, cwd=REPO, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()


def _now():
    return datetime.now(timezone.utc).isoformat()


def _execute(directory, metadata, database, source_csv, settings, prepare=False):
    """Run a cache hook, then one JVM, preserving partial evidence on failure."""
    directory.mkdir(parents=True, exist_ok=False)
    metadata.update(directory=str(directory), status="pending", fresh_jvm=True, started_at=_now(),
                    measured=not prepare, cache_state="preparation" if prepare else settings["cache_state"])
    write_json(directory / "trial.json", metadata)
    process = None
    try:
        if not prepare and settings["cache_command"]:
            targets = [source_csv] if metadata["workload"] == "create_copy" else sorted(
                path for path in database.rglob("*") if path.is_file())
            if not targets:
                raise ValueError("Cache hook has no input files to control")
            command = settings["cache_command"] + list(map(str, targets))
            metadata.update(cache_command=command, cache_method=settings["cache_method"])
            write_json(directory / "trial.json", metadata)
            environment = dict(os.environ, EXPERIMENT_WORKLOAD=metadata["workload"],
                               EXPERIMENT_TRIAL_DIR=str(directory))
            with (directory / "cache.stdout").open("wb") as out, (directory / "cache.stderr").open("wb") as err:
                cached = subprocess.run(command, cwd=directory, env=environment,
                                        stdout=out, stderr=err, timeout=settings["timeout"])
            metadata["cache_returncode"] = cached.returncode
            if cached.returncode:
                raise RuntimeError(f"Cache command failed with exit {cached.returncode}; see {directory}")
        mode = "import" if metadata["workload"] == "create_copy" else "select"
        command = [settings["java"], *settings["jvm_options"], "-cp", settings["classpath"],
                   "ExperimentWorkload", mode, str(metadata["partition_rows"]), str(database)]
        if mode == "import":
            command.append(str(source_csv))
        metadata["command"] = command
        with (directory / "stderr.log").open("wb") as err:
            process = subprocess.Popen(command, cwd=directory, stdout=subprocess.DEVNULL, stderr=err)
            metadata["pid"] = process.pid
            write_json(directory / "trial.json", metadata)
            metadata["returncode"] = process.wait(timeout=settings["timeout"])
        if metadata["returncode"]:
            raise RuntimeError(f"Java workload failed with exit {metadata['returncode']}; see {directory}")
        metadata["status"] = "complete"
        sample = extract_trial(directory, metadata)
        write_json(directory / "sample.json", sample)
    except BaseException as error:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()
        metadata.update(status="failed", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        metadata["finished_at"] = _now()
        write_json(directory / "trial.json", metadata)


def run_experiment(args) -> Path:
    """Run the requested matrix; cache commands are supplied explicitly by the team."""
    datasets = args.datasets.resolve()
    manifest = load_manifest(datasets)
    verify_manifest(datasets, manifest)
    if not args.machine_id.strip():
        raise ValueError("Provide a nonempty machine identifier")
    environment = json.loads(args.environment.read_text(encoding="utf-8"))
    if not isinstance(environment, dict) or any(
            not isinstance(environment.get(field), str) or not environment[field].strip()
            for field in ("hardware", "os")):
        raise ValueError("Environment JSON must supply nonempty hardware and os descriptions from the team")
    if not re.fullmatch(r"[1-9][0-9]*[kKmMgG]?", args.heap):
        raise ValueError("Heap must be a positive JVM size, such as 128m or 2g")
    if args.repetitions < 1 or args.timeout <= 0:
        raise ValueError("Repetition count and timeout must be positive")
    if not args.partition_sizes or any(size < 1 or size > 2_147_483_647 for size in args.partition_sizes):
        raise ValueError("Partition sizes must be positive Java integers")
    if len(set(args.partition_sizes)) != len(args.partition_sizes):
        raise ValueError("Partition sizes must be distinct")
    if any(option.startswith("-Xmx") for option in args.jvm_option):
        raise ValueError("Choose the maximum heap with --heap, not an additional -Xmx option")
    cache_command = json.loads(args.cache_command) if args.cache_command else None
    if cache_command is not None:
        if (not isinstance(cache_command, list) or not cache_command
                or any(not isinstance(part, str) or not part for part in cache_command)):
            raise ValueError("Cache command must be a nonempty JSON array of command arguments")
        if not args.cache_method or not args.cache_method.strip():
            raise ValueError("Describe the cache-control method with --cache-method")
        if args.allow_uncontrolled:
            raise ValueError("Use either a cache command or --allow-uncontrolled")
    elif not args.allow_uncontrolled:
        raise ValueError("Cold runs need --cache-command and --cache-method; use --allow-uncontrolled only for diagnostics")
    elif args.cache_method:
        raise ValueError("A cache method requires an actual --cache-command")
    available = {item["id"]: item for item in manifest["datasets"]}
    sizes = args.sizes if args.sizes is not None else list(available)
    if not sizes or len(set(sizes)) != len(sizes) or any(size not in available for size in sizes):
        raise ValueError("Select distinct dataset sizes present in the dataset manifest")
    jar = args.jar.resolve()
    if not jar.is_file():
        raise ValueError(f"Missing engine JAR: {jar}; run mvn -B verify first")
    java = shutil.which(args.java)
    if not java:
        raise ValueError(f"Java executable not found: {args.java}")
    java = str(Path(java).resolve())
    javac = shutil.which(args.javac) if args.javac else str(Path(java).with_name("javac"))
    jvm_version = _capture([java, "-version"])
    version = re.search(r'version "(\d+)', jvm_version)
    if not version or int(version[1]) < 25:
        raise ValueError("The experiment requires JDK 25 or newer")
    compiler_version = _capture([javac, "-version"])
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "launcher"
    classes.mkdir()
    run_id = uuid.uuid4().hex
    jvm_options = [f"-Xmx{args.heap}", *args.jvm_option]
    jobs = list(itertools.product(sizes, args.partition_sizes, range(1, args.repetitions + 1)))
    random.Random(args.order_seed).shuffle(jobs)
    source_hashes = {path.relative_to(REPO).as_posix(): source_checksum(path)
                     for path in sorted((REPO / "scripts").rglob("*"))
                     if path.suffix in (".py", ".java") and path.is_file()}
    run = {"format_version": 1, "run_id": run_id, "machine_id": args.machine_id,
           "status": "running", "started_at": _now(), "environment": environment,
           "jvm_version": jvm_version, "compiler_version": compiler_version,
           "heap": args.heap, "jvm_options": jvm_options,
           "ambient_java_options": {key: os.environ.get(key, "")
                                    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")},
           "engine_commit": _capture(["git", "rev-parse", "HEAD"]),
           "working_tree_status": _capture(["git", "status", "--porcelain"]),
           "engine_jar": str(jar), "engine_jar_sha256": checksum(jar),
           "engine_content_sha256": engine_fingerprint(jar), "script_sha256": source_hashes,
           "script_fingerprint_format": "SHA-256 of LF-normalized text; POSIX relative path keys",
           "dataset_manifest": manifest, "dataset_directory": str(datasets),
           "cache_command": cache_command, "cache_method": args.cache_method,
           "cache_state": "controlled" if cache_command else "uncontrolled",
           "cache_evidence": "Team-declared method and successful command exit; hardware caches are not verified",
           "stdout_destination": "operating-system null device", "logging": "packaged engine configuration",
           "order_seed": args.order_seed, "execution_order": [list(job) for job in jobs],
           "repetitions": args.repetitions, "partition_sizes": args.partition_sizes, "sizes": sizes,
           "retain_successful_databases": args.keep_databases,
           "experiment_design_sha256": checksum(REPO / "docs/experiment-design.md"),
           "deviations": {"dataset_subset": sizes != DEFAULT_SIZES,
                          "partition_subset": args.partition_sizes != PARTITION_SIZES,
                          "fewer_than_five_repetitions": args.repetitions < 5,
                          "uncontrolled_filesystem_cache": not bool(cache_command)},
           "timing": "Executor statement durationMs; excludes JVM startup and StorageEngine construction"}
    write_json(output / "run.json", run)
    settings = dict(java=java, jvm_options=jvm_options, classpath=os.pathsep.join([str(classes), str(jar)]),
                    cache_command=cache_command, cache_method=args.cache_method,
                    cache_state=run["cache_state"], timeout=args.timeout)
    try:
        shutil.copyfile(SOURCE, classes / SOURCE.name)
        with (output / "compilation.log").open("wb") as log:
            subprocess.run([javac, "-cp", str(jar), "-d", str(classes), str(classes / SOURCE.name)],
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=args.timeout)
        for index, (size, partition_rows, repetition) in enumerate(jobs):
            dataset = available[size]
            print(f"[{index + 1}/{len(jobs)}] {size}, partition={partition_rows}, repetition={repetition}", flush=True)
            cell = output / "trials" / f"{index:05d}-{size}-{partition_rows}-r{repetition}"
            for order in ("shuffled", "sorted"):
                source_csv = dataset_path(datasets, dataset["files"][order]["path"])
                database = cell / order / "database"
                base = {"machine_id": args.machine_id, "run_id": run_id, "dataset": size,
                        "dataset_sha256": dataset["files"][order]["sha256"], "input_order": order,
                        "partition_rows": partition_rows, "repetition": repetition,
                        "expected_import_rows": dataset["rows"], "expected_rows_out": dataset["expected_rows_out"]}
                for role, workload, prepare in (("import", "create_copy", order == "sorted"),
                                                ("select", "select", False)):
                    directory = cell / order / role
                    metadata = dict(base, workload=workload, trial_id=f"{index:05d}-{order}-{role}")
                    _execute(directory, metadata, database, source_csv, settings, prepare=prepare)
                    if role == "select" and not args.keep_databases:
                        shutil.rmtree(database)
                        metadata["database_removed_after_successful_select"] = True
                        write_json(directory / "trial.json", metadata)
        run["status"] = "complete"
    except BaseException as error:
        run.update(status="failed", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        run["finished_at"] = _now()
        write_json(output / "run.json", run)
    return output

"""Export the predeclared experiment charts without interpreting their results."""

import hashlib
import os
import re
import textwrap
from collections import defaultdict
from pathlib import Path

PANELS = (("create_copy", "shuffled", "CREATE + COPY (shuffled)"),
          ("select", "sorted", "SELECT (sorted)"),
          ("select", "shuffled", "SELECT (shuffled)"))


def plot_summaries(summaries: list[dict], output: Path, diagnostic: bool = False) -> list[Path]:
    """Export PDF, PNG, and SVG charts separately for each machine run and cache state."""
    os.environ.setdefault("MPLCONFIGDIR", str(output.resolve() / ".matplotlib"))
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError as error:
        raise RuntimeError("Chart export needs matplotlib. Install scripts/experiment-requirements.txt "
                           "or use analyze --no-plots to export tables only.") from error
    groups = defaultdict(list)
    for row in summaries:
        groups[(row["machine_id"], row["run_id"], row["cache_state"])].append(row)
    exported = []
    with matplotlib.rc_context({"svg.fonttype": "none", "font.size": 10}):
        for (machine, run_id, cache_state), rows in sorted(groups.items()):
            fig, axes = plt.subplots(1, 3, figsize=(18, 6.8))
            try:
                datasets = sorted({row["dataset"] for row in rows}, key=_dataset_sort_key)
                colors = {label: f"C{index}" for index, label in enumerate(datasets)}
                for ax, (workload, order, title) in zip(axes, PANELS):
                    panel = [row for row in rows if row["workload"] == workload and row["input_order"] == order]
                    for label in datasets:
                        points = sorted((row for row in panel if row["dataset"] == label),
                                        key=lambda row: row["partition_rows"])
                        if not points:
                            continue
                        ax.errorbar([row["partition_rows"] for row in points],
                                    [row["mean_ms"] for row in points],
                                    yerr=[[row["mean_ms"] - row["min_ms"] for row in points],
                                          [row["max_ms"] - row["mean_ms"] for row in points]],
                                    marker="o", capsize=3, label=label, color=colors[label])
                    ax.set_title(title)
                    ax.set_xlabel("Partition size (rows)")
                    ax.set_ylabel("Duration (ms)")
                    ax.set_xscale("log", base=2)
                    ticks = sorted({row["partition_rows"] for row in panel})
                    if ticks:
                        ax.set_xticks(ticks, [f"{tick:,}" for tick in ticks], rotation=40)
                        low, high = min(row["min_ms"] for row in panel), max(row["max_ms"] for row in panel)
                        if low > 0 and high / low >= 100:
                            ax.set_yscale("log")
                        else:
                            ax.set_ylim(bottom=0)
                        ax.legend(title="Dataset size")
                    else:
                        ax.text(0.5, 0.5, "No qualifying samples", ha="center", transform=ax.transAxes)
                    ax.grid(True, which="both", alpha=0.25)
                prefix = "diagnostic-" if diagnostic else ""
                heading = "DIAGNOSTIC: " if diagnostic else ""
                fig.suptitle(f"{heading}{machine} | run {run_id}")
                counts = sorted({row["count"] for row in rows})
                repetitions = ", ".join(map(str, counts))
                caption = (f"Each curve represents the dataset size in its legend. Points are arithmetic means; "
                           f"whiskers show minimum and maximum. Repetitions per point: {repetitions} "
                           f"(see summary table). Every sample uses a fresh JVM; filesystem cache: {cache_state}. "
                           f"Operation and input order are shown above each panel. "
                           f"Timings are engine statement durationMs, excluding startup and catalog loading.")
                if any(row["min_ms"] == 0 for row in rows):
                    caption += " Zero values reflect whole-millisecond log resolution; panels containing zero use a linear y axis."
                if diagnostic:
                    caption += " Development diagnostics only; these are not the final cold experiment results."
                fig.text(0.04, 0.025, textwrap.fill(caption, 170), ha="left", va="bottom", fontsize=9)
                fig.tight_layout(rect=(0, 0.25, 1, 0.92))
                slug = re.sub(r"[^A-Za-z0-9_.-]+", "-", f"{machine}-{run_id}")[:100]
                digest = hashlib.sha256(f"{machine}\0{run_id}\0{cache_state}".encode()).hexdigest()[:8]
                for extension in ("pdf", "svg", "png"):
                    path = output / f"{prefix}{slug}-{digest}.{extension}"
                    fig.savefig(path, dpi=180)
                    exported.append(path)
            finally:
                plt.close(fig)
    return exported


def _dataset_sort_key(label):
    """Order the experiment's size labels by decimal byte size."""
    from .datasets import size_bytes
    return size_bytes(label)

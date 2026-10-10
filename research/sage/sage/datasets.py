"""Adapters for inspected SocioPatterns documentation; no guessed formats."""
from __future__ import annotations
import gzip
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile
import numpy as np
import pandas as pd

SOURCES = {
    "hypertext": ("ht2009_contact_list.dat.gz", 3, "conference"),
    "sfhh": ("SFHH_tij.dat.gz", 3, "conference"),
    "school": ("HighSchool2013_proximity_net.csv.gz", 5, "school"),
    "workplace": ("workplace_InVS_tij.dat.zip", 3, "workplace"),
}

def download(name: str, root: Path) -> Path:
    filename = SOURCES[name][0]
    path = root / name / filename
    path.parent.mkdir(parents=True, exist_ok=True)
    url = "https://sociopatterns.org/assets/data/" + filename
    if not path.exists():
        with urllib.request.urlopen(url, timeout=90) as response:
            content = response.read(30_000_001)
        if len(content) > 30_000_000:
            raise ValueError("Download exceeds the 30 MB pilot limit")
        path.write_bytes(content)
    (path.parent / "source.json").write_text(json.dumps({
        "url": url, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "format": "20-second end-stamped contacts; consult DATASETS.md for license"
    }, indent=2), encoding="utf-8")
    return path

def load(name: str, path: Path) -> pd.DataFrame:
    _, width, context = SOURCES[name]
    if path.suffix == ".gz":
        raw = gzip.decompress(path.read_bytes())
    elif path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            members = [n for n in archive.namelist() if n.endswith(".dat")]
            if len(members) != 1:
                raise ValueError("Expected exactly one .dat contact file")
            raw = archive.read(members[0])  # Never extract archive paths.
    else:
        raw = path.read_bytes()
    frame = pd.read_csv(io.BytesIO(raw), sep=r"\s+", header=None, dtype=str)
    if frame.shape[1] != width:
        raise ValueError(f"{name}: expected {width} columns, got {frame.shape[1]}")
    end = pd.to_numeric(frame[0], errors="raise").astype(float)
    a, b = frame[1].str.strip(), frame[2].str.strip()
    result = pd.DataFrame({"timestamp_start": end - 20, "timestamp_end": end,
        "node_a": np.minimum(a, b), "node_b": np.maximum(a, b),
        "dataset": name, "context": context})
    validate(result)
    return result.drop_duplicates().sort_values(
        ["timestamp_start", "node_a", "node_b"]).reset_index(drop=True)

def validate(frame: pd.DataFrame) -> dict:
    required = {"timestamp_start", "timestamp_end", "node_a", "node_b", "dataset", "context"}
    if not required.issubset(frame.columns) or frame.empty:
        raise ValueError("Missing fields or empty contact trace")
    if frame[list(required)].isna().any().any():
        raise ValueError("Null contact fields")
    times = frame[["timestamp_start", "timestamp_end"]].to_numpy(dtype=float)
    if not np.isfinite(times).all() or (times[:, 1] <= times[:, 0]).any():
        raise ValueError("Nonfinite timestamps or nonpositive durations")
    for col in ("node_a", "node_b"):
        if not frame[col].map(lambda x: isinstance(x, str) and bool(x) and x == x.strip()).all():
            raise ValueError("IDs must be nonempty normalized strings")
    if (frame.node_a == frame.node_b).any():
        raise ValueError("Self contact")
    nodes = pd.concat([frame.node_a, frame.node_b])
    n = nodes.nunique()
    duration = float(times[:, 1].max() - times[:, 0].min())
    return {"nodes": int(n), "encounters": len(frame), "trace_start": float(times[:, 0].min()),
        "trace_end": float(times[:, 1].max()), "duration_seconds": duration,
        "median_contact_bin_seconds": float(np.median(times[:, 1] - times[:, 0])),
        "encounter_density_per_pair_second": len(frame) / (n * (n - 1) / 2 * duration),
        "encounters_per_node": {str(k): int(v) for k, v in nodes.value_counts().items()}}

def pilot(frame: pd.DataFrame, max_nodes: int = 100, seconds: int = 14400) -> pd.DataFrame:
    """Deterministic ID subset, not selected by future degree/delivery success."""
    nodes = sorted(set(frame.node_a) | set(frame.node_b))[:max_nodes]
    t0 = frame.timestamp_start.min()
    result = frame[frame.node_a.isin(nodes) & frame.node_b.isin(nodes)
        & (frame.timestamp_end <= t0 + seconds)].copy()
    result[["timestamp_start", "timestamp_end"]] -= t0
    validate(result)
    return result.reset_index(drop=True)

"""Registry of public contact traces, verified parsers and episode construction.

Every format below was checked against the source documentation and the
downloaded files (see DATASETS.md). Nothing is downloaded that needs an
account or licence click-through.
"""
from __future__ import annotations
from dataclasses import dataclass, field
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import urllib.request
import zipfile
import numpy as np
import pandas as pd
from ..datasets import validate

SP = "https://sociopatterns.org/assets/data/"


@dataclass(frozen=True)
class Source:
    name: str
    filename: str
    url: str
    kind: str            # tij | tgz_days | copenhagen
    columns: int         # expected whitespace columns for tij
    family: str          # domain family used for splits
    bin_seconds: int
    licence: str
    citation: str


SOURCES = {s.name: s for s in [
    Source("hypertext", "ht2009_contact_list.dat.gz", SP + "ht2009_contact_list.dat.gz", "tij", 3,
           "conference", 20, "CC BY-NC-SA", "Isella et al., J. Theor. Biol. 271 (2011)"),
    Source("sfhh", "SFHH_tij.dat.gz", SP + "SFHH_tij.dat.gz", "tij", 3,
           "conference", 20, "CC0 public domain", "Genois & Barrat, EPJ Data Sci. 7, 11 (2018)"),
    Source("school", "HighSchool2013_proximity_net.csv.gz", SP + "HighSchool2013_proximity_net.csv.gz", "tij", 5,
           "school", 20, "CC BY-NC-SA", "Mastrandrea et al., PLoS ONE 10(9) e0136497 (2015)"),
    Source("highschool2011", "highschool_2011.csv.gz", SP + "highschool_2011.csv.gz", "tij", 5,
           "school", 20, "CC BY-NC-SA", "Fournet & Barrat, PLoS ONE 9(9) e107878 (2014)"),
    Source("highschool2012", "highschool_2012.csv.gz", SP + "highschool_2012.csv.gz", "tij", 5,
           "school", 20, "CC BY-NC-SA", "Fournet & Barrat, PLoS ONE 9(9) e107878 (2014)"),
    Source("primaryschool", "primaryschool.csv.gz", SP + "primaryschool.csv.gz", "tij", 5,
           "school", 20, "CC BY-NC-SA", "Stehle et al., PLoS ONE 6(8) e23176 (2011); Gemmetto et al. 2014"),
    Source("workplace", "workplace_InVS_tij.dat.zip", SP + "workplace_InVS_tij.dat.zip", "tij", 3,
           "workplace", 20, "CC0 public domain", "Genois et al., Network Science 3, 326 (2015)"),
    Source("workplace2015", "workplace_InVS15_tij.dat.gz", SP + "workplace_InVS15_tij.dat.gz", "tij", 3,
           "workplace", 20, "CC0 public domain", "Genois & Barrat, EPJ Data Sci. 7, 11 (2018)"),
    Source("hospital", "hospital_lyon_contacts.dat.gz", SP + "hospital_lyon_contacts.dat.gz", "tij", 5,
           "hospital", 20, "CC BY-NC-SA", "Vanhems et al., PLoS ONE 8(9) e73970 (2013)"),
    Source("infectious", "sciencegallery_infectious_contacts.tgz", SP + "sciencegallery_infectious_contacts.tgz",
           "tgz_days", 3, "exhibition", 20, "CC BY-NC-SA", "Isella et al., J. Theor. Biol. 271 (2011)"),
    Source("copenhagen", "bt_symmetric.csv", "https://ndownloader.figshare.com/files/14000795", "copenhagen", 4,
           "campus_bluetooth", 300, "MIT", "Sapiezynski et al., Scientific Data 6, 315 (2019)"),
]}

MAX_DOWNLOAD = 120_000_000


def download(name: str, root: Path) -> Path:
    src = SOURCES[name]
    path = root / name / src.filename
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with urllib.request.urlopen(src.url, timeout=300) as response:
            content = response.read(MAX_DOWNLOAD + 1)
        if len(content) > MAX_DOWNLOAD:
            raise ValueError(f"{name}: download exceeds {MAX_DOWNLOAD} bytes")
        path.write_bytes(content)
    meta = path.parent / "source.json"
    if not meta.exists():
        meta.write_text(json.dumps({"url": src.url, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "licence": src.licence, "citation": src.citation}, indent=2), encoding="utf-8")
    return path


def _frame(t_end, a, b, bin_seconds, name, family):
    a, b = np.asarray(a).astype(str), np.asarray(b).astype(str)
    swap = a > b
    result = pd.DataFrame({"timestamp_start": t_end - bin_seconds, "timestamp_end": t_end,
        "node_a": np.where(swap, b, a), "node_b": np.where(swap, a, b), "dataset": name, "context": family})
    return result


def load(name: str, path: Path) -> tuple[pd.DataFrame, dict]:
    """Return normalized contacts and an import report. Never extracts archive paths."""
    src = SOURCES[name]
    report = {"dataset": name, "source_rows": 0, "dropped": {}}
    if src.kind == "tij":
        if path.suffix == ".zip":
            with zipfile.ZipFile(path) as archive:
                members = [n for n in archive.namelist() if n.endswith(".dat")]
                if len(members) != 1:
                    raise ValueError("Expected exactly one .dat contact file")
                raw = archive.read(members[0])
        else:
            raw = gzip.decompress(path.read_bytes())
        frame = pd.read_csv(io.BytesIO(raw), sep=r"\s+", header=None, dtype=str)
        if frame.shape[1] != src.columns:
            raise ValueError(f"{name}: expected {src.columns} columns, got {frame.shape[1]}")
        report["source_rows"] = len(frame)
        t = pd.to_numeric(frame[0], errors="raise").to_numpy(float)
        # Documented 20 s end-stamped bins. A few HS2011 rows are offset by 10 s:
        # snap to the trace's dominant grid and count every snapped row.
        origin = float(pd.Series(t % src.bin_seconds).mode().iloc[0])
        snapped = origin + np.round((t - origin) / src.bin_seconds) * src.bin_seconds
        report["snapped_rows"] = int((snapped != t).sum())
        out = _frame(snapped, frame[1].str.strip(), frame[2].str.strip(), src.bin_seconds, name, src.family)
        out["episode_hint"] = ""
    elif src.kind == "tgz_days":
        parts = []
        with tarfile.open(path, "r:gz") as archive:
            for member in archive.getmembers():
                match = re.fullmatch(r"listcontacts_(\d{4}_\d{2}_\d{2})\.txt", member.name)
                if not member.isfile() or not match:
                    continue
                data = archive.extractfile(member).read()  # read bytes only; no path extraction
                frame = pd.read_csv(io.BytesIO(data), sep=r"\s+", header=None, dtype=str)
                if frame.shape[1] != 3:
                    raise ValueError(f"{member.name}: expected 3 columns")
                day = match.group(1)
                t = pd.to_numeric(frame[0], errors="raise").to_numpy(float)
                # Badge IDs are reused by different visitors on different days.
                ids_a = day + ":" + frame[1].str.strip()
                ids_b = day + ":" + frame[2].str.strip()
                part = _frame(t, ids_a, ids_b, 20, name, src.family)
                part["episode_hint"] = day
                parts.append(part)
                report["source_rows"] += len(frame)
        out = pd.concat(parts, ignore_index=True)
        report["days"] = len(parts)
    elif src.kind == "copenhagen":
        frame = pd.read_csv(path, comment="#", header=None, names=["t", "a", "b", "rssi"],
            dtype={"t": np.int64, "a": np.int64, "b": np.int64, "rssi": np.int64})
        report["source_rows"] = len(frame)
        empty = frame.b == -1
        outside = frame.b == -2
        bad_rssi = (frame.b >= 0) & (frame.rssi >= 0)
        report["dropped"] = {"empty_scans": int(empty.sum()), "non_participant_devices": int(outside.sum()),
            "nonnegative_rssi_rows": int(bad_rssi.sum())}
        frame = frame[(frame.b >= 0) & (frame.rssi < 0) & (frame.a != frame.b)]
        # Scans every 5 min; we treat a detection at t as radio opportunity [t, t+300].
        out = _frame(frame.t.to_numpy(float) + 300, frame.a.to_numpy(), frame.b.to_numpy(), 300, name, src.family)
        out["episode_hint"] = ""
    else:
        raise ValueError(src.kind)
    before = len(out)
    out = out[out.node_a != out.node_b]
    report["dropped"]["self_contacts"] = before - len(out)
    before = len(out)
    out = out.drop_duplicates(["timestamp_start", "node_a", "node_b"]).sort_values(
        ["timestamp_start", "node_a", "node_b"]).reset_index(drop=True)
    report["dropped"]["duplicate_rows"] = before - len(out)
    report.update(validate(out.drop(columns="episode_hint")))
    report.pop("encounters_per_node")
    return out, report


def load_meshmind_field(paths, bin_seconds: int = 20) -> tuple[pd.DataFrame, dict]:
    """Contacts recorded by MeshMind phones in SAGE shadow mode (Debug → Export, one file per phone).

    Each phone logs the peers it saw (linked or only discovered) per completed bin, on its own local clock:
    ``bin_start_local_s,self,peer,gateway``. Phones' clocks are not aligned, so starts are snapped to the bin grid,
    and a contact counts if either phone saw the other. Rows for the virtual "@gateway" column are reported, not
    returned as contacts. This is the field-data half of the research flywheel (ANDROID_INTEGRATION_PLAN.md §5).
    """
    rows, gateway_rows, phones = [], 0, set()
    for path in map(Path, paths):
        lines = path.read_text(encoding="utf-8").splitlines()
        try:
            start = next(i for i, l in enumerate(lines) if l.startswith("# sage_contacts"))
        except StopIteration:
            raise ValueError(f"{path.name}: no '# sage_contacts' section (export from the Debug screen)")
        if lines[start + 1].strip() != "bin_start_local_s,self,peer,gateway":
            raise ValueError(f"{path.name}: unexpected sage_contacts header")
        for line in lines[start + 2:]:
            if line.startswith("#") or not line.strip():
                break
            t, me, peer, gw = line.split(",")
            phones.add(me)
            if gw == "1":
                gateway_rows += 1
                continue
            rows.append((float(t), me, peer))
    if not rows:
        raise ValueError("no contact rows found")
    t = np.array([r[0] for r in rows])
    snapped = np.round(t / bin_seconds) * bin_seconds
    out = _frame(snapped + bin_seconds, [r[1] for r in rows], [r[2] for r in rows], bin_seconds,
        "meshmind_field", "event_field")
    before = len(out)
    out = out[out.node_a != out.node_b].drop_duplicates(["timestamp_start", "node_a", "node_b"]).sort_values(
        ["timestamp_start", "node_a", "node_b"]).reset_index(drop=True)
    report = {"dataset": "meshmind_field", "source_rows": len(rows) + gateway_rows, "phones": sorted(phones),
        "gateway_bins": gateway_rows, "dropped": {"duplicate_or_two_sided_rows": before - len(out)}}
    return out, report


@dataclass
class Episode:
    """One independent replay window. Times are seconds relative to the trace origin."""
    key: str
    trace: str
    family: str
    index: int
    bin_seconds: int
    nodes: list[str]                     # population of the whole trace (warm-up + episode)
    start: np.ndarray                    # bin start, sorted
    a: np.ndarray                        # int node index
    b: np.ndarray
    warm_start: np.ndarray = field(default_factory=lambda: np.zeros(0))
    warm_a: np.ndarray = field(default_factory=lambda: np.zeros(0, np.int32))
    warm_b: np.ndarray = field(default_factory=lambda: np.zeros(0, np.int32))

    @property
    def t0(self):
        return float(self.start[0])

    @property
    def t1(self):
        return float(self.start[-1] + self.bin_seconds)

    def summary(self):
        active = np.unique(np.concatenate([self.a, self.b]))
        return {"episode": self.key, "trace": self.trace, "family": self.family, "bin_seconds": self.bin_seconds,
            "active_nodes": int(len(active)), "contact_bins": int(len(self.start)),
            "hours": round((self.t1 - self.t0) / 3600, 2), "warmup_contact_bins": int(len(self.warm_start))}


def episodes(frame: pd.DataFrame, name: str, gap=3 * 3600, max_hours=24.0, warm_days=7.0, min_hours=1.0):
    """Split a trace into activity sessions; earlier sessions of the SAME population warm up history.

    Infectious days have disjoint visitors, so they never warm up each other.
    """
    src = SOURCES[name]
    out = []
    groups = [(hint, g) for hint, g in frame.groupby("episode_hint", sort=True)] if name == "infectious" else [("", frame)]
    for hint, g in groups:
        nodes = sorted(set(g.node_a) | set(g.node_b))
        index = {n: i for i, n in enumerate(nodes)}
        s = g.timestamp_start.to_numpy(float)
        a = g.node_a.map(index).to_numpy(np.int32)
        b = g.node_b.map(index).to_numpy(np.int32)
        order = np.argsort(s, kind="stable")
        s, a, b = s[order], a[order], b[order]
        origin = s[0]
        s = s - origin
        # Session boundaries at gaps; long sessions cut into <= max_hours windows.
        cuts = [0]
        unique = np.unique(s)
        session_start = unique[0]
        for prev, cur in zip(unique[:-1], unique[1:]):
            if cur - prev > gap or cur - session_start >= max_hours * 3600:
                cuts.append(int(np.searchsorted(s, cur)))
                session_start = cur
        cuts.append(len(s))
        for k, (lo, hi) in enumerate(zip(cuts[:-1], cuts[1:])):
            if (s[hi - 1] - s[lo]) < min_hours * 3600:
                continue
            warm_lo = int(np.searchsorted(s, s[lo] - warm_days * 86400))
            key = f"{name}/{hint or 'all'}/{k}"
            out.append(Episode(key, name, src.family, len(out), src.bin_seconds, nodes,
                s[lo:hi], a[lo:hi], b[lo:hi], s[warm_lo:lo], a[warm_lo:lo], b[warm_lo:lo]))
    for i, e in enumerate(out):
        e.index = i
    return out


def load_all(root: Path, names=None, cache: Path | None = None):
    """Load traces (downloading public files if missing) and return {name: (frame, report)}."""
    result = {}
    for name in names or SOURCES:
        path = download(name, root)
        cached = cache / f"{name}.parquet" if cache else None
        if cached is not None and cached.exists() and cached.stat().st_mtime > path.stat().st_mtime:
            frame = pd.read_parquet(cached)
            report = json.loads(cached.with_suffix(".json").read_text())
        else:
            frame, report = load(name, path)
            if cached is not None:
                cached.parent.mkdir(parents=True, exist_ok=True)
                frame.to_parquet(cached, index=False)
                cached.with_suffix(".json").write_text(json.dumps(report, indent=2))
        result[name] = (frame, report)
    return result

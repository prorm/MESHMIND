"""SAGE v1 experiment stages: labels -> train -> tune (validation) -> test -> report.

Run from research/sage:
    python -m sage.v1.experiment --config configs/v1.json --stage all
Each stage writes to outputs/v1 and can be rerun on its own.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
import json
import os
from pathlib import Path
import time
import numpy as np
import pandas as pd
from .traces import load_all, episodes as make_episodes
from .sim import Scenario, World, Simulator, label_logs
from .state import NodeState, FEATURES
from . import policies as P

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "outputs" / "v1"
LABELS = ROOT / "data" / "processed" / "v1" / "labels"
STORE = ROOT / "data" / "processed" / "v1" / "episodes"
_EPISODES = {}
_LOADED = {}


# ----------------------------------------------------------------- episodes & splits
class _Meta:
    """Lightweight episode handle; arrays load lazily from the per-episode .npz store."""

    def __init__(self, d):
        self.__dict__.update(d)


def build_store(config):
    """Parse traces ONCE in the parent process and write compact per-episode arrays."""
    STORE.mkdir(parents=True, exist_ok=True)
    index_path = STORE / "index.json"
    if index_path.exists():
        return
    index = {}
    data = load_all(ROOT / "data" / "raw", config["traces"], cache=ROOT / "data" / "processed" / "v1")
    for name, (frame, report) in data.items():
        for ep in make_episodes(frame, name):
            s = ep.summary()
            if s["active_nodes"] < config["min_active_nodes"] or s["hours"] < config["min_hours"]:
                continue
            fname = ep.key.replace("/", "__") + ".npz"
            np.savez_compressed(STORE / fname, start=ep.start, a=ep.a, b=ep.b, warm_start=ep.warm_start,
                warm_a=ep.warm_a, warm_b=ep.warm_b, nodes=np.array(ep.nodes))
            index[ep.key] = s | {"file": fname, "index": ep.index, "n": len(ep.nodes)}
        (STORE / f"{name}_import.json").write_text(json.dumps(report, indent=2))
    index_path.write_text(json.dumps(index, indent=2))


def all_episodes(config):
    if not _EPISODES:
        build_store(config)
        for key, d in json.loads((STORE / "index.json").read_text()).items():
            if d["trace"] in config["traces"]:
                _EPISODES[key] = _Meta(d | {"key": key})
    return _EPISODES


def load_episode(config, key):
    if key not in _LOADED:
        from .traces import Episode
        meta = all_episodes(config)[key]
        z = np.load(STORE / meta.file)
        _LOADED.clear()   # keep at most one episode resident per worker
        _LOADED[key] = Episode(key, meta.trace, meta.family, meta.index, meta.bin_seconds, list(z["nodes"]),
            z["start"], z["a"], z["b"], z["warm_start"], z["warm_a"], z["warm_b"])
    return _LOADED[key]


def split_of(ep, config):
    for split in ("test", "validation", "train"):
        for rule in config["splits"][split]:
            trace, _, rng = rule.partition(":")
            if ep.trace != trace:
                continue
            if not rng:
                return split
            lo, hi = map(int, rng.split("-"))
            eps = all_episodes(config)
            order = sorted((k for k, e in eps.items() if e.trace == trace), key=lambda k: eps[k].index)
            if lo <= order.index(ep.key) <= hi:
                return split
    return None


def splits(config):
    out = {"train": [], "validation": [], "test": []}
    for key, ep in all_episodes(config).items():
        s = split_of(ep, config)
        if s:
            out[s].append(key)
    return {k: sorted(v) for k, v in out.items()}


def warm_state(world):
    ep = world.ep
    st = NodeState(world.n, ep.bin_seconds)
    if len(ep.warm_start):
        ok = world.participant[ep.warm_a] & world.participant[ep.warm_b]
        s, a, b = ep.warm_start[ok], ep.warm_a[ok], ep.warm_b[ok]
        bins, offs = np.unique(s, return_index=True)
        offs = np.append(offs, len(s))
        for k, t in enumerate(bins):
            st.observe_bin(float(t), a[offs[k]:offs[k + 1]], b[offs[k]:offs[k + 1]], world.gateway_up(t + ep.bin_seconds))
    return st


# ----------------------------------------------------------------- policies
_MODELS = {}


def predictor(path):
    if path not in _MODELS:
        if path.endswith(".pkl"):
            import pickle
            from .model import TreePredictor
            with open(path, "rb") as f:
                _MODELS[path] = TreePredictor(pickle.load(f))
        else:
            import torch
            from .model import NumpySageNet
            bundle = torch.load(path, map_location="cpu", weights_only=True)
            if bundle["features"] != FEATURES:
                raise ValueError("feature schema mismatch")
            _MODELS[path] = NumpySageNet(bundle["state_dict"])
    return _MODELS[path]


def make_policy(spec: dict):
    kind = spec["kind"]
    if kind == "direct":
        return P.Direct()
    if kind == "epidemic":
        return P.Epidemic()
    if kind == "spray_wait":
        return P.SprayWait(spec["copies"])
    if kind == "spray_focus":
        return P.SprayFocus(spec["copies"])
    if kind == "prophet":
        return P.Prophet(spec["speed"])
    if kind == "heuristic":
        return P.Heuristic()
    if kind == "prophet_value":
        return P.ProphetValue(spec["speed"], threshold=spec.get("threshold", 0.0), per_byte=spec.get("per_byte", False))
    if kind == "sage":
        pol = P.Sage(predictor(str(ROOT / spec["model"])), spec.get("name", "sage"), spec.get("head", "gain_replicate"),
            threshold=spec.get("threshold", 0.0), per_byte=spec.get("per_byte", False))
        return pol
    raise ValueError(kind)


def policy_label(spec):
    return spec.get("label") or make_policy(spec).name


# ----------------------------------------------------------------- jobs (run in worker processes)
def _world(config, key, scenario, seed):
    ep = load_episode(config, key)
    sc = Scenario(**scenario)
    return World(ep, sc, seed)


def label_job(config, key, scenario, seed, behavior, out_path):
    t = time.perf_counter()
    world = _world(config, key, scenario, seed)
    sim = Simulator(world, warm_state(world))
    metrics, logs = sim.run(make_policy(behavior), log_rate=config["label_log_rate"],
        log_cap=config["label_cap_per_run"], log_seed=seed)
    labeled = label_logs(world, logs)
    if labeled is None:
        return {"key": key, "rows": 0}
    X, meta = labeled
    frame = pd.DataFrame(X, columns=FEATURES)
    for k, v in meta.items():
        frame[k] = v
    frame["episode"] = key
    frame["family"] = world.ep.family
    frame["seed"] = seed
    frame["behavior"] = behavior["kind"]
    frame["group"] = [f"{key}|{seed}|{behavior['kind']}|{i}|{a}|{s}" for i, a, s in
        zip(meta["packet"], meta["holder"], meta["t"])]
    frame.to_parquet(out_path, index=False)
    return {"key": key, "rows": len(frame), "seconds": time.perf_counter() - t,
        "delivery": metrics["normal_delivery_ratio"]}


def eval_job(config, key, scenario, seed, specs, with_ceiling=True):
    world = _world(config, key, scenario, seed)
    warm = warm_state(world)
    ceiling = world.oracle_ceiling(config.get("ceiling_sample")) if with_ceiling else None
    rows = []
    for spec in specs:
        t = time.perf_counter()
        pol = make_policy(spec)
        m, _ = Simulator(world, warm).run(pol)
        m["policy"] = policy_label(spec)
        m["seconds"] = time.perf_counter() - t
        m["fallbacks"] = getattr(pol, "fallbacks", 0)
        if ceiling is not None:
            normal = world.p_prio > 0
            m["oracle_ceiling"] = float(np.nanmean(ceiling[normal])) if np.isfinite(ceiling[normal]).any() else None
            m["critical_ceiling"] = float(np.nanmean(ceiling[~normal])) if np.isfinite(ceiling[~normal]).any() else None
        m["scenario_params"] = json.dumps(scenario, sort_keys=True)
        rows.append(m)
    return rows


def run_parallel(fn, jobs, workers, desc):
    results = []
    started = time.perf_counter()
    with ProcessPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(fn, *job) for job in jobs]
        for k, f in enumerate(as_completed(futures), 1):
            results.append(f.result())
            if k % max(1, len(jobs) // 20) == 0 or k == len(jobs):
                print(f"[{desc}] {k}/{len(jobs)} jobs, {time.perf_counter() - started:.0f}s", flush=True)
    return results


# ----------------------------------------------------------------- stages
def random_scenario(rng, space):
    return {k: (v[int(rng.integers(len(v)))] if isinstance(v, list) else v) for k, v in space.items()}


def stage_labels(config, workers):
    sp = splits(config)
    rng = np.random.default_rng(config["seed"])
    jobs = []
    for split, keys in sp.items():
        (LABELS / split).mkdir(parents=True, exist_ok=True)
        for key in keys:
            for r in range(config["label_runs_per_episode"][split]):
                scenario = random_scenario(rng, config["label_scenario_space"]) | {"name": "label"}
                behavior = config["behavior_policies"][r % len(config["behavior_policies"])]
                seed = 100 + r
                out = LABELS / split / f"{key.replace('/', '__')}__{r}.parquet"
                if out.exists():
                    continue
                jobs.append((config, key, scenario, seed, behavior, str(out)))
    print(f"label jobs: {len(jobs)}", flush=True)
    results = run_parallel(label_job, jobs, workers, "labels")
    OUT.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(results).to_csv(OUT / "label_jobs.csv", index=False)


def load_labels(split):
    files = sorted((LABELS / split).glob("*.parquet"))
    return pd.concat([pd.read_parquet(f) for f in files], ignore_index=True) if files else pd.DataFrame()


def scenarios(config, names=None):
    base = config["eval_base"]
    out = {}
    for name, over in config["eval_scenarios"].items():
        if names is None or name in names:
            sc = dict(base) | over | {"name": name}
            if "partition" in sc:
                sc["partition"] = tuple(sc["partition"])
            out[name] = sc
    return out


def stage_tune(config, workers):
    """Pick every tunable policy's hyper-parameters on VALIDATION episodes only."""
    sp = splits(config)
    candidates = config["tuning_candidates"]
    specs = [s for group in candidates.values() for s in group]
    for k, s in enumerate(specs):
        s.setdefault("label", f"{s['kind']}#{k}")
    jobs = []
    for name, sc in scenarios(config, config["tuning_scenarios"]).items():
        for key in sp["validation"]:
            for seed in config["tuning_seeds"]:
                jobs.append((config, key, sc, seed, specs, False))
    rows = [r for batch in run_parallel(eval_job, jobs, workers, "tune") for r in batch]
    frame = pd.DataFrame(rows)
    frame.to_csv(OUT / "tuning_runs.csv", index=False)
    # Mean over (episode, scenario, seed) per configuration; choose max delivery, tie -> fewer bytes.
    mean = frame.groupby("policy")[["normal_delivery_ratio", "total_bytes"]].mean()
    selected = {}
    for family, group in candidates.items():
        labels = [s["label"] for s in group]
        m = mean.loc[labels]
        best_delivery = m.normal_delivery_ratio.max()
        best = m[m.normal_delivery_ratio >= best_delivery - 1e-12].total_bytes.idxmin()
        selected[family] = next(s for s in group if s["label"] == best) | {"label": family}
        # Efficiency operating point: fewest bytes within 1% (absolute) of the family's best delivery.
        if family.startswith("sage") or family.startswith("prophet_value"):
            ok = m[m.normal_delivery_ratio >= best_delivery - 0.01]
            eco = ok.total_bytes.idxmin()
            selected[family + "_eco"] = next(s for s in group if s["label"] == eco) | {"label": family + "_eco"}
    (OUT / "selected_policies.json").write_text(json.dumps(selected, indent=2))
    print(json.dumps(selected, indent=2), flush=True)
    return selected


def stage_test(config, workers, scenario_names=None, tag="test"):
    selected = json.loads((OUT / "selected_policies.json").read_text())
    fixed = config["fixed_policies"]
    specs = fixed + list(selected.values())
    sp = splits(config)
    jobs = []
    for name, sc in scenarios(config, scenario_names).items():
        seeds = config["test_core_seeds"] if name in config.get("test_core_scenarios", []) else config["test_seeds"]
        for key in sp["test"]:
            for seed in seeds:
                jobs.append((config, key, sc, seed, specs, True))
    rows = [r for batch in run_parallel(eval_job, jobs, workers, tag) for r in batch]
    frame = pd.DataFrame(rows)
    frame.to_csv(OUT / f"{tag}_runs.csv", index=False)
    return frame


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=ROOT / "configs" / "v1.json")
    parser.add_argument("--stage", default="all")
    parser.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "config_used.json").write_text(json.dumps(config, indent=2))
    stages = ["episodes", "labels", "train", "tune", "test", "report"] if args.stage == "all" else args.stage.split(",")
    if "episodes" in stages or "labels" in stages:
        sp = splits(config)
        summary = [{k2: v for k2, v in vars(all_episodes(config)[k]).items()} | {"split": s} for s, keys in sp.items() for k in keys]
        pd.DataFrame(summary).to_csv(OUT / "episodes.csv", index=False)
        print({k: len(v) for k, v in sp.items()}, flush=True)
    if "labels" in stages:
        stage_labels(config, args.workers)
    if "train" in stages:
        from .learn import stage_train
        stage_train(config, OUT, load_labels)
    if "tune" in stages:
        stage_tune(config, args.workers)
    if "test" in stages:
        stage_test(config, args.workers)
    if "report" in stages:
        from .report import stage_report
        stage_report(config, OUT)


if __name__ == "__main__":
    main()

"""DIAGNOSTIC ONLY: how much could a PERFECT relay-gain predictor gain inside the SAGE skeleton?

``OracleGain`` reads the future contact trace through the offline oracle, so it is not a
deployable policy. It bounds what better learning could achieve with the same mechanics.
Run: python -m sage.v1.headroom --episodes 6
"""
from __future__ import annotations
import argparse
from concurrent.futures import ProcessPoolExecutor
import json
import numpy as np
import pandas as pd
from .oracle import sweep, utility
from .policies import ValuePolicy
from .sim import Simulator
from . import experiment as X


class OracleGain(ValuePolicy):
    def __init__(self, world, **kw):
        super().__init__(**kw)
        self.w = world
        self.name = f"oracle_gain_t{kw.get('threshold', 0)}"

    def estimate(self, ctx, C):
        w = self.w
        value = np.zeros(len(C.i))
        gain = np.zeros(len(C.i))
        for i in np.unique(C.i):
            rows = np.flatnonzero(C.i == i)
            snap = sweep(w.index, int(w.p_dst[i]), float(w.p_expiry[i]), w.sc.ttl, [C.e], w.gateway_up)[C.e]
            ea = snap[C.a[rows], C.hops_left[rows]]
            eb = snap[C.b[rows], C.hops_left[rows] - 1]
            ua = utility(ea, w.p_expiry[i], w.sc.lifetime)
            value[rows] = ua
            gain[rows] = utility(np.minimum(ea, eb), w.p_expiry[i], w.sc.lifetime) - ua
        return value, gain


def job(config, key, scenario, seed, sage_specs, thresholds):
    world = X._world(config, key, scenario, seed)
    warm = X.warm_state(world)
    rows = []
    pols = [X.make_policy({"kind": "epidemic"})] + [X.make_policy(s) for s in sage_specs] + \
        [OracleGain(world, threshold=t) for t in thresholds]
    labels = ["epidemic"] + [s["label"] for s in sage_specs] + [f"oracle_gain_t{t}" for t in thresholds]
    for label, pol in zip(labels, pols):
        m, _ = Simulator(world, warm).run(pol)
        m["policy"] = label
        rows.append(m)
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--episodes", type=int, default=6)
    parser.add_argument("--workers", type=int, default=3)
    args = parser.parse_args()
    config = json.loads((X.ROOT / "configs" / "v1.json").read_text())
    keys = [k for k in X.splits(config)["validation"] if k.startswith("infectious")][: args.episodes]
    m = "outputs/v1/checkpoints/sage_net.pt"
    specs = [{"kind": "sage", "model": m, "threshold": t, "per_byte": pb, "label": f"sage_t{t}{'_pb' if pb else ''}"}
        for t, pb in [(-1.0, True), (0.005, True), (0.02, True), (0.05, False)]]
    thresholds = [-1.0, 0.0, 0.005, 0.02]
    jobs = []
    for name, sc in X.scenarios(config, ["base", "heavy_load", "queue_saturation"]).items():
        for key in keys:
            jobs.append((config, key, sc, 7, specs, thresholds))
    with ProcessPoolExecutor(args.workers) as pool:
        rows = [r for batch in pool.map(job, *zip(*jobs)) for r in batch]
    frame = pd.DataFrame(rows)
    frame.to_csv(X.OUT / "headroom_runs.csv", index=False)
    print(frame.groupby(["scenario", "policy"])[["normal_delivery_ratio", "total_bytes"]].mean().round(4).to_string())


if __name__ == "__main__":
    main()

"""Export SAGE v1 for the Kotlin router: model weights + golden vectors for feature/inference parity.

Writes
  core/src/main/resources/sage/sage_net_weights.json   weights the phone runs (pure Kotlin forward pass)
  core/src/test/resources/sage/golden.json             a synthetic contact history, decision queries, the
                                                       research features (state.py) and ONNX outputs for them

The Kotlin side (dev.meshmind.core.sage) replays the same contacts through one ContactHistory per node and must
reproduce every feature and output within 1e-4 (SageGoldenTest). Run from research/sage:
    .venv\\Scripts\\python.exe scripts\\export_kotlin.py
"""
from __future__ import annotations
import json
import math
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from sage.v1.state import FEATURES, NodeState, packet_view  # noqa: E402

REPO = ROOT.parents[1]
CKPT = ROOT / "outputs/v1/checkpoints"
WEIGHTS_OUT = REPO / "core/src/main/resources/sage/sage_net_weights.json"
GOLDEN_OUT = REPO / "core/src/test/resources/sage/golden.json"

N, BIN, GATEWAY_NODE = 6, 20.0, 5
T0 = 86400.0 * 20000 + 3600.0          # day-aligned origin (hour-of-day features are t mod 86400)
SPAN_BINS = int(1.5 * 86400 / BIN)


def export_weights():
    bundle = torch.load(CKPT / "sage_net.pt", map_location="cpu", weights_only=False)
    sd = {k: v.detach().cpu().numpy().astype(np.float32) for k, v in bundle["state_dict"].items()}
    meta = json.loads((CKPT / "sage_net.json").read_text())
    assert meta["feature_names"] == FEATURES
    out = {
        "version": 1,
        "source": "research/sage outputs/v1/checkpoints/sage_net.pt",
        "feature_names": FEATURES,
        "outputs": meta["outputs"],
        "hidden": bundle["hidden"], "embed": bundle["embed"],
        "tensors": {k: {"shape": list(v.shape), "data": [float(x) for x in v.ravel()]} for k, v in sd.items()},
    }
    WEIGHTS_OUT.parent.mkdir(parents=True, exist_ok=True)
    WEIGHTS_OUT.write_text(json.dumps(out, separators=(",", ":")))
    print(f"weights -> {WEIGHTS_OUT} ({WEIGHTS_OUT.stat().st_size} B, {sum(v.size for v in sd.values())} values)")


def contact_bins(rng):
    """Random contacts per bin; everyone meets at 01:00 each day so every node sees every day (per-phone day sets)."""
    bins = []
    for k in range(SPAN_BINS):
        start = T0 + k * BIN
        pairs = set()
        if int((start % 86400) // 3600) == 1 and (start % 3600) < BIN:
            pairs = {(i, j) for i in range(N) for j in range(i + 1, N)}
        else:
            rate = 0.02 + 0.06 * (math.sin(start / 5400.0) + 1) / 2     # crowds come and go
            for i in range(N):
                for j in range(i + 1, N):
                    if rng.random() < rate * (2.0 if (i, j) in ((0, 1), (2, 3)) else 1.0):  # two "friend" pairs
                        pairs.add((i, j))
        gw_up = (int(start // 1800) % 3) != 2                          # the gateway flickers: up 1 h, down 30 min
        bins.append((start, sorted(pairs), gw_up))
    return bins


def main():
    export_weights()
    rng = np.random.default_rng(2026)
    bins = contact_bins(rng)
    st = NodeState(N, int(BIN))
    sess = ort.InferenceSession(str(CKPT / "sage_net.onnx"), providers=["CPUExecutionProvider"])

    # Decision queries at random times after warm-up; each is evaluated right after its bin is observed.
    q_bins = set(rng.choice(np.arange(200, SPAN_BINS), size=256, replace=False).tolist())
    queries = []
    for k, (start, pairs, gw_up) in enumerate(bins):
        a = np.array([p[0] for p in pairs], np.int64)
        b = np.array([p[1] for p in pairs], np.int64)
        up = np.zeros(N, bool)
        up[GATEWAY_NODE] = gw_up
        st.observe_bin(start, a, b, up)
        if k not in q_bins:
            continue
        t = start + BIN + float(rng.integers(0, 20))
        na, nb = rng.choice(N, 2, replace=False)
        dest = int(rng.integers(0, N + 1))           # N = the virtual "@gateway" column
        if dest == na:
            dest = N
        prio = int(rng.choice([1, 2, 3]))
        size = int(rng.choice([128, 512, 4096, 16384, 32768]))
        lifetime = float(rng.choice([1800.0, 7200.0, 86400.0]))
        age = float(rng.uniform(0, lifetime * 0.9))
        remaining = lifetime - age
        replicas = int(rng.integers(0, 6))
        hops_left = int(rng.integers(1, 9))
        qf = rng.uniform(0, 1, 2)
        qc = rng.integers(0, 400, 2)
        bat = rng.uniform(0.05, 1, 2)
        gw = [int(na == GATEWAY_NODE and gw_up), int(nb == GATEWAY_NODE and gw_up)]
        P = packet_view([prio], [dest == N], [size], [age], [remaining], [lifetime], [replicas], BIN)
        S = st.node_view([na], [dest], t, hops_left, remaining, qf[0], qc[0], bat[0], gw[0])
        Q = st.node_view([nb], [dest], t, hops_left - 1, remaining, qf[1], qc[1], bat[1], gw[1])
        R = st.pair_view([na], [nb], t)
        x = np.concatenate([P, S, Q, R], 1).astype(np.float32)
        y = sess.run(None, {"features": x})[0][0]
        queries.append(dict(bin=k, t=t, a=int(na), b=int(nb), dest=dest, prio=prio, size=size, lifetime=lifetime,
            age=age, replicas=replicas, hops_left=hops_left, queue_frac=[float(v) for v in qf],
            queue_count=[int(v) for v in qc], battery=[float(v) for v in bat], is_gateway=gw,
            features=[float(v) for v in x[0]], outputs=[float(v) for v in y]))

    golden = dict(version=1, n=N, bin_seconds=BIN, gateway_node=GATEWAY_NODE, feature_names=FEATURES,
        bins=[[s, [list(p) for p in pairs], up] for s, pairs, up in bins], queries=queries)
    GOLDEN_OUT.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN_OUT.write_text(json.dumps(golden, separators=(",", ":")))
    print(f"golden -> {GOLDEN_OUT} ({GOLDEN_OUT.stat().st_size} B, {len(bins)} bins, {len(queries)} queries)")


if __name__ == "__main__":
    main()

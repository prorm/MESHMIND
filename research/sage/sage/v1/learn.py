"""Train SAGE-Net (CUDA) and a gradient-boosted tree; offline accuracy vs hand-crafted scores; ONNX."""
from __future__ import annotations
import json
import pickle
import time
from pathlib import Path
import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.metrics import roc_auc_score, average_precision_score, mean_squared_error
from .state import FEATURES

TARGETS = ["u_self", "u_peer", "reach_self", "reach_peer", "gain_replicate", "gain_forward"]
USEFUL = 0.01      # a copy is "useful" if the oracle says it raises delivery utility by > 1% of lifetime


def arrays(frame):
    X = frame[FEATURES].to_numpy(np.float32)
    Y = frame[TARGETS].to_numpy(np.float32)
    return X, Y


def handcrafted_scores(frame):
    """Non-learned relay scores computable from the SAME leakage-free features."""
    f = frame
    return {
        "prophet_fast (P_peer - P_self)": f.peer_d_prophet_fast - f.self_d_prophet_fast,
        "prophet_slow (P_peer - P_self)": f.peer_d_prophet_slow - f.self_d_prophet_slow,
        "encounter timer (spray-and-focus)": f.self_d_since_log - f.peer_d_since_log,
        "dest contact rate 1h (MeshMind heuristic)": f.peer_d_rate_1h - f.self_d_rate_1h,
        "peer degree 1h": f.peer_uniq_1h - f.self_uniq_1h,
        "daily routine": f.peer_d_hour_routine - f.self_d_hour_routine,
    }


def ranking_report(name, frame, scores: dict):
    useful = (frame.gain_replicate > USEFUL).to_numpy()
    rows = []
    for method, s in scores.items():
        s = np.asarray(s, float)
        row = {"split": name, "method": method, "rows": len(s), "useful_rate": float(useful.mean())}
        if 0 < useful.sum() < len(useful):
            row["auc_useful_copy"] = float(roc_auc_score(useful, s))
            row["avg_precision_useful_copy"] = float(average_precision_score(useful, s))
            # Precision when copying only the top 10% of opportunities.
            k = max(1, len(s) // 10)
            top = np.argsort(-s, kind="stable")[:k]
            row["precision_top10pct"] = float(useful[top].mean())
            row["recall_top10pct"] = float(useful[top].sum() / useful.sum())
        rows.append(row)
    return rows


def train_net(X, Y, fam, vX, vY, config, out: Path, hidden, embed, seed=7):
    import torch
    from torch import nn
    from ..models import require_cuda
    from .model import SageNet, parameter_count
    hardware = require_cuda()
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    mean = X.mean(0)
    scale = np.maximum(X.std(0), 1e-3)
    model = SageNet(mean, scale, hidden, embed).cuda()
    dev = torch.device("cuda")
    on_gpu = X.nbytes + Y.nbytes < 900_000_000
    tX = torch.from_numpy(X).to(dev) if on_gpu else torch.from_numpy(X)
    tY = torch.from_numpy(Y).to(dev) if on_gpu else torch.from_numpy(Y)
    vtX, vtY = torch.from_numpy(vX).to(dev), torch.from_numpy(vY).to(dev)
    # Family-balanced sampling: each domain family contributes equally per epoch.
    families, inverse, counts = np.unique(fam, return_inverse=True, return_counts=True)
    prob = (1.0 / counts[inverse]) / len(families)
    per_epoch = min(len(X), config.get("rows_per_epoch", 600_000))
    epochs, bs = config["epochs"], config["batch_size"]
    opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-4)
    steps = epochs * int(np.ceil(per_epoch / bs))
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=steps, pct_start=0.1)
    bce = nn.functional.binary_cross_entropy_with_logits

    def loss_fn(o, y):
        w_gain = 1 + 4 * (y[:, 4] > USEFUL).float()
        return (nn.functional.mse_loss(o[:, 0], y[:, 0]) + nn.functional.mse_loss(o[:, 1], y[:, 1])
            + 0.25 * (bce(o[:, 2], y[:, 2]) + bce(o[:, 3], y[:, 3]))
            + 4 * (w_gain * (o[:, 4] - y[:, 4]) ** 2).mean() + nn.functional.mse_loss(o[:, 5], y[:, 5]))

    torch.cuda.reset_peak_memory_stats()
    started = time.perf_counter()
    best, best_state, history = np.inf, None, []
    for epoch in range(epochs):
        model.train()
        idx = torch.from_numpy(rng.choice(len(X), per_epoch, replace=True, p=prob))
        for b in idx.split(bs):
            xb = tX[b.to(dev)] if on_gpu else tX[b].to(dev, non_blocking=True)
            yb = tY[b.to(dev)] if on_gpu else tY[b].to(dev, non_blocking=True)
            opt.zero_grad(set_to_none=True)
            loss = loss_fn(model(xb), yb)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
        model.eval()
        with torch.no_grad():
            vl = float(np.mean([loss_fn(model(vtX[i:i + 65536]), vtY[i:i + 65536]).item()
                for i in range(0, len(vX), 65536)]))
        history.append(vl)
        if vl < best:
            best, best_state = vl, {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
    torch.cuda.synchronize()
    info = {"hidden": hidden, "embed": embed, "parameters": parameter_count(model), "best_validation_loss": best,
        "validation_loss_curve": history, "training_seconds": time.perf_counter() - started,
        "peak_allocated_bytes": torch.cuda.max_memory_allocated(), "peak_reserved_bytes": torch.cuda.max_memory_reserved(),
        "rows_per_epoch": per_epoch, "epochs": epochs, "batch_size": bs, "data_on_gpu": on_gpu, "hardware": hardware}
    model.load_state_dict(best_state)
    return model.cpu().eval(), best_state, info


def predict_net(model, X):
    import torch
    with torch.no_grad():
        out = np.concatenate([model(torch.from_numpy(X[i:i + 65536])).numpy() for i in range(0, len(X), 65536)])
    return {k: out[:, j] for j, k in enumerate(["u_self", "u_peer", "reach_self", "reach_peer", "gain_replicate", "gain_forward"])}


def export_onnx(model, sample, path: Path):
    import onnx
    import onnxruntime as ort
    import torch
    from .model import NumpySageNet

    class Flat(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x):
            o = self.m(x)
            return torch.stack([o[:, 0], o[:, 1], torch.sigmoid(o[:, 2]), torch.sigmoid(o[:, 3]), o[:, 4], o[:, 5]], 1)

    flat = Flat(model).eval()
    sample = np.asarray(sample, np.float32)
    torch.onnx.export(flat, torch.from_numpy(sample[:2]), str(path), input_names=["features"], output_names=["outputs"],
        dynamic_axes={"features": {0: "batch"}, "outputs": {0: "batch"}}, opset_version=17, dynamo=False)
    onnx.checker.check_model(onnx.load(path))
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    expected = flat(torch.from_numpy(sample)).detach().numpy()
    actual = sess.run(None, {"features": sample})[0]
    np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
    npy = NumpySageNet(model.state_dict())(sample)
    np_out = np.stack([npy[k] for k in ["u_self", "u_peer", "reach_self", "reach_peer", "gain_replicate", "gain_forward"]], 1)
    np.testing.assert_allclose(np_out, expected, rtol=1e-4, atol=1e-5)
    for _ in range(50):
        sess.run(None, {"features": sample[:1]})
    single, batch = [], []
    for _ in range(1000):
        t = time.perf_counter_ns()
        sess.run(None, {"features": sample[:1]})
        single.append((time.perf_counter_ns() - t) / 1e6)
    for _ in range(200):
        t = time.perf_counter_ns()
        sess.run(None, {"features": sample[:64]})
        batch.append((time.perf_counter_ns() - t) / 1e6)
    return {"onnx_file": path.name, "file_bytes": path.stat().st_size, "features": len(FEATURES),
        "outputs": ["u_self", "u_peer", "p_reach_self", "p_reach_peer", "gain_replicate", "gain_forward"],
        "max_abs_error_torch_vs_onnx": float(np.abs(actual - expected).max()),
        "max_abs_error_torch_vs_numpy": float(np.abs(np_out - expected).max()),
        "cpu_single_row_p50_ms": float(np.percentile(single, 50)), "cpu_single_row_p95_ms": float(np.percentile(single, 95)),
        "cpu_batch64_p50_ms": float(np.percentile(batch, 50)), "threads": 1,
        "note": "Laptop CPU (Ryzen 5 5600H), onnxruntime CPUExecutionProvider; NOT Android latency."}


def stage_train(config, out: Path, load_labels):
    import torch
    ck = out / "checkpoints"
    ck.mkdir(parents=True, exist_ok=True)
    frames = {s: load_labels(s) for s in ("train", "validation", "test")}
    for s, f in frames.items():
        print(f"{s}: {len(f)} labeled decisions from {f.episode.nunique()} episodes", flush=True)
    X, Y = arrays(frames["train"])
    vX, vY = arrays(frames["validation"])
    fam = frames["train"].family.to_numpy()
    report = {"rows": {s: len(f) for s, f in frames.items()},
        "useful_copy_rate": {s: float((f.gain_replicate > USEFUL).mean()) for s, f in frames.items()},
        "families_train": frames["train"].family.value_counts().to_dict()}
    # --- neural network: small architecture sweep, selected on validation loss only.
    candidates = []
    for hidden, embed in config.get("net_sizes", [[64, 48], [128, 96]]):
        model, state, info = train_net(X, Y, fam, vX, vY, config, ck, hidden, embed)
        print(f"SageNet h={hidden} e={embed}: {info['parameters']} params, val loss {info['best_validation_loss']:.5f}, "
            f"{info['training_seconds']:.0f}s, peak {info['peak_reserved_bytes'] / 2**20:.0f} MiB", flush=True)
        candidates.append((info["best_validation_loss"], model, state, info))
    candidates.sort(key=lambda c: c[0])
    _, model, state, info = candidates[0]
    report["net"] = info
    report["net_sweep"] = [c[3] | {"validation_loss_curve": None, "hardware": None} for c in candidates]
    torch.save({"state_dict": state, "features": FEATURES, "hidden": info["hidden"], "embed": info["embed"],
        "hardware": info["hardware"]}, ck / "sage_net.pt")
    # --- gradient-boosted trees (CPU), same split.
    t = time.perf_counter()
    rng = np.random.default_rng(3)
    sub = rng.choice(len(X), min(len(X), 600_000), replace=False)
    trees = {}
    for target in ("gain_replicate", "u_self"):
        j = TARGETS.index(target)
        trees[target] = HistGradientBoostingRegressor(max_iter=300, max_leaf_nodes=31, learning_rate=0.08,
            l2_regularization=1.0, early_stopping=True, validation_fraction=0.1, random_state=3).fit(X[sub], Y[sub, j])
    with open(ck / "sage_tree.pkl", "wb") as f:
        pickle.dump(trees, f)
    report["tree_seconds"] = time.perf_counter() - t
    # --- offline accuracy: learned vs hand-crafted relay scores on held-out splits.
    rows = []
    for split in ("validation", "test"):
        f = frames[split]
        if f.empty:
            continue
        fx, fy = arrays(f)
        pn = predict_net(model, fx)
        scores = {"SAGE-Net (learned)": pn["gain_replicate"],
            "SAGE-Net value difference": pn["u_peer"] - pn["u_self"],
            "SAGE-Tree (learned)": trees["gain_replicate"].predict(fx)} | handcrafted_scores(f)
        rows += ranking_report(split, f, scores)
        report[f"{split}_mse"] = {"net_gain": float(mean_squared_error(fy[:, 4], pn["gain_replicate"])),
            "tree_gain": float(mean_squared_error(fy[:, 4], trees["gain_replicate"].predict(fx))),
            "net_u_self": float(mean_squared_error(fy[:, 0], pn["u_self"])),
            "zero_predictor_gain": float(np.mean(fy[:, 4] ** 2))}
        reach = fy[:, 2] > 0.5
        if 0 < reach.sum() < len(reach):
            report[f"{split}_reach_auc"] = {"net": float(roc_auc_score(reach, pn["reach_self"])),
                "prophet_slow": float(roc_auc_score(reach, f.self_d_prophet_slow)),
                "encounter_timer": float(roc_auc_score(reach, -f.self_d_since_log))}
        # Per family breakdown of the headline metric.
        for family, g in f.groupby("family"):
            idx = g.index.to_numpy()
            rows += ranking_report(f"{split}:{family}", g, {"SAGE-Net (learned)": pn["gain_replicate"][idx],
                "prophet_slow (P_peer - P_self)": g.peer_d_prophet_slow - g.self_d_prophet_slow,
                "encounter timer (spray-and-focus)": g.self_d_since_log - g.peer_d_since_log})
    pd.DataFrame(rows).to_csv(out / "offline_accuracy.csv", index=False)
    report["onnx"] = export_onnx(model, vX[:256], ck / "sage_net.onnx")
    (ck / "sage_net.json").write_text(json.dumps({"feature_names": FEATURES, **report["onnx"],
        "parameters": info["parameters"], "hidden": info["hidden"], "embed": info["embed"]}, indent=2))
    (out / "training.json").write_text(json.dumps(report, indent=2, default=str))
    acc = pd.DataFrame(rows)
    print(acc[acc.split.isin(["validation", "test"])][["split", "method", "auc_useful_copy",
        "avg_precision_useful_copy", "precision_top10pct"]].to_string(index=False), flush=True)
    return report

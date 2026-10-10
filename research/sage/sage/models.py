"""Tiny CUDA MLP; CPU-resident data, fixed small batches, train-only scaling."""
import json
import pickle
import time
from pathlib import Path
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.metrics import mean_squared_error
import torch
from torch import nn
from .features import FEATURES

class MLP(nn.Module):
    def __init__(self, mean=None, scale=None):
        super().__init__()
        d = len(FEATURES)
        self.register_buffer("mean", torch.zeros(d) if mean is None else torch.as_tensor(mean, dtype=torch.float32))
        self.register_buffer("scale", torch.ones(d) if scale is None else torch.as_tensor(scale, dtype=torch.float32))
        self.net = nn.Sequential(nn.LayerNorm(d), nn.Linear(d, 64), nn.GELU(),
            nn.Linear(64, 32), nn.GELU(), nn.Linear(32, 1))

    def forward(self, x):
        return self.net((x-self.mean)/self.scale).squeeze(-1)

def require_cuda():
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA training is required. Install the CUDA PyTorch wheel; no silent CPU fallback.")
    torch.set_num_threads(2)
    torch.cuda.set_per_process_memory_fraction(0.6)
    props = torch.cuda.get_device_properties(0)
    return {"gpu": props.name, "vram_bytes": props.total_memory, "torch": str(torch.__version__),
        "cuda": torch.version.cuda, "device": "cuda:0"}

def train(train_frame, val_frame, out: Path, epochs=25, seed=7, batch_size=128):
    out.mkdir(parents=True, exist_ok=True)
    hardware = require_cuda()
    torch.manual_seed(seed)
    np.random.seed(seed)
    x = train_frame[FEATURES].to_numpy(np.float32)
    y = train_frame.target.to_numpy(np.float32)
    vx = val_frame[FEATURES].to_numpy(np.float32)
    vy = val_frame.target.to_numpy(np.float32)
    tree = HistGradientBoostingRegressor(max_iter=80, max_leaf_nodes=15, l2_regularization=1,
        early_stopping=False, random_state=seed).fit(x, y)
    with (out/"tree.pkl").open("wb") as f:
        pickle.dump(tree, f)
    results = {"hardware": hardware, "train_rows": len(x), "validation_rows": len(vx),
        "tree_validation_mse": mean_squared_error(vy, tree.predict(vx)), "batch_size": batch_size}
    predictors = {"sage_tree": tree.predict}
    mean, scale = x.mean(axis=0), np.maximum(x.std(axis=0), .001)
    # Pairs only from the SAME packet/current/time opportunity group.
    pair_indices = []
    for indices in train_frame.reset_index(drop=True).groupby("group").indices.values():
        for a, b in zip(indices[:-1], indices[1:]):
            if abs(y[a]-y[b]) > .001:
                pair_indices.append((a, b, float(np.sign(y[a]-y[b]))))
    results["ranking_pairs"] = len(pair_indices)
    for objective in ("regression", "ranking"):
        if objective == "ranking" and not pair_indices:
            continue
        torch.manual_seed(seed)
        model = MLP(mean, scale).cuda()
        optimizer = torch.optim.AdamW(model.parameters(), lr=.001, weight_decay=.001)
        tx, ty = torch.from_numpy(x), torch.from_numpy(y)  # Entire dataset stays on CPU.
        generator = torch.Generator().manual_seed(seed)
        best, best_state = float("inf"), None
        torch.cuda.reset_peak_memory_stats()
        started = time.perf_counter()
        for epoch in range(epochs):
            model.train()
            for idx in torch.randperm(len(tx), generator=generator).split(batch_size):
                optimizer.zero_grad(set_to_none=True)
                loss = nn.functional.mse_loss(model(tx[idx].cuda()), ty[idx].cuda())
                if objective == "ranking":
                    chosen = np.asarray(pair_indices)[np.random.randint(0, len(pair_indices), min(batch_size, len(pair_indices)))]
                    a, b = chosen[:, 0].astype(int), chosen[:, 1].astype(int)
                    sign = torch.tensor(chosen[:, 2], dtype=torch.float32, device="cuda")
                    delta = model(tx[a].cuda())-model(tx[b].cuda())
                    # Regression anchor keeps scores calibrated to the HOLD threshold.
                    loss = loss + .1*nn.functional.softplus(-sign*delta).mean()
                loss.backward()
                nn.utils.clip_grad_norm_(model.parameters(), 1)
                optimizer.step()
            model.eval()
            with torch.no_grad():
                pred = np.concatenate([model(torch.from_numpy(z).cuda()).cpu().numpy()
                    for z in np.array_split(vx, max(1, int(np.ceil(len(vx)/batch_size))))])
            mse = mean_squared_error(vy, pred)
            if mse < best:
                best = mse
                best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
        torch.cuda.synchronize()
        results[objective] = {"validation_mse": best, "training_seconds": time.perf_counter()-started,
            "peak_allocated_bytes": torch.cuda.max_memory_allocated(),
            "peak_reserved_bytes": torch.cuda.max_memory_reserved(),
            "parameters": sum(p.numel() for p in model.parameters()), "epochs": epochs}
        torch.save({"state_dict": best_state, "features": FEATURES, "objective": objective,
            "hardware": hardware}, out/f"{objective}.pt")
        model.load_state_dict(best_state)
        model.cpu().eval()
        def predict(values, model=model):
            with torch.no_grad():
                return model(torch.as_tensor(values, dtype=torch.float32)).numpy()
        predictors[f"sage_{objective}"] = predict
    (out/"training.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return predictors, results

def export(checkpoint: Path, output: Path, sample):
    import onnx
    import onnxruntime as ort
    bundle = torch.load(checkpoint, map_location="cpu", weights_only=True)
    if bundle["features"] != FEATURES:
        raise ValueError("Feature schema mismatch")
    model = MLP().eval()
    model.load_state_dict(bundle["state_dict"])
    sample = np.asarray(sample, dtype=np.float32)
    torch.onnx.export(model, torch.from_numpy(sample[:2]), str(output), input_names=["features"],
        output_names=["relay_value"], dynamic_axes={"features": {0: "batch"}, "relay_value": {0: "batch"}},
        opset_version=17, dynamo=False)
    onnx.checker.check_model(onnx.load(output))
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 2
    session = ort.InferenceSession(str(output), sess_options=opts, providers=["CPUExecutionProvider"])
    expected = model(torch.from_numpy(sample)).detach().numpy()
    actual = session.run(None, {"features": sample})[0]
    np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
    for _ in range(25):
        session.run(None, {"features": sample[:1]})
    times = []
    for _ in range(500):
        start = time.perf_counter_ns()
        session.run(None, {"features": sample[:1]})
        times.append((time.perf_counter_ns()-start)/1e6)
    report = {"features": len(FEATURES), "feature_names": FEATURES, "parameters": sum(p.numel() for p in model.parameters()),
        "file_bytes": output.stat().st_size, "max_abs_error": float(np.max(np.abs(actual-expected))),
        "cpu_p50_ms": float(np.percentile(times, 50)), "cpu_p95_ms": float(np.percentile(times, 95)),
        "samples": len(sample), "provider": "CPUExecutionProvider", "threads": 2,
        "note": "Laptop single-row warm inference; not Android performance"}
    output.with_suffix(".json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report

"""Deployment-style SAGE v1 loader: ONNX only, no torch/oracle imports, fail-safe to the heuristic."""
from __future__ import annotations
import json
import logging
from pathlib import Path
import numpy as np
from .state import FEATURES
from .policies import Sage, Heuristic

OUTPUTS = ["u_self", "u_peer", "reach_self", "reach_peer", "gain_replicate", "gain_forward"]


def onnx_predictor(path: Path):
    import onnxruntime as ort
    meta = json.loads(path.with_suffix(".json").read_text())
    if meta.get("feature_names") != FEATURES:
        raise ValueError("SAGE ONNX feature schema missing or mismatched")
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    session = ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])
    if session.get_inputs()[0].shape[1] != len(FEATURES):
        raise ValueError("SAGE ONNX input width mismatch")

    def predict(x):
        out = session.run(None, {"features": np.asarray(x, dtype=np.float32)})[0]
        return {k: out[:, j] for j, k in enumerate(OUTPUTS)}
    return predict


def load_policy(path: Path, threshold: float = -1.0, per_byte: bool = True):
    """Return a SAGE policy, or the deterministic heuristic if the model cannot be loaded."""
    try:
        return Sage(onnx_predictor(Path(path)), "sage_onnx", threshold=threshold, per_byte=per_byte)
    except Exception:
        logging.warning("SAGE model could not load; using deterministic heuristic", exc_info=True)
        return Heuristic()

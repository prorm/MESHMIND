"""Fail-safe local ONNX loading without importing training/oracle code."""
import json
import logging
from pathlib import Path
import numpy as np
from .features import FEATURES
from .routing import Policy

def load_policy(path: Path, threshold=0.02):
    try:
        import onnxruntime as ort
        metadata = json.loads(path.with_suffix(".json").read_text())
        if metadata.get("feature_names") != FEATURES:
            raise ValueError("ONNX feature schema missing or mismatched")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        if session.get_inputs()[0].shape[1] != len(FEATURES):
            raise ValueError("ONNX input dimension mismatch")
        def predict(x):
            return session.run(None, {"features": np.asarray(x, dtype=np.float32)})[0]
        return Policy("sage_onnx", predict, threshold)
    except Exception:
        logging.warning("SAGE model could not load; using heuristic", exc_info=True)
        return Policy("heuristic")

import numpy as np
import torch
from sage.v1.model import SageNet, NumpySageNet, parameter_count
from sage.v1.state import FEATURES


def test_numpy_forward_matches_torch_and_model_is_small():
    torch.manual_seed(0)
    rng = np.random.default_rng(0)
    x = rng.normal(size=(64, len(FEATURES))).astype(np.float32)
    model = SageNet(x.mean(0), x.std(0) + 0.1).eval()
    with torch.no_grad():
        ref = model(torch.from_numpy(x)).numpy()
    out = NumpySageNet(model.state_dict())(x)
    np.testing.assert_allclose(out["u_self"], ref[:, 0], atol=1e-5)
    np.testing.assert_allclose(out["u_peer"], ref[:, 1], atol=1e-5)
    np.testing.assert_allclose(out["gain_replicate"], ref[:, 4], atol=1e-5)
    np.testing.assert_allclose(out["gain_forward"], ref[:, 5], atol=1e-5)
    assert parameter_count(model) < 50_000


def test_siamese_value_head_is_symmetric():
    """Swapping self and peer views swaps the value outputs (shared encoder)."""
    from sage.v1.state import SLICES
    torch.manual_seed(1)
    x = torch.randn(16, len(FEATURES))
    swapped = x.clone()
    swapped[:, SLICES["self"]], swapped[:, SLICES["peer"]] = x[:, SLICES["peer"]], x[:, SLICES["self"]]
    model = SageNet().eval()
    with torch.no_grad():
        a, b = model(x), model(swapped)
    torch.testing.assert_close(a[:, 0], b[:, 1])
    torch.testing.assert_close(a[:, 1], b[:, 0])


def test_exported_onnx_matches_checkpoint_and_missing_model_falls_back(tmp_path):
    from pathlib import Path
    import pytest
    root = Path(__file__).resolve().parents[1] / "outputs" / "v1" / "checkpoints"
    if not (root / "sage_net.onnx").exists():
        pytest.skip("run the v1 training stage first")
    from sage.v1.inference import load_policy, onnx_predictor
    from sage.v1 import policies as P
    bundle = torch.load(root / "sage_net.pt", map_location="cpu", weights_only=True)
    x = np.random.default_rng(3).normal(size=(32, len(FEATURES))).astype(np.float32)
    ref = NumpySageNet(bundle["state_dict"])(x)
    out = onnx_predictor(root / "sage_net.onnx")(x)
    for k in ("u_self", "u_peer", "gain_replicate", "gain_forward"):
        np.testing.assert_allclose(out[k], ref[k], atol=1e-4)
    assert isinstance(load_policy(root / "sage_net.onnx"), P.Sage)
    assert isinstance(load_policy(tmp_path / "missing.onnx"), P.Heuristic)

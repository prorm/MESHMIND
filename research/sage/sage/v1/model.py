"""SAGE-Net: a tiny siamese relay-value network (~15k parameters).

Input: one row of ``state.FEATURES`` = [packet | self node view | peer node view | pair].
A shared encoder embeds (packet, node view) for the holder and the candidate peer;
a value head predicts each node's delivery utility/reachability and a gain head
predicts the marginal utility of giving the peer a copy (and of a handoff).
The same weights score holder and peer, so "who is the better carrier" is a
symmetric comparison rather than two unrelated regressions.
"""
from __future__ import annotations
import numpy as np
import torch
from torch import nn
from .state import FEATURES, SLICES, N_PACKET, N_NODE, N_PAIR

HEADS = ["u_self", "u_peer", "reach_self", "reach_peer", "gain_replicate", "gain_forward"]


class SageNet(nn.Module):
    def __init__(self, mean=None, scale=None, hidden=64, embed=48):
        super().__init__()
        d = len(FEATURES)
        self.register_buffer("mean", torch.zeros(d) if mean is None else torch.as_tensor(mean, dtype=torch.float32))
        self.register_buffer("scale", torch.ones(d) if scale is None else torch.as_tensor(scale, dtype=torch.float32))
        din = N_PACKET + N_NODE
        self.encoder = nn.Sequential(nn.LayerNorm(din), nn.Linear(din, hidden), nn.GELU(),
            nn.Linear(hidden, embed), nn.GELU())
        self.value = nn.Linear(embed, 2)
        self.gain = nn.Sequential(nn.Linear(3 * embed + N_PAIR, hidden), nn.GELU(), nn.Linear(hidden, 2))

    def forward(self, x):
        z = (x - self.mean) / self.scale
        p, s, q, r = z[:, SLICES["packet"]], z[:, SLICES["self"]], z[:, SLICES["peer"]], z[:, SLICES["pair"]]
        hs = self.encoder(torch.cat([p, s], 1))
        hp = self.encoder(torch.cat([p, q], 1))
        vs, vp = self.value(hs), self.value(hp)
        g = self.gain(torch.cat([hs, hp, hs * hp, r], 1))
        return torch.stack([torch.sigmoid(vs[:, 0]), torch.sigmoid(vp[:, 0]), vs[:, 1], vp[:, 1],
            torch.sigmoid(g[:, 0]), torch.tanh(g[:, 1])], 1)


def parameter_count(model):
    return sum(p.numel() for p in model.parameters())


class NumpySageNet:
    """Bit-for-bit-tolerant CPU re-implementation for fast, torch-free simulation workers."""

    def __init__(self, state_dict):
        sd = {k: v.detach().cpu().numpy().astype(np.float64) for k, v in state_dict.items()}
        self.sd = sd

    @staticmethod
    def _ln(x, w, b, eps=1e-5):
        mu = x.mean(1, keepdims=True)
        var = x.var(1, keepdims=True)
        return (x - mu) / np.sqrt(var + eps) * w + b

    @staticmethod
    def _gelu(x):
        from scipy.special import erf
        return 0.5 * x * (1 + erf(x / np.sqrt(2)))

    def _enc(self, x):
        sd = self.sd
        h = self._ln(x, sd["encoder.0.weight"], sd["encoder.0.bias"])
        h = self._gelu(h @ sd["encoder.1.weight"].T + sd["encoder.1.bias"])
        return self._gelu(h @ sd["encoder.3.weight"].T + sd["encoder.3.bias"])

    def __call__(self, x):
        sd = self.sd
        z = (np.asarray(x, np.float64) - sd["mean"]) / sd["scale"]
        p, s, q, r = z[:, SLICES["packet"]], z[:, SLICES["self"]], z[:, SLICES["peer"]], z[:, SLICES["pair"]]
        hs = self._enc(np.concatenate([p, s], 1))
        hp = self._enc(np.concatenate([p, q], 1))
        vs = hs @ sd["value.weight"].T + sd["value.bias"]
        vp = hp @ sd["value.weight"].T + sd["value.bias"]
        g = self._gelu(np.concatenate([hs, hp, hs * hp, r], 1) @ sd["gain.0.weight"].T + sd["gain.0.bias"])
        g = g @ sd["gain.2.weight"].T + sd["gain.2.bias"]
        sig = lambda v: 1 / (1 + np.exp(-v))
        return {"u_self": sig(vs[:, 0]), "u_peer": sig(vp[:, 0]), "reach_self": sig(vs[:, 1]),
            "reach_peer": sig(vp[:, 1]), "gain_replicate": sig(g[:, 0]), "gain_forward": np.tanh(g[:, 1])}


class TreePredictor:
    """Wraps scikit-learn regressors (one per head) with the same dict interface."""

    def __init__(self, models):
        self.models = models

    def __call__(self, x):
        out = {k: m.predict(x) for k, m in self.models.items()}
        out.setdefault("u_self", np.full(len(x), 0.5))
        return out

"""Leakage-free, incrementally updated contact history and SAGE v1 features.

``NodeState`` only ever receives contacts through ``observe_bin`` after the
bin has finished, in non-decreasing time order. It holds no reference to the
trace, the oracle or any future data. Everything a feature uses is something
a phone could know locally, or receive from the peer in the encounter
handshake (the peer's own summaries, like a PRoPHET predictability vector).
"""
from __future__ import annotations
import math
import numpy as np

RATE_HALF_LIVES = (600.0, 3600.0, 21600.0, 86400.0)
PAIR_HALF_LIVES = (3600.0, 86400.0)
PROPHET_INIT, PROPHET_BETA, PROPHET_GAMMA = 0.75, 0.25, 0.98
PROPHET_UNITS = (60.0, 600.0)          # fast and slow aging time units (seconds)
NEVER = 30 * 86400.0                   # "never met" recency cap

PACKET_FEATURES = ["p_media", "p_control", "p_text", "p_gateway_dest", "p_log_size", "p_age_frac",
    "p_remaining_log", "p_remaining_frac", "p_replicas_log", "ctx_bin_log"]
NODE_FEATURES = ["hops_left", "rate_10m", "rate_1h", "rate_6h", "rate_24h", "uniq_10m", "uniq_1h", "uniq_24h",
    "since_contact_log", "queue_frac", "queue_count_log", "battery", "is_gateway",
    "d_since_log", "d_never", "d_count_log", "d_rate_1h", "d_rate_24h", "d_prophet_fast", "d_prophet_slow",
    "d_hour_routine", "d_expected_meets"]
PAIR_FEATURES = ["pr_count_log", "pr_rate_1h", "pr_rate_24h", "pr_since_log", "pr_jaccard_1h"]
FEATURES = (PACKET_FEATURES + ["self_" + f for f in NODE_FEATURES] + ["peer_" + f for f in NODE_FEATURES]
    + PAIR_FEATURES)
N_PACKET, N_NODE, N_PAIR = len(PACKET_FEATURES), len(NODE_FEATURES), len(PAIR_FEATURES)
SLICES = {"packet": slice(0, N_PACKET), "self": slice(N_PACKET, N_PACKET + N_NODE),
    "peer": slice(N_PACKET + N_NODE, N_PACKET + 2 * N_NODE), "pair": slice(N_PACKET + 2 * N_NODE, len(FEATURES))}


class NodeState:
    """Contact history for ``n`` nodes plus a virtual column ``n`` meaning "any live gateway"."""

    def __init__(self, n: int, bin_seconds: int):
        self.n, self.bin = n, float(bin_seconds)
        m = n + 1
        self.last_met = np.full((n, m), -np.inf)
        self.count = np.zeros((n, m), np.float32)
        self.pair_rate = np.zeros((len(PAIR_HALF_LIVES), n, m), np.float32)
        self.prophet = np.zeros((len(PROPHET_UNITS), n, m), np.float32)
        self.hour = np.zeros((n, m, 24), np.float32)
        self.node_rate = np.zeros((n, len(RATE_HALF_LIVES)), np.float32)
        self.last_contact = np.full(n, -np.inf)
        self.days = set()
        self.now = -np.inf

    def copy(self):
        other = NodeState.__new__(NodeState)
        for k, v in self.__dict__.items():
            setattr(other, k, v.copy() if hasattr(v, "copy") else v)
        return other

    def _decay(self, t):
        if not math.isfinite(self.now):
            self.now = t
            return
        dt = t - self.now
        if dt < 0:
            raise ValueError("History cannot move backwards")
        if dt == 0:
            return
        for k, h in enumerate(PAIR_HALF_LIVES):
            self.pair_rate[k] *= np.float32(0.5 ** (dt / h))
        for k, h in enumerate(RATE_HALF_LIVES):
            self.node_rate[:, k] *= np.float32(0.5 ** (dt / h))
        for k, unit in enumerate(PROPHET_UNITS):
            self.prophet[k] *= np.float32(PROPHET_GAMMA ** (dt / unit))
        self.now = t

    def observe_bin(self, start: float, a: np.ndarray, b: np.ndarray, gateway_up: np.ndarray | None = None):
        """Record a COMPLETED bin [start, start+bin). ``gateway_up``: bool per node at bin end."""
        end = start + self.bin
        if end < self.now:
            raise ValueError("Cannot observe a bin earlier than current history")
        self._decay(end)
        if len(a) == 0:
            return
        u = np.concatenate([a, b]).astype(np.int64)
        v = np.concatenate([b, a]).astype(np.int64)
        if gateway_up is not None and gateway_up.any():
            g = gateway_up[v]
            u = np.concatenate([u, u[g]])
            v = np.concatenate([v, np.full(int(g.sum()), self.n)])
            # Several gateway neighbours in one bin count once.
            pairs = np.unique(np.stack([u, v], 1), axis=0)
            u, v = pairs[:, 0], pairs[:, 1]
        new = self.last_met[u, v] < start - 0.5 * self.bin       # encounter START (not continuing)
        np.add.at(self.count, (u, v), 1)
        for k in range(len(PAIR_HALF_LIVES)):
            np.add.at(self.pair_rate[k], (u, v), 1)
        hour = int((start % 86400) // 3600)
        np.add.at(self.hour[:, :, hour], (u, v), 1)
        self.days.add(int(start // 86400))
        nodes, counts = np.unique(u[v < self.n], return_counts=True)
        self.node_rate[nodes] += counts[:, None].astype(np.float32)
        self.last_contact[nodes] = end
        if new.any():
            nu, nv = u[new], v[new]
            for k in range(len(PROPHET_UNITS)):
                p = self.prophet[k]
                old = p.copy()
                p[nu, nv] = old[nu, nv] + (1 - old[nu, nv]) * PROPHET_INIT
                real = nv < self.n
                if real.any():
                    rows = old[nv[real]] * (p[nu[real], nv[real]] * PROPHET_BETA)[:, None]
                    np.maximum.at(p, nu[real], rows)
                    p[np.arange(self.n), np.arange(self.n)] = 0
        self.last_met[u, v] = end

    # ----- features -------------------------------------------------------
    def node_view(self, nodes, dests, t, hops_left, remaining, queue_frac, queue_count, battery, is_gateway):
        """Vectorized NODE_FEATURES for (node, destination column) rows at decision time t."""
        nodes = np.asarray(nodes, np.int64)
        dests = np.asarray(dests, np.int64)
        out = np.empty((len(nodes), N_NODE), np.float32)
        out[:, 0] = hops_left
        out[:, 1:5] = np.log1p(self.node_rate[nodes])
        uniq, inv = np.unique(nodes, return_inverse=True)       # N-wide work once per distinct node
        age = t - self.last_met[uniq, :self.n]
        for k, w in enumerate((600.0, 3600.0, 86400.0)):
            out[:, 5 + k] = np.log1p((age <= w).sum(axis=1))[inv]
        out[:, 8] = np.log1p(np.minimum(t - self.last_contact[nodes], NEVER))
        out[:, 9] = queue_frac
        out[:, 10] = np.log1p(queue_count)
        out[:, 11] = battery
        out[:, 12] = is_gateway
        since = t - self.last_met[nodes, dests]
        out[:, 13] = np.log1p(np.minimum(since, NEVER))
        out[:, 14] = ~np.isfinite(since)
        out[:, 15] = np.log1p(self.count[nodes, dests])
        out[:, 16] = np.log1p(self.pair_rate[0][nodes, dests])
        out[:, 17] = np.log1p(self.pair_rate[1][nodes, dests])
        out[:, 18] = self.prophet[0][nodes, dests]
        out[:, 19] = self.prophet[1][nodes, dests]
        # Daily routine: past bins with dest during the hours this packet will still be alive.
        days = max(1, len(self.days) - 1)
        hours = np.arange(24)
        start_h = (t % 86400) // 3600
        span = np.clip(np.ceil(np.asarray(remaining, float) / 3600), 1, 24)
        span = np.broadcast_to(span, nodes.shape)
        mask = ((hours[None, :] - start_h) % 24) < span[:, None]
        out[:, 20] = np.log1p((self.hour[nodes, dests] * mask).sum(axis=1) / days)
        rate_per_s = self.pair_rate[1][nodes, dests] * math.log(2) / PAIR_HALF_LIVES[1]
        out[:, 21] = np.log1p(rate_per_s * remaining)
        return out

    def pair_view(self, a, b, t):
        a = np.asarray(a, np.int64)
        b = np.asarray(b, np.int64)
        out = np.empty((len(a), N_PAIR), np.float32)
        out[:, 0] = np.log1p(self.count[a, b])
        out[:, 1] = np.log1p(self.pair_rate[0][a, b])
        out[:, 2] = np.log1p(self.pair_rate[1][a, b])
        out[:, 3] = np.log1p(np.minimum(t - self.last_met[a, b], NEVER))
        pairs, inv = np.unique(np.stack([a, b], 1), axis=0, return_inverse=True)
        na = (t - self.last_met[pairs[:, 0], :self.n]) <= 3600
        nb = (t - self.last_met[pairs[:, 1], :self.n]) <= 3600
        inter = (na & nb).sum(axis=1)
        union = (na | nb).sum(axis=1)
        out[:, 4] = (inter / np.maximum(union, 1))[inv.reshape(-1)]
        return out


def packet_view(priority, gateway_dest, size, age, remaining, lifetime, replicas, bin_seconds):
    n = len(priority)
    out = np.empty((n, N_PACKET), np.float32)
    priority = np.asarray(priority)
    out[:, 0] = priority == 3
    out[:, 1] = priority == 1
    out[:, 2] = priority == 2
    out[:, 3] = gateway_dest
    out[:, 4] = np.log1p(size)
    out[:, 5] = np.asarray(age) / np.asarray(lifetime)
    out[:, 6] = np.log1p(np.maximum(remaining, 0))
    out[:, 7] = np.asarray(remaining) / np.asarray(lifetime)
    out[:, 8] = np.log1p(replicas)
    out[:, 9] = math.log(bin_seconds)
    return out

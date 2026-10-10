"""OFFLINE label oracle: hop-bounded earliest arrival for ALL holders in one reverse sweep.

Used only to create training labels and the "perfect knowledge" ceiling. It is
never imported by ``state.py``, ``policies.py`` or the inference wrapper.

Semantics match the v1 simulator (and the v0 ``TemporalOracle``):
* a transfer in bin [s, e) arrives at e, needs e < expiry and one remaining hop;
* no same-bin chains: the receiver can only forward in bins starting at >= e;
* a gateway that is up at a processed bin start s < expiry delivers its own
  "@gateway" packets locally at s without a radio hop; a transfer INTO a node
  that is a live gateway at e is delivered at e;
* contention, losses and buffers are ignored: labels are optimistic reachability.
"""
from __future__ import annotations
import math
import numpy as np


class ContactIndex:
    def __init__(self, start: np.ndarray, a: np.ndarray, b: np.ndarray, bin_seconds: float, n: int):
        order = np.argsort(start, kind="stable")
        self.start, self.a, self.b = start[order], a[order].astype(np.int64), b[order].astype(np.int64)
        self.bins, self.offsets = np.unique(self.start, return_index=True)
        self.offsets = np.append(self.offsets, len(self.start))
        self.bin, self.n = float(bin_seconds), n
        # Directed edges grouped per bin for fast slicing.
        lo, hi = self.offsets[:-1], self.offsets[1:]
        self.dir_from, self.dir_to, self.seg_rows, self.seg_starts = [], [], [], []
        for l, h in zip(lo, hi):
            frm = np.concatenate([self.a[l:h], self.b[l:h]])
            to = np.concatenate([self.b[l:h], self.a[l:h]])
            order = np.argsort(frm, kind="stable")     # presorted so per-sender minima use reduceat
            frm, to = frm[order], to[order]
            rows, starts = np.unique(frm, return_index=True)
            self.dir_from.append(frm)
            self.dir_to.append(to)
            self.seg_rows.append(rows)
            self.seg_starts.append(starts)


def sweep(index: ContactIndex, dest: int, expiry: float, hops: int, snapshots, gateway=None,
          size_ok: bool = True):
    """Return {T: EA[n, hops+1]} = earliest arrival when holding at node v from time T with h hops left.

    ``dest`` is a node index, or -1 for "any live gateway" (then ``gateway(t)`` -> bool[n]).
    ``snapshots``: departure times T to record (any order).
    """
    n = index.n
    best = np.full((n, hops + 1), np.inf)
    pending = sorted(set(float(t) for t in snapshots), reverse=True)
    out = {}
    last = int(np.searchsorted(index.bins, expiry, side="left"))   # bins with start < expiry
    for k in range(last - 1, -1, -1):
        s = float(index.bins[k])
        e = s + index.bin
        while pending and pending[0] > s:
            out[pending.pop(0)] = best.copy()
        new = best.copy()
        if dest < 0:
            up_s = gateway(s)
            if up_s.any():
                new[up_s, :] = s
        if size_ok and e < expiry and hops > 0:
            to = index.dir_to[k]
            if dest < 0:
                up_e = gateway(e)
                direct = up_e[to]
            else:
                direct = to == dest
            cand = best[to, :-1].copy()          # receiver has one fewer hop
            cand[direct, :] = e
            rows = index.seg_rows[k]
            new[rows, 1:] = np.minimum(new[rows, 1:], np.minimum.reduceat(cand, index.seg_starts[k], axis=0))
        best = new
    for t in pending:
        out[t] = best.copy()
    if dest >= 0:
        for arr in out.values():
            arr[dest, :] = np.nan                  # holder == destination is never labeled
    return out


def utility(arrival, expiry, lifetime):
    arrival = np.asarray(arrival, float)
    u = (expiry - arrival) / lifetime
    return np.where(np.isfinite(arrival) & (arrival < expiry), np.clip(u, 0, 1), 0.0)


def ceiling(index: ContactIndex, packets, hops, gateway):
    """Fraction of packets deliverable by a perfect-knowledge, unlimited-resource router."""
    reached = []
    for p in packets:
        dest = -1 if p.gateway_dest else p.destination
        ea = sweep(index, dest, p.expiry_time, hops, [p.creation_time], gateway)[float(p.creation_time)]
        reached.append(bool(np.isfinite(ea[p.source, hops]) and ea[p.source, hops] < p.expiry_time))
    return float(np.mean(reached)) if reached else math.nan

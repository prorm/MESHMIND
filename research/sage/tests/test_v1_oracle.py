import math
import numpy as np
import pytest
from sage.types import Contact
from sage.oracle import TemporalOracle
from sage.v1.oracle import ContactIndex, sweep, utility


def tiny(rows, n):
    s = np.array([r[0] for r in rows], float)
    return ContactIndex(s, np.array([r[1] for r in rows]), np.array([r[2] for r in rows]), 20, n)


def test_reverse_sweep_prefers_temporal_relay():
    # A=0 meets B=1 at t=0, B meets D=2 at t=40, A meets D at t=200.
    idx = tiny([(0, 0, 1), (40, 1, 2), (200, 0, 2)], 3)
    snap = sweep(idx, 2, 1000, 8, [20])[20.0]
    assert snap[1, 7] == 60          # B holding after the first bin delivers at 60
    assert snap[0, 8] == 220         # A retaining waits for its own meeting
    assert utility(snap[1, 7], 1000, 1000) > utility(snap[0, 8], 1000, 1000)


def test_hops_expiry_and_no_same_bin_chain():
    idx = tiny([(0, 0, 1), (0, 1, 2), (20, 0, 1), (40, 1, 2)], 3)
    snap = sweep(idx, 2, 1000, 8, [0])[0.0]
    assert snap[0, 2] == 60 and math.isinf(snap[0, 1])
    assert math.isinf(sweep(idx, 2, 60, 8, [0])[0.0][0, 2])   # arrival must precede expiry


def test_gateway_local_service_and_arrival():
    idx = tiny([(0, 0, 1), (40, 2, 3)], 4)
    gw = lambda t: np.array([False, True, False, t >= 40])
    snap = sweep(idx, -1, 1000, 8, [0], gw)[0.0]
    assert snap[0, 1] == 20          # 0 -> live gateway 1 arrives at bin end
    assert snap[3, 0] == 40          # gateway 3 delivers locally at a processed bin start
    assert snap[1, 0] == 0


@pytest.mark.parametrize("seed", range(40))
def test_matches_v0_dijkstra_oracle_on_random_temporal_graphs(seed):
    rng = np.random.default_rng(seed)
    n, bins = 7, 25
    rows = []
    for k in range(bins):
        for _ in range(rng.integers(0, 4)):
            a, b = rng.choice(n, 2, replace=False)
            rows.append((k * 20, int(a), int(b)))
    rows = sorted(set(rows))
    if not rows:
        return
    gateway_nodes = {int(rng.integers(n))}
    up = lambda node, t: node in gateway_nodes and (int(t // 60) % 2 == 0)
    v0 = TemporalOracle([Contact(s, s + 20, str(a), str(b)) for s, a, b in rows], 1e9,
        lambda node, t: up(int(node), t))
    idx = tiny(rows, n)
    gw = lambda t: np.array([up(i, t) for i in range(n)])
    expiry, hops = float(rng.integers(100, 520)), int(rng.integers(1, 5))
    starts = sorted({s for s, _, _ in rows})
    T = float(starts[rng.integers(len(starts))])
    for dest in range(n):
        snap = sweep(idx, dest, expiry, hops, [T])[T]
        for v in range(n):
            if v == dest:
                continue
            for h in range(hops + 1):
                ref = v0.earliest(str(v), str(dest), T, expiry, h)
                assert snap[v, h] == ref, (dest, v, h)
    snap = sweep(idx, -1, expiry, hops, [T], gw)[T]
    for v in range(n):
        for h in range(hops + 1):
            # v0 keeps only the earliest arrival per (node, hops); for gateways whose
            # status changes over time a LATER arrival can be better, so compare to
            # exhaustive flooding instead (documented v0 edge case).
            assert snap[v, h] == brute_force(rows, v, h, T, expiry, gw), ("gw", v, h)


def brute_force(rows, v, h, T, expiry, gw=None, dest=None):
    """Exhaustive forward flooding: each holder keeps its copy and its maximum remaining hops."""
    holders = {v: h}
    for s in sorted({r[0] for r in rows if r[0] >= T and r[0] < expiry}):
        e = s + 20
        if gw is not None and any(gw(s)[x] for x in holders):
            return float(s)
        new = dict(holders)
        for _, x, y in [r for r in rows if r[0] == s]:
            for p, q in ((x, y), (y, x)):
                if p in holders and holders[p] >= 1 and e < expiry:
                    if (gw is not None and gw(e)[q]) or q == dest:
                        return float(e)
                    new[q] = max(new.get(q, -1), holders[p] - 1)
        holders = new
    return math.inf


@pytest.mark.parametrize("seed", range(40))
def test_matches_exhaustive_flooding_for_node_destinations(seed):
    rng = np.random.default_rng(1000 + seed)
    n = 6
    rows = sorted({(k * 20, *map(int, rng.choice(n, 2, replace=False)))
        for k in range(20) for _ in range(rng.integers(0, 3))})
    if not rows:
        return
    idx = tiny(rows, n)
    expiry, hops = float(rng.integers(60, 420)), int(rng.integers(1, 4))
    T = float(rows[rng.integers(len(rows))][0])
    for dest in range(n):
        snap = sweep(idx, dest, expiry, hops, [T])[T]
        for v in range(n):
            if v != dest:
                for h in range(hops + 1):
                    assert snap[v, h] == brute_force(rows, v, h, T, expiry, dest=dest)

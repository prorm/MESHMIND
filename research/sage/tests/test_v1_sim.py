import numpy as np
import pytest
from sage.v1.traces import Episode
from sage.v1.sim import Scenario, World, Simulator, label_logs, CRITICAL, Copy
from sage.v1.state import NodeState, FEATURES, SLICES
from sage.v1 import policies as P


def random_episode(seed=0, n=24, bins=900, density=0.02, future_seed=None, cut=None):
    """Synthetic aligned 20 s contact trace. With future_seed, contacts after `cut` are re-drawn."""
    rng = np.random.default_rng(seed)
    rows = []
    for k in range(bins):
        r = rng if (future_seed is None or k * 20 < cut) else np.random.default_rng((future_seed, k))
        m = r.random((n, n)) < density
        a, b = np.nonzero(np.triu(m, 1))
        rows += [(k * 20.0, x, y) for x, y in zip(a, b)]
    # Guarantee every node is active early and the episode end is fixed.
    rows += [(0.0, i, (i + 1) % n) for i in range(n)] + [((bins - 1) * 20.0, 0, 1)]
    rows = sorted(set((s, min(x, y), max(x, y)) for s, x, y in rows if x != y))
    s = np.array([r[0] for r in rows])
    return Episode("synthetic/0", "synthetic", "test", 0, 20, [str(i) for i in range(n)], s,
        np.array([r[1] for r in rows], np.int32), np.array([r[2] for r in rows], np.int32))


SC = dict(rate_per_node_hour=6.0, lifetime=1800.0, node_bps=4096.0, max_packets=400)


def test_replay_is_deterministic_under_a_seed():
    ep = random_episode()
    m1, _ = Simulator(World(ep, Scenario(**SC), 3)).run(P.Epidemic())
    m2, _ = Simulator(World(ep, Scenario(**SC), 3)).run(P.Epidemic())
    assert m1 == m2
    m3, _ = Simulator(World(ep, Scenario(**SC), 4)).run(P.Epidemic())
    assert m3 != m1


def test_features_never_see_future_contacts():
    cut = 9000.0
    base = random_episode(1)
    alt = random_episode(1, future_seed=99, cut=cut)
    assert not np.array_equal(base.start, alt.start)
    logs = []
    for ep in (base, alt):
        w = World(ep, Scenario(**SC), 5)
        _, log = Simulator(w).run(P.Epidemic(), log_rate=1.0, log_cap=10 ** 6, log_seed=1)
        logs.append({(i, a, b, s): row for i, a, b, s, e, h, row in log if s + 20 <= cut})
    common = set(logs[0]) & set(logs[1])
    assert len(common) > 100
    for key in common:
        np.testing.assert_array_equal(logs[0][key], logs[1][key])


def test_state_rejects_time_travel():
    st = NodeState(3, 20)
    st.observe_bin(100.0, np.array([0]), np.array([1]))
    with pytest.raises(ValueError):
        st.observe_bin(40.0, np.array([0]), np.array([2]))


class Adversary:
    """A hostile model: refuses every copy, and fails loudly if it is ever shown a CRITICAL row."""

    def __call__(self, X):
        prio = X[:, SLICES["packet"]][:, :3]
        assert (prio.sum(1) == 1).all(), "CRITICAL packet reached the model"
        n = len(X)
        return {"u_self": np.zeros(n), "u_peer": np.zeros(n), "gain_replicate": np.full(n, -1.0)}


def test_ml_cannot_suppress_or_delay_critical():
    ep = random_episode(2)
    sc = Scenario(**(SC | dict(critical_fraction=1.0)))
    w = World(ep, sc, 7)
    assert (w.p_prio == CRITICAL).all()
    sage, _ = Simulator(w).run(P.Sage(Adversary(), threshold=0.0))
    flood, _ = Simulator(w).run(P.Epidemic())
    for k in ("critical_delivery_ratio", "critical_p50_latency", "critical_p95_latency", "transmissions"):
        assert sage[k] == flood[k]


def test_adversarial_model_never_sees_critical_in_mixed_traffic():
    w = World(random_episode(3), Scenario(**(SC | dict(critical_fraction=0.3))), 8)
    m, _ = Simulator(w).run(P.Sage(Adversary(), threshold=0.0))
    assert m["model_rows"] > 0 and m["critical_delivery_ratio"] > 0


def test_model_failure_falls_back_to_heuristic():
    def broken(X):
        raise RuntimeError("corrupt model")
    w = World(random_episode(4), Scenario(**SC), 9)
    pol = P.Sage(broken)
    m, _ = Simulator(w).run(pol)
    ref, _ = Simulator(w).run(P.Heuristic())
    assert pol.fallbacks > 0
    assert m["normal_delivery_ratio"] > 0
    assert abs(m["normal_delivery_ratio"] - ref["normal_delivery_ratio"]) < 0.05


def test_eviction_never_drops_critical_for_ordinary_traffic():
    w = World(random_episode(5), Scenario(**(SC | dict(queue_bytes=4096, critical_fraction=0.3,
        media_fraction=0.0, rate_per_node_hour=30.0))), 10)
    sim = Simulator(w)
    original = sim.run
    m, _ = original(P.Epidemic())
    assert m["queue_drops"] > 0
    pol = P.Epidemic()
    victims = [Copy(i) for i in range(w.packets)]
    order = pol.eviction_order([c for c in victims if w.p_prio[c.i] != CRITICAL], w)
    assert all(w.p_prio[c.i] != CRITICAL for c in order)


def test_ack_gossip_reduces_redundant_bytes():
    ep = random_episode(6, density=0.04)
    on, _ = Simulator(World(ep, Scenario(**SC), 11)).run(P.Epidemic())
    off, _ = Simulator(World(ep, Scenario(**(SC | dict(ack_gossip=False))), 11)).run(P.Epidemic())
    assert on["total_bytes"] < off["total_bytes"]
    assert on["duplicate_bytes"] <= off["duplicate_bytes"]


def test_labels_are_consistent_with_oracle_semantics():
    w = World(random_episode(7), Scenario(**SC), 12)
    _, logs = Simulator(w).run(P.Epidemic(), log_rate=1.0, log_cap=5000, log_seed=2)
    X, meta = label_logs(w, logs)
    assert X.shape[1] == len(FEATURES)
    assert (meta["gain_replicate"] >= -1e-12).all()          # an extra copy never hurts the oracle
    assert (meta["gain_replicate"] >= meta["gain_forward"] - 1e-12).all()
    assert ((meta["u_self"] > 0) == (meta["reach_self"] > 0)).all()


def test_epidemic_never_exceeds_perfect_knowledge_ceiling():
    w = World(random_episode(8), Scenario(**(SC | dict(loss=0.0))), 13)
    ceiling = w.oracle_ceiling()
    for pol in (P.Epidemic(), P.SprayWait(8), P.Direct()):
        m, _ = Simulator(w).run(pol)
        assert m["delivery_ratio"] <= ceiling.mean() + 1e-12

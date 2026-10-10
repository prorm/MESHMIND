import math
from dataclasses import replace
import numpy as np
import pandas as pd
import pytest
from sage.types import Contact, Packet, Copy, Priority
from sage.oracle import TemporalOracle
from sage.features import History, FEATURES
from sage.datasets import validate, load
from sage.routing import Policy
from sage.simulation import Replay, Scenario

def packet(**kw):
    return replace(Packet("p", "A", "D", 0, 100, 8, 1, Priority.TEXT), **kw)

def trace(rows):
    return pd.DataFrame([(a, b, c, d, "tiny", "test") for a,b,c,d in rows],
        columns=["timestamp_start", "timestamp_end", "node_a", "node_b", "dataset", "context"])

def test_oracle_prefers_temporal_relay():
    oracle = TemporalOracle([Contact(1, 2, "A", "B"), Contact(5, 6, "B", "D"), Contact(50, 51, "A", "D")])
    assert oracle.gain(packet(), "A", "B", 2, 0) == pytest.approx(.45)

def test_oracle_order_expiry_hops_and_unreachable():
    oracle = TemporalOracle([Contact(0, 20, "B", "D"), Contact(20, 40, "A", "B")])
    assert math.isinf(oracle.earliest("A", "D", 0, 100, 8))
    oracle = TemporalOracle([Contact(0,20,"A","B"), Contact(20,40,"B","D")])
    assert oracle.earliest("A", "D", 0, 100, 2) == 40
    assert math.isinf(oracle.earliest("A", "D", 0, 100, 1))
    assert math.isinf(oracle.earliest("A", "D", 0, 40, 8))
    assert math.isinf(oracle.earliest("A", "D", 0, 100, 8, size=1000000))

def test_no_same_bin_chain_and_gateway():
    contacts = [Contact(0,20,"A","B"), Contact(0,20,"B","D")]
    oracle = TemporalOracle(contacts, gateways=lambda n,t: n == "B")
    assert math.isinf(oracle.earliest("A", "D", 0, 100, 8))
    assert oracle.earliest("A", "@gateway", 0, 100, 8) == 20

def test_oracle_gateway_can_wait_for_service_without_a_hop():
    oracle = TemporalOracle([Contact(40,60,"B","D")], gateways=lambda n,t: n == "A" and t >= 40)
    assert oracle.earliest("A", "@gateway", 0, 100, 0) == 40
    assert math.isinf(oracle.earliest("A", "@gateway", 0, 40, 0))

def test_features_cannot_observe_future():
    h = History()
    with pytest.raises(ValueError):
        h.observe([Contact(0,20,"A","B")], 0, lambda *args: False)
    queues, battery = {"A": {}, "B": {}}, {"A": 1., "B": 1.}
    before = h.vector(Copy(packet()), "A", "B", 0, queues, battery)
    # Future oracle contents cannot affect features; they are disconnected objects.
    TemporalOracle([Contact(50,70,"A","D")])
    np.testing.assert_array_equal(before, h.vector(Copy(packet()), "A", "B", 0, queues, battery))
    assert len(before) == len(FEATURES)

def test_critical_bypasses_model_and_caps():
    def fail(x):
        raise AssertionError("CRITICAL must never access the model")
    policy = Policy("sage", fail, replica_limit=0)
    cp = Copy(packet(priority=Priority.CRITICAL), replication_count=999)
    assert policy.decide(cp, "A", "B", 0, History(), np.zeros(len(FEATURES))).action == "REPLICATE"
    assert policy.fallbacks == 0

def test_model_failure_and_nan_fallback():
    for predictor in (None, lambda x: [float("nan")]):
        p = Policy("sage", predictor)
        out = p.decide(Copy(packet()), "A", "B", 0, History(), np.zeros(len(FEATURES)))
        assert out.action == "HOLD" and p.fallbacks == 1

def test_loader_end_stamps(tmp_path):
    f = tmp_path/"contacts.dat"
    f.write_text("20 2 1\n40 1 2\n")
    result = load("hypertext", f)
    assert result.timestamp_start.tolist() == [0.,20.]
    assert result.node_a.tolist() == ["1","1"]
    assert validate(result)["nodes"] == 2
    f.write_text("20 1 1\n")
    with pytest.raises(ValueError):
        load("hypertext", f)

def test_replay_determinism_and_priority():
    frame = trace([(0,20,"A","D"),(20,40,"A","D"),(80,100,"A","D")])
    sim = Replay(frame, Scenario(packets=1,lifetime=50,loss=0,bytes_per_second=1,gateway_count=0), 1)
    sim.packets = [packet(packet_id="normal",size_bytes=20), packet(packet_id="sos",size_bytes=20,priority=Priority.CRITICAL)]
    first = sim.run(Policy("epidemic"))
    second = sim.run(Policy("epidemic"))
    assert first == second
    assert first[2][0][1] == "sos"
    assert first[0]["critical_delivery_ratio"] == 1

def test_critical_preempts_queue_and_no_same_slot_forward():
    frame = trace([(0,20,"A","B"),(0,20,"B","D"),(60,80,"B","D"),(100,120,"A","D")])
    sim = Replay(frame, Scenario(packets=1,lifetime=90,loss=0,queue_bytes=20,gateway_count=0), 1)
    sim.packets = [packet(packet_id="n",size_bytes=20),packet(packet_id="c",size_bytes=20,priority=Priority.CRITICAL)]
    metrics, _, sends = sim.run(Policy("epidemic"))
    assert metrics["queue_drops"] >= 1
    assert metrics["critical_p50_latency"] == 80
    assert not any(t == 0 and a == "B" for t, _, a, _, _ in sends)

def test_spray_token_conservation():
    frame = trace([(0,20,"A","B"),(0,20,"A","C"),(20,40,"A","E"),(100,120,"A","D")])
    sim = Replay(frame, Scenario(packets=1,lifetime=90,loss=0,gateway_count=0), 1)
    sim.packets = [packet(expiry_time=100)]
    _, _, sends = sim.run(Policy("spray"))
    assert len([x for x in sends if x[2] == "A"]) == 2

def test_history_windows_and_future_independence():
    h = History()
    h.observe([Contact(0,20,"A","D")], 20, lambda n,t: False)
    queues, battery = {"A": {}, "B": {}}, {"A": 1., "B": 1.}
    x = dict(zip(FEATURES, h.vector(Copy(packet()), "A", "B", 20, queues, battery)))
    assert x["self_unique_30"] == pytest.approx(.01)
    assert x["self_destination_rate"] == pytest.approx(1/600)
    assert x["peer_destination_rate"] == 0
    # Even if a caller accidentally asks for an earlier snapshot, future history
    # records are excluded by timestamp rather than exposed to that snapshot.
    x = dict(zip(FEATURES, h.vector(Copy(packet()), "A", "B", 0, queues, battery)))
    assert x["self_encounters_30"] == 0
    assert h.recent("A",621) == []

def test_invalid_timestamps_and_ids():
    for row in [(20,0,"A","B"),(0,float("nan"),"A","B"),(0,20," A","B")]:
        with pytest.raises(ValueError):
            validate(trace([row]))

def test_missing_model_load_is_safe(tmp_path):
    from sage.inference import load_policy
    policy = load_policy(tmp_path/"missing.onnx")
    assert policy.name == "heuristic"
    assert policy.decide(Copy(packet(priority=Priority.CRITICAL)),"A","B",0,History(),
        np.zeros(len(FEATURES))).action == "REPLICATE"

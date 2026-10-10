"""The field-telemetry adapter parses Debug exports from several phones into one contact trace."""
from sage.v1.traces import load_meshmind_field


def _export(path, me, rows):
    body = ["MeshMind debug export", "", "# sage_contacts v1 bin_seconds=20.0", "bin_start_local_s,self,peer,gateway"]
    body += [f"{t},{me},{peer},{gw}" for t, peer, gw in rows]
    body += ["# sage_decisions v1 eco_threshold=0.005", "at_ms,packet,type,peer,gain_replicate,u_self,u_peer,would_skip,micros"]
    path.write_text("\n".join(body) + "\n", encoding="utf-8")


def test_two_phones_merge_into_one_symmetric_trace(tmp_path):
    a, b = tmp_path / "a.txt", tmp_path / "b.txt"
    _export(a, "A-1", [(1000, "B-2", 0), (1020, "B-2", 0), (1020, "@gateway", 1)])
    _export(b, "B-2", [(1001, "A-1", 0), (1040, "C-3", 0)])     # B's clock is 1 s ahead
    frame, report = load_meshmind_field([a, b])
    assert report["phones"] == ["A-1", "B-2"]
    assert report["gateway_bins"] == 1
    pairs = list(zip(frame.timestamp_start, frame.node_a, frame.node_b))
    assert pairs == [(1000.0, "A-1", "B-2"), (1020.0, "A-1", "B-2"), (1040.0, "B-2", "C-3")]
    assert (frame.timestamp_end - frame.timestamp_start == 20).all()

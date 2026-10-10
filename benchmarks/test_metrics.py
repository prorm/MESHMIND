"""Unit fixtures for the statistics/evidence checks; never included in phone measurements."""
import tempfile
from pathlib import Path
import unittest

from benchmark_all import Session, SystemFailure, quantile


class MetricsTests(unittest.TestCase):
    def test_type7_known_values(self):
        self.assertEqual(quantile([], .95), None)
        self.assertEqual(quantile([12], .99), 12)
        self.assertEqual(quantile([0, 10, 20, 30], .5), 15)
        self.assertAlmostEqual(quantile([0, 10, 20, 30], .95), 28.5)
        self.assertAlmostEqual(quantile([0, 10, 20, 30], .99), 29.7)

    def fixture(self):
        s=Session.__new__(Session)
        s.node=dict(A="A-a", B="B-b", C="C-c")
        s.expected=[s.node[r] for r in "ABC"]
        pid,ack="fixture-packet","fixture-ack"
        def ev(category,packetId,message):
            return dict(category=category,packetId=packetId,message=message,time=100)
        source=dict(packetId=pid, side="source", route=s.expected, hops=2, rttMs=123)
        dest=dict(packetId=pid, side="destination", route=s.expected,hops=2)
        obs={
            "A":dict(snapshot=dict(metrics=[source]),logs=[ev("FORWARD",pid,"sent TEXT → B-b"),ev("RX",ack,"rx ACK route=C-c→B-b→A-a"),ev("DELIVERY",pid,"ACK: TEXT")]),
            "B":dict(snapshot=dict(metrics=[]),logs=[ev("RX",pid,"rx TEXT"),ev("FORWARD",pid,"forwarded TEXT → C-c"),ev("RX",ack,"rx ACK"),ev("FORWARD",ack,"forwarded ACK → A-a")]),
            "C":dict(snapshot=dict(metrics=[dest]),logs=[ev("FORWARD",ack,"sent ACK → B-b")]),
        }
        return s,dict(packetId=pid),obs

    def test_full_ack_evidence_required(self):
        s,p,obs=self.fixture()
        result,proof=s.verify_packet(p,obs)
        self.assertEqual(result["latency_ms"],123)
        self.assertEqual(proof["ack_packet_id"],"fixture-ack")
        obs["B"]["logs"]=[e for e in obs["B"]["logs"] if e["packetId"] != "fixture-ack"]
        with self.assertRaises(SystemFailure):
            s.verify_packet(p,obs)

    def test_direct_path_and_duplicate_delivery_rejected(self):
        s,p,obs=self.fixture()
        obs["A"]["snapshot"]["metrics"][0]["route"]=["A-a","C-c"]
        with self.assertRaises(SystemFailure):
            s.verify_packet(p,obs)
        s,p,obs=self.fixture()
        obs["C"]["snapshot"]["metrics"]*=2
        with self.assertRaises(SystemFailure):
            s.verify_packet(p,obs)


if __name__ == "__main__":
    unittest.main()

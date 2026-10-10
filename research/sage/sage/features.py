"""Features see completed history only; no trace/oracle reference is accepted."""
from collections import defaultdict, deque
import math
import numpy as np

WINDOWS = (30, 120, 600)
FEATURES = ["priority", "log_size", "age", "remaining_lifetime", "remaining_hops", "hops", "replicas",
    "self_queue_bytes", "self_queue_packets", "self_battery", "peer_queue_bytes", "peer_queue_packets", "peer_battery"]
for role in ("self", "peer"):
    for w in WINDOWS:
        FEATURES += [f"{role}_unique_{w}", f"{role}_encounters_{w}"]
    FEATURES += [f"{role}_destination_recency", f"{role}_destination_rate", f"{role}_gateway_observations"]
FEATURES += ["pair_frequency", "pair_mean_duration", "pair_recency", "peer_churn", "peer_gateway_probability"]

class History:
    def __init__(self):
        self.events = defaultdict(deque)
        self.last_time = float("-inf")

    def observe(self, contacts, end, gateways):
        if end < self.last_time:
            raise ValueError("History cannot move backwards")
        for c in contacts:
            if c.end > end:
                raise ValueError("Cannot observe a future contact")
            for a, b in ((c.a, c.b), (c.b, c.a)):
                self.events[a].append((c.end, b, c.end - c.start, gateways(b, c.end)))
        self.last_time = end
        for events in self.events.values():
            while events and events[0][0] < end - 600:
                events.popleft()

    def recent(self, node, now, window=600):
        return [e for e in self.events[node] if now - window <= e[0] <= now]

    def destination_rate(self, node, dest, now):
        return sum(e[1] == dest or (dest == "@gateway" and e[3]) for e in self.recent(node, now)) / 600

    def vector(self, copy, current, peer, now, queues, battery):
        p = copy.packet
        v = [int(p.priority), math.log1p(p.size_bytes), (now-p.creation_time)/600,
             max(0, p.expiry_time-now)/600, p.ttl-copy.hop_count, copy.hop_count, copy.replication_count]
        for n in (current, peer):
            q = queues[n]
            v += [sum(c.packet.size_bytes for c in q.values())/65536, len(q)/100, battery[n]]
        for n in (current, peer):
            events = self.recent(n, now)
            for w in WINDOWS:
                ev = [e for e in events if e[0] >= now-w]
                v += [len({e[1] for e in ev})/100, len(ev)/100]
            dest = [e for e in events if e[1] == p.destination or (p.destination == "@gateway" and e[3])]
            v += [(now-max(e[0] for e in dest))/600 if dest else 1.0, len(dest)/600,
                sum(e[3] for e in events)/100]
        pair = [e for e in self.recent(current, now) if e[1] == peer]
        events = self.recent(peer, now)
        recent = {e[1] for e in events if e[0] >= now-120}
        earlier = {e[1] for e in events if e[0] < now-120}
        v += [len(pair)/600, np.mean([e[2] for e in pair])/20 if pair else 0,
            (now-pair[-1][0])/600 if pair else 1.0,
            len(recent ^ earlier)/max(1, len(recent | earlier)),
            sum(e[3] for e in events)/max(1, len(events))]
        assert len(v) == len(FEATURES)
        return np.asarray(v, dtype=np.float32)

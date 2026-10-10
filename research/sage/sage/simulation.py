"""CPU, synchronous contact-bin replay with bounded packet buffers."""
from __future__ import annotations
from collections import defaultdict
from dataclasses import dataclass, replace
import hashlib
import itertools
import numpy as np
from .features import History, FEATURES
from .types import Contact, Packet, Copy, Priority

@dataclass
class Scenario:
    packets: int = 120
    lifetime: float = 1800
    ttl: int = 8
    queue_bytes: int = 131072
    bytes_per_second: int = 2048
    loss: float = 0.05
    gateway_count: int = 2
    gateway_uptime: float = 0.9
    gateway_period: int = 600
    gateway_fraction: float = 0.15
    gateway_disappear: float = 1e12
    adoption: float = 1.0
    media_fraction: float = 0.2
    critical_fraction: float = 0.1
    critical_burst: int = 0
    partition_start: float = 1e12
    partition_end: float = 1e12
    contact_keep: float = 1.0
    node_uptime: float = 1.0
    battery_cost_per_kb: float = 0.00001
    label_limit: int = 0

def random_value(seed, *parts):
    # Counter-based draws couple exogenous conditions across policies despite
    # different transmission counts/order. Never use Python's salted hash().
    key = "|".join(map(str, (seed, *parts))).encode()
    return int.from_bytes(hashlib.blake2b(key, digest_size=8).digest(), "big") / 2**64

class Replay:
    def __init__(self, frame, scenario: Scenario, seed: int):
        self.config, self.seed = scenario, seed
        nodes = sorted(set(frame.node_a) | set(frame.node_b))
        active = [n for n in nodes if random_value(seed, "adoption", n) < scenario.adoption]
        if len(active) < 2:
            raise ValueError("Need at least two participating nodes")
        self.nodes = active
        self.gateway_nodes = set(sorted(active, key=lambda n: random_value(seed, "gateway", n))[:scenario.gateway_count])
        self.contacts = []
        for row in frame.itertuples():
            a, b, t = row.node_a, row.node_b, row.timestamp_start
            if a not in active or b not in active:
                continue
            if scenario.partition_start <= t < scenario.partition_end and (
                random_value(seed, "partition", a) < .5) != (random_value(seed, "partition", b) < .5):
                continue
            if random_value(seed, "contact", t, a, b) >= scenario.contact_keep:
                continue
            if any(random_value(seed, "uptime", n, int(t//600)) >= scenario.node_uptime for n in (a, b)):
                continue
            self.contacts.append(Contact(t, row.timestamp_end, a, b))
        self.contacts.sort(key=lambda c: (c.start, c.a, c.b))
        self.end = float(frame.timestamp_end.max())
        if scenario.lifetime >= self.end:
            raise ValueError("Trace must outlast packet lifetime")
        self.packets = self.traffic()

    def gateway(self, node, t):
        s = self.config
        return node in self.gateway_nodes and t < s.gateway_disappear and random_value(
            self.seed, "gateway_up", node, int(t//s.gateway_period)) < s.gateway_uptime

    def traffic(self):
        s, rng = self.config, np.random.default_rng(self.seed)
        packets = []
        for i in range(s.packets+s.critical_burst):
            source, dest = rng.choice(self.nodes, size=2, replace=False)
            created = float(rng.uniform(0, self.end-s.lifetime-1))
            r = rng.random()
            priority = Priority.CRITICAL if r < s.critical_fraction else (
                Priority.MEDIA if r < s.critical_fraction+s.media_fraction else
                Priority.CONTROL if r < s.critical_fraction+s.media_fraction+.1 else Priority.TEXT)
            if i >= s.packets:
                priority, created = Priority.CRITICAL, (self.end-s.lifetime)/2
            if rng.random() < s.gateway_fraction and self.gateway_nodes:
                dest = "@gateway"
            sizes = {Priority.CRITICAL: 256, Priority.CONTROL: 128, Priority.TEXT: 512, Priority.MEDIA: 16384}
            size = int(sizes[priority]*rng.choice([.5, 1, 2]))
            packets.append(Packet(str(i), str(source), str(dest), created, created+s.lifetime,
                s.ttl, size, priority))
        return sorted(packets, key=lambda p: (p.creation_time, p.packet_id))

    def run(self, policy, oracle=None):
        s = self.config
        queues = {n: {} for n in self.nodes}
        seen = {n: set() for n in self.nodes}
        battery = {n: 1.0 for n in self.nodes}
        history, labels = History(), []
        label_candidates = 0
        delivered, transfers, arrivals = {}, [], 0
        drops = critical_overflow = total_bytes = duplicate_bytes = failed = 0
        successful_sends = defaultdict(int)

        def admit(node, copy):
            nonlocal drops, critical_overflow
            p, q = copy.packet, queues[node]
            if p.packet_id in seen[node]:
                return False
            used = sum(c.packet.size_bytes for c in q.values())
            if used+p.size_bytes > s.queue_bytes:
                if p.priority != Priority.CRITICAL:
                    drops += 1
                    return False
                victims = sorted([c for c in q.values() if c.packet.priority != Priority.CRITICAL],
                    key=lambda c: (-int(c.packet.priority), c.packet.creation_time, c.packet.packet_id))
                for victim in victims:
                    del q[victim.packet.packet_id]
                    used -= victim.packet.size_bytes
                    drops += 1
                    if used+p.size_bytes <= s.queue_bytes:
                        break
                if used+p.size_bytes > s.queue_bytes:
                    # Deterministic bounded emergency overflow, never an ML drop.
                    critical_overflow += 1
                    return False
            q[p.packet_id] = copy
            seen[node].add(p.packet_id)
            return True

        for start, group in itertools.groupby(self.contacts, key=lambda c: c.start):
            contacts = list(group)
            end = contacts[0].end
            if any(c.end != end for c in contacts) or end-start != 20:
                raise ValueError("Replay v0 requires aligned 20-second bins")
            for q in queues.values():
                for pid in list(q):
                    if q[pid].packet.expiry_time <= start:
                        del q[pid]
            while arrivals < len(self.packets) and self.packets[arrivals].creation_time <= start:
                p = self.packets[arrivals]
                if p.expiry_time > start:
                    admit(p.source, Copy(p))
                arrivals += 1
            # Local gateway delivery, without a radio hop.
            for n, q in queues.items():
                if self.gateway(n, start):
                    for pid, cp in q.items():
                        if cp.packet.destination == "@gateway":
                            delivered.setdefault(pid, (start, cp.hop_count))
            opportunities = []
            budgets = {}
            for c in contacts:
                key = (c.a, c.b)
                budgets[key] = int((c.end-c.start)*s.bytes_per_second)
                for current, peer in ((c.a, c.b), (c.b, c.a)):
                    for pid, cp in list(queues[current].items()):
                        p = cp.packet
                        if pid in seen[peer] or cp.hop_count >= p.ttl or end >= p.expiry_time:
                            continue
                        if p.size_bytes > budgets[key]:
                            continue
                        direct = peer == p.destination or (p.destination == "@gateway" and self.gateway(peer, start))
                        x = history.vector(cp, current, peer, start, queues, battery)
                        decision = policy.decide(cp, current, peer, start, history, x, direct)
                        if oracle is not None and p.priority != Priority.CRITICAL and s.label_limit:
                            label_candidates += 1
                            row = dict(zip(FEATURES, x.tolist()),
                                _oracle_args=(p, current, peer, end, cp.hop_count),
                                group=f"{self.seed}:{pid}:{current}:{start}", packet_id=pid,
                                timestamp=start, peer=peer)
                            if len(labels) < s.label_limit:
                                labels.append(row)
                            else:
                                index = int(random_value(self.seed, "reservoir", label_candidates)*label_candidates)
                                if index < s.label_limit:
                                    labels[index] = row
                        if decision.action != "HOLD":
                            opportunities.append((int(p.priority), -decision.score, p.creation_time,
                                pid, current, peer, key, cp, direct))
            staged, reserved = [], set()
            # CRITICAL first across ALL contacts, then per-policy utility and FIFO.
            for _, _, _, pid, current, peer, key, cp, direct in sorted(opportunities, key=lambda o: o[:6]):
                p = cp.packet
                if (peer, pid) in reserved or p.size_bytes > budgets[key]:
                    continue
                if p.priority != Priority.CRITICAL and not direct:
                    if cp.replication_count >= policy.replica_limit:
                        continue
                    if policy.name == "spray" and cp.tokens <= 1:
                        continue
                budgets[key] -= p.size_bytes
                total_bytes += p.size_bytes
                if pid in delivered:
                    duplicate_bytes += p.size_bytes
                battery[current] = max(0.0, battery[current]-p.size_bytes/1024*s.battery_cost_per_kb)
                transfers.append((start, pid, current, peer, int(p.priority)))
                if random_value(self.seed, "loss", start, pid, current, peer) < s.loss:
                    failed += 1
                    continue
                successful_sends[pid] += 1
                cp.replication_count += 1
                tokens = max(1, cp.tokens//2) if policy.name == "spray" else 4
                if policy.name == "spray" and not direct and p.priority != Priority.CRITICAL:
                    cp.tokens -= tokens
                staged.append((peer, Copy(p, cp.hop_count+1, 0, tokens)))
                reserved.add((peer, pid))
            # Committing only now prevents same-slot multi-hop delivery/leakage.
            for peer, cp in staged:
                p = cp.packet
                if peer == p.destination or (p.destination == "@gateway" and self.gateway(peer, end)):
                    delivered.setdefault(p.packet_id, (end, cp.hop_count))
                admit(peer, cp)
            history.observe(contacts, end, self.gateway)
            policy.observe(contacts, end)
        pmap = {p.packet_id: p for p in self.packets}
        critical = [p for p in self.packets if p.priority == Priority.CRITICAL]
        latency = [t-pmap[pid].creation_time for pid, (t, _) in delivered.items()]
        crit_latency = [t-pmap[pid].creation_time for pid, (t, _) in delivered.items() if pmap[pid].priority == Priority.CRITICAL]
        gateway_packets = [p for p in self.packets if p.destination == "@gateway"]
        def pct(values, q):
            return float(np.percentile(values, q)) if values else None
        metrics = {"policy": policy.name, "seed": self.seed, "generated": len(self.packets),
            "delivery_ratio": len(delivered)/len(self.packets),
            "critical_delivery_ratio": len(crit_latency)/len(critical) if critical else None,
            "p50_latency": pct(latency, 50), "p95_latency": pct(latency, 95),
            "critical_p50_latency": pct(crit_latency, 50), "critical_p95_latency": pct(crit_latency, 95),
            "duplicate_bytes": duplicate_bytes, "total_transmitted_bytes": total_bytes,
            "transmissions_per_delivered": len(transfers)/len(delivered) if delivered else None,
            "mean_hops": float(np.mean([h for _, h in delivered.values()])) if delivered else None,
            "expired_packets": sum(p.packet_id not in delivered and p.expiry_time <= self.end for p in self.packets),
            "gateway_success": sum(p.packet_id in delivered for p in gateway_packets)/len(gateway_packets) if gateway_packets else None,
            "queue_drops": drops, "critical_overflow": critical_overflow, "failed_transmissions": failed,
            "battery_cost": sum(1-b for b in battery.values()), "fallbacks": policy.fallbacks,
            "active_nodes": len(self.nodes), "contact_bins": len(self.contacts)}
        for row in labels:
            row["target"] = oracle.gain(*row.pop("_oracle_args"))
        return metrics, labels, transfers

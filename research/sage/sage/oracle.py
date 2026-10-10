"""OFFLINE label oracle. Never imported by inference features or policy."""
from bisect import bisect_left
from collections import defaultdict
from heapq import heappop, heappush
from math import inf
from .types import Contact, Packet

class TemporalOracle:
    """Earliest interval-end delivery, finite hop budget, unlimited competing capacity.

    Transfers use a whole recorded bin and arrive at its end. No same-bin chains.
    Gateway availability is an explicit offline function. Oracle is optimistic
    about contention/loss, but enforces packet size vs full contact capacity.
    """
    def __init__(self, contacts: list[Contact], bytes_per_second=4096, gateways=None):
        self.edges = defaultdict(list)
        for c in sorted(contacts, key=lambda c: (c.start, c.end, c.a, c.b)):
            self.edges[c.a].append((c.start, c.end, c.b))
            self.edges[c.b].append((c.start, c.end, c.a))
        self.starts = {n: [c[0] for c in edges] for n, edges in self.edges.items()}
        self.rate = bytes_per_second
        self.gateways = gateways or (lambda node, t: False)
        self.service_times = sorted({c.start for c in contacts})

    def earliest(self, source, destination, now, expiry, hops, size=1):
        if now >= expiry:
            return inf
        queue, best = [(now, 0, source)], {(source, 0): now}
        delivery = inf
        while queue:
            t, used, node = heappop(queue)
            if t >= delivery:
                break
            if t != best.get((node, used)):
                continue
            if node == destination or (destination == "@gateway" and self.gateways(node, t)):
                return t
            if destination == "@gateway":
                # A carrier that is itself a temporarily offline gateway can
                # retain the packet until service returns, without another hop.
                for service in self.service_times[bisect_left(self.service_times, t):]:
                    if service >= min(expiry, delivery):
                        break
                    if self.gateways(node, service):
                        delivery = service
                        break
            if used >= hops:
                continue
            for start, end, peer in self.edges[node][bisect_left(self.starts.get(node, []), t):]:
                if start >= expiry:
                    break
                if end >= expiry or size > (end - start) * self.rate:
                    continue
                if end < best.get((peer, used + 1), inf):
                    best[peer, used + 1] = end
                    heappush(queue, (end, used + 1, peer))
        return delivery

    def gain(self, packet: Packet, current, peer, end, used_hops):
        remaining = packet.ttl - used_hops
        ta = self.earliest(current, packet.destination, end, packet.expiry_time, remaining, packet.size_bytes)
        tb = self.earliest(peer, packet.destination, end, packet.expiry_time, remaining - 1, packet.size_bytes)
        def utility(arrival):
            return 0.0 if arrival == inf else max(0.0, (packet.expiry_time - arrival) /
                (packet.expiry_time - packet.creation_time))
        return utility(tb) - utility(ta)

"""SAGE v1 replay simulator: real contact bins + synthetic MeshMind traffic.

Differences from the v0 pilot (all apply identically to every policy):
* delivery ACKs gossip on every contact and purge delivered copies (MeshMind
  Router ACK/SUMMARY semantics);
* each phone has ONE radio byte budget per bin shared by all its simultaneous
  links (send and receive), so dense crowds create real airtime contention;
* byte-capped buffers evict ordinary packets (never CRITICAL) instead of
  silently refusing them;
* traffic endpoints are drawn from nodes PRESENT in the recent past, with a
  configurable share of "social" messages to people the sender met before
  and a share addressed to any Internet gateway;
* exogenous randomness (contacts kept, churn, losses, gateway uptime) is a
  pure function of (seed, ids), so all policies see the same world.
"""
from __future__ import annotations
from dataclasses import dataclass, asdict
import math
import numpy as np
from .oracle import ContactIndex, sweep, utility
from .state import NodeState, packet_view

CRITICAL, CONTROL, TEXT, MEDIA = 0, 1, 2, 3
SIZES = {CRITICAL: 256, CONTROL: 128, TEXT: 512, MEDIA: 32768}
HOLD, REPLICATE, FORWARD = 0, 1, 2


@dataclass
class Scenario:
    name: str = "base"
    rate_per_node_hour: float = 1.0
    lifetime: float = 7200.0
    ttl: int = 8
    queue_bytes: int = 262144
    node_bps: float = 2048.0          # per phone, shared by all links in a bin (send + receive)
    contact_seconds: float = 60.0     # usable radio time per bin (<= bin length)
    loss: float = 0.05
    gateway_share: float = 0.03       # fraction of participating phones with Internet uplink
    gateway_uptime: float = 0.9
    gateway_period: float = 600.0
    gateway_disappear_frac: float = 2.0   # fraction of the episode after which gateways vanish
    traffic_gateway: float = 0.3      # share of ordinary packets addressed to "@gateway"
    social: float = 0.6               # share of node-addressed packets sent to a past contact
    adoption: float = 1.0
    media_fraction: float = 0.2
    critical_fraction: float = 0.05
    control_fraction: float = 0.1
    critical_burst: int = 0
    partition: tuple = ()             # (start_frac, end_frac) of the episode
    contact_keep: float = 1.0
    node_uptime: float = 1.0
    battery_cost_per_kb: float = 1e-5
    max_packets: int = 1500
    ack_gossip: bool = True

    def to_dict(self):
        return asdict(self)


def mix(*parts):
    """Vectorized splitmix64-style hash of integer arrays -> uniform floats in [0,1)."""
    x = np.uint64(0x9E3779B97F4A7C15)
    with np.errstate(over="ignore"):
        for p in parts:
            p = np.asarray(p).astype(np.int64).astype(np.uint64)
            x = (x ^ p) * np.uint64(0xBF58476D1CE4E5B9)
            x ^= x >> np.uint64(31)
            x = x * np.uint64(0x94D049BB133111EB)
            x ^= x >> np.uint64(29)
    return (x >> np.uint64(11)).astype(np.float64) / float(1 << 53)


class Copy:
    __slots__ = ("i", "hops", "replicas", "tokens", "value")

    def __init__(self, i, hops=0, replicas=0, tokens=8, value=math.nan):
        self.i, self.hops, self.replicas, self.tokens, self.value = i, hops, replicas, tokens, value


class World:
    """Everything exogenous for one (episode, scenario, seed): filtered contacts, gateways, traffic."""

    def __init__(self, episode, scenario: Scenario, seed: int):
        self.ep, self.sc, self.seed = episode, scenario, seed
        n = len(episode.nodes)
        self.n = n
        sid = seed * 1000003 + episode.index
        self.sid = sid
        active_all = np.unique(np.concatenate([episode.a, episode.b]))
        adopt = mix(sid, 1, active_all) < scenario.adoption
        self.participant = np.zeros(n, bool)
        self.participant[active_all[adopt]] = True
        s, a, b = episode.start, episode.a, episode.b
        keep = self.participant[a] & self.participant[b]
        keep &= mix(sid, 2, s, a, b) < scenario.contact_keep
        if scenario.node_uptime < 1:
            slot = (s // 600).astype(np.int64)
            keep &= (mix(sid, 3, a, slot) < scenario.node_uptime) & (mix(sid, 3, b, slot) < scenario.node_uptime)
        t0, t1 = episode.t0, episode.t1
        self.t0, self.t1 = t0, t1
        if scenario.partition:
            lo, hi = t0 + scenario.partition[0] * (t1 - t0), t0 + scenario.partition[1] * (t1 - t0)
            side = mix(sid, 4, np.arange(n)) < 0.5
            keep &= ~((s >= lo) & (s < hi) & (side[a] != side[b]))
        self.start, self.a, self.b = s[keep], a[keep].astype(np.int64), b[keep].astype(np.int64)
        self.bin = float(episode.bin_seconds)
        self.index = ContactIndex(self.start, self.a, self.b, self.bin, n)
        # Gateways: deterministic subset of participants.
        parts = np.flatnonzero(self.participant)
        k = max(1, int(round(scenario.gateway_share * len(parts))))
        order = np.argsort(mix(sid, 5, parts))
        self.gateway_nodes = parts[order[:k]]
        self.is_gateway = np.zeros(n, bool)
        self.is_gateway[self.gateway_nodes] = True
        self.disappear = t0 + scenario.gateway_disappear_frac * (t1 - t0)
        self._gw_cache = {}
        self.packets = self._traffic()

    def gateway_up(self, t):
        period = int(t // self.sc.gateway_period)
        key = (period, t >= self.disappear)
        up = self._gw_cache.get(key)
        if up is None:
            up = np.zeros(self.n, bool)
            if t < self.disappear:
                g = self.gateway_nodes
                up[g] = mix(self.sid, 6, g, period) < self.sc.gateway_uptime
            self._gw_cache[key] = up
        return up

    def _traffic(self):
        sc, ep = self.sc, self.ep
        rng = np.random.default_rng(self.sid)
        lo, hi = self.t0 + 600.0, self.t1 - sc.lifetime
        if hi <= lo:
            raise ValueError(f"{ep.key}: episode shorter than packet lifetime")
        part = self.participant
        expected = sc.rate_per_node_hour * part.sum() * (hi - lo) / 3600
        count = int(min(sc.max_packets, rng.poisson(expected)))
        self.packets_capped = bool(expected > sc.max_packets)
        # Past contact lists per node (warm-up + episode) for presence and social choice.
        ws = np.concatenate([ep.warm_start, self.start])
        wa = np.concatenate([ep.warm_a, self.a]).astype(np.int64)
        wb = np.concatenate([ep.warm_b, self.b]).astype(np.int64)
        okw = part[wa] & part[wb]
        ws, wa, wb = ws[okw], wa[okw], wb[okw]
        u = np.concatenate([wa, wb])
        v = np.concatenate([wb, wa])
        t = np.concatenate([ws, ws])
        order = np.lexsort((t, u))
        u, v, t = u[order], v[order], t[order]
        bounds = np.searchsorted(u, np.arange(self.n + 1))
        times = rng.uniform(lo, hi, count)
        times.sort()
        burst_t = (lo + hi) / 2
        rows = []
        for k in range(count + sc.critical_burst):
            if k < count:
                tc = float(times[k])
                r = rng.random()
                prio = (CRITICAL if r < sc.critical_fraction else
                        MEDIA if r < sc.critical_fraction + sc.media_fraction else
                        CONTROL if r < sc.critical_fraction + sc.media_fraction + sc.control_fraction else TEXT)
            else:
                tc, prio = burst_t, CRITICAL
            # Present = had a contact in the previous 30 minutes (past only).
            i0, i1 = np.searchsorted(self.start, [tc - 1800, tc])
            present = np.unique(np.concatenate([self.a[i0:i1], self.b[i0:i1]]))
            if len(present) < 2:
                continue
            src = int(rng.choice(present))
            gw_share = 0.5 if prio == CRITICAL else sc.traffic_gateway
            if rng.random() < gw_share:
                dst = -1
            else:
                lo_i, hi_i = bounds[src], bounds[src + 1]
                past = v[lo_i:hi_i][t[lo_i:hi_i] < tc]
                if len(past) and rng.random() < sc.social:
                    dst = int(past[rng.integers(len(past))])     # frequency-weighted past contact
                else:
                    others = present[present != src]
                    dst = int(rng.choice(others))
            size = int(SIZES[prio] * rng.choice([0.5, 1.0, 2.0])) if prio == MEDIA else SIZES[prio]
            rows.append((tc, src, dst, prio, size))
        rows.sort(key=lambda r: r[0])
        arr = np.array(rows, dtype=float).reshape(-1, 5)
        self.p_created = arr[:, 0]
        self.p_expiry = arr[:, 0] + sc.lifetime
        self.p_src = arr[:, 1].astype(np.int64)
        self.p_dst = arr[:, 2].astype(np.int64)
        self.p_prio = arr[:, 3].astype(np.int64)
        self.p_size = arr[:, 4].astype(np.int64)
        return len(rows)

    def dest_col(self, i):
        d = self.p_dst[i]
        return np.where(d < 0, self.n, d)

    def oracle_ceiling(self, sample=None):
        """Per-packet deliverability with perfect future knowledge and unlimited resources.

        With ``sample``, only a fixed seeded subset is evaluated (others are NaN)."""
        hit = np.full(self.packets, np.nan)
        todo = range(self.packets)
        if sample is not None and sample < self.packets:
            todo = np.sort(np.random.default_rng(self.sid + 17).choice(self.packets, sample, replace=False))
        for i in todo:
            created = float(self.p_created[i])
            snap = sweep(self.index, int(self.p_dst[i]), float(self.p_expiry[i]), self.sc.ttl, [created],
                self.gateway_up)
            ea = snap[created][self.p_src[i], self.sc.ttl]
            hit[i] = np.isfinite(ea) and ea < self.p_expiry[i]
        return hit


@dataclass
class Candidates:
    i: np.ndarray
    a: np.ndarray
    b: np.ndarray
    prio: np.ndarray
    size: np.ndarray
    direct: np.ndarray
    hops_left: np.ndarray
    replicas: np.ndarray
    tokens: np.ndarray
    copies: list
    t: float
    e: float


class Context:
    """What a policy may read at decision time: past history + current local queue state."""

    def __init__(self, sim):
        self.sim = sim

    def features(self, C: Candidates):
        sim, w = self.sim, self.sim.w
        t = C.t
        dcol = w.dest_col(C.i)
        remaining = w.p_expiry[C.i] - t
        lifetime = w.sc.lifetime
        P = packet_view(w.p_prio[C.i], w.p_dst[C.i] < 0, w.p_size[C.i], t - w.p_created[C.i], remaining,
            lifetime, C.replicas, w.bin)
        qf = sim.qbytes / w.sc.queue_bytes
        qc = np.array([len(q) for q in sim.queues], float)
        S = sim.state.node_view(C.a, dcol, t, C.hops_left, remaining, qf[C.a], qc[C.a], sim.battery[C.a],
            w.is_gateway[C.a])
        Q = sim.state.node_view(C.b, dcol, t, C.hops_left - 1, remaining, qf[C.b], qc[C.b], sim.battery[C.b],
            w.is_gateway[C.b])
        R = sim.state.pair_view(C.a, C.b, t)
        return np.concatenate([P, S, Q, R], axis=1)


class Simulator:
    def __init__(self, world: World, warm_state: NodeState | None = None):
        self.w = world
        self.warm_state = warm_state

    def run(self, policy, log_rate: float = 0.0, log_cap: int = 0, log_seed: int = 0):
        w, sc = self.w, self.w.sc
        n, P = w.n, w.packets
        self.queues = [dict() for _ in range(n)]
        self.qbytes = np.zeros(n, np.int64)
        self.battery = np.ones(n)
        seen = [0] * n
        acks = [0] * n
        qmask = [0] * n
        needs_state = policy.uses_state or log_rate > 0
        self.state = (self.warm_state.copy() if self.warm_state is not None else NodeState(n, w.bin)) if needs_state else None
        ctx = Context(self)
        policy.reset(w)
        delivered_t = np.full(P, np.nan)
        delivered_h = np.zeros(P, np.int64)
        stats = dict(total_bytes=0, duplicate_bytes=0, transmissions=0, failed=0, queue_drops=0,
            critical_overflow=0, evictions_by_value=0, model_rows=0)
        logs = []
        logged = 0
        log_rng = np.random.default_rng(log_seed)
        next_packet = 0
        budget_bin = sc.node_bps * min(w.bin, sc.contact_seconds)
        prio = w.p_prio
        size = w.p_size
        expiry = w.p_expiry
        dst = w.p_dst
        last_cleanup = -np.inf

        def remove(node, i):
            cp = self.queues[node].pop(i, None)
            if cp is not None:
                self.qbytes[node] -= size[i]
                qmask[node] &= ~(1 << i)
            return cp

        def cleanup(node, now):
            q = self.queues[node]
            dead = [i for i in q if expiry[i] <= now or (acks[node] >> i) & 1]
            for i in dead:
                remove(node, i)

        def admit(node, cp, now):
            i = cp.i
            if (seen[node] >> i) & 1:
                return False
            need = self.qbytes[node] + size[i] - sc.queue_bytes
            if need > 0:
                cleanup(node, now)
                need = self.qbytes[node] + size[i] - sc.queue_bytes
            if need > 0:
                victims = [c for c in self.queues[node].values()
                    if prio[c.i] != CRITICAL and (prio[i] == CRITICAL or prio[c.i] >= prio[i])]
                victims = policy.eviction_order(victims, w)
                freed, chosen = 0, []
                for c in victims:
                    if freed >= need:
                        break
                    chosen.append(c)
                    freed += size[c.i]
                if freed < need:
                    if prio[i] == CRITICAL:
                        stats["critical_overflow"] += 1   # deterministic; never an ML decision
                    else:
                        stats["queue_drops"] += 1
                    return False
                for c in chosen:
                    remove(node, c.i)
                    stats["queue_drops"] += 1
            self.queues[node][i] = cp
            self.qbytes[node] += size[i]
            qmask[node] |= 1 << i
            seen[node] |= 1 << i
            return True

        def deliver(i, node, when, hops):
            if np.isnan(delivered_t[i]):
                delivered_t[i] = when
                delivered_h[i] = hops
            acks[node] |= 1 << i

        idx = w.index
        for k in range(len(idx.bins)):
            s = float(idx.bins[k])
            e = s + w.bin
            ca = idx.a[idx.offsets[k]:idx.offsets[k + 1]]
            cb = idx.b[idx.offsets[k]:idx.offsets[k + 1]]
            if s - last_cleanup >= 600:
                for node in range(n):
                    if self.queues[node]:
                        cleanup(node, s)
                last_cleanup = s
            while next_packet < P and w.p_created[next_packet] <= s:
                i = next_packet
                if expiry[i] > s:
                    admit(int(w.p_src[i]), Copy(i, 0, 0, policy.initial_tokens), s)
                next_packet += 1
            up_s = w.gateway_up(s)
            for g in w.gateway_nodes:
                if up_s[g] and self.queues[g]:
                    for i in [i for i in self.queues[g] if dst[i] < 0 and expiry[i] > s]:
                        deliver(i, g, s, self.queues[g][i].hops)
                        remove(g, i)
            if sc.ack_gossip:
                for x, y in zip(ca.tolist(), cb.tolist()):
                    if acks[x] != acks[y]:
                        merged = acks[x] | acks[y]
                        for z in (x, y):
                            if acks[z] != merged:
                                acks[z] = merged
                                if qmask[z] & merged:
                                    cleanup(z, s)
            up_e = w.gateway_up(e)
            ci, cA, cB, cdir = [], [], [], []
            for x, y in zip(ca.tolist(), cb.tolist()):
                for frm, to in ((x, y), (y, x)):
                    m = qmask[frm] & ~seen[to]
                    while m:
                        low = m & -m
                        i = low.bit_length() - 1
                        m ^= low
                        if expiry[i] <= e:
                            continue
                        cp = self.queues[frm][i]
                        if cp.hops >= sc.ttl:
                            continue
                        ci.append(i)
                        cA.append(frm)
                        cB.append(to)
                        cdir.append(dst[i] == to or (dst[i] < 0 and up_e[to]))
            if ci:
                ci = np.array(ci, np.int64)
                cA = np.array(cA, np.int64)
                cB = np.array(cB, np.int64)
                copies = [self.queues[f][i] for f, i in zip(cA.tolist(), ci.tolist())]
                C = Candidates(ci, cA, cB, prio[ci], size[ci], np.array(cdir, bool),
                    sc.ttl - np.array([c.hops for c in copies]), np.array([c.replicas for c in copies]),
                    np.array([c.tokens for c in copies]), copies, s, e)
                action, score = policy.decide(ctx, C)
                # Safety gate AFTER the policy as well: CRITICAL is always replicated; direct always delivered.
                crit = C.prio == CRITICAL
                action = np.where(crit, REPLICATE, action)
                action = np.where(C.direct & ~crit, FORWARD, action)
                if log_rate > 0:
                    # Bernoulli thinning, then an unbiased reservoir over the whole episode.
                    pick = (~crit) & (~C.direct) & (log_rng.random(len(ci)) < log_rate)
                    sel = []
                    for j in np.flatnonzero(pick).tolist():
                        logged += 1
                        if len(logs) + len(sel) < log_cap:
                            sel.append((j, None))
                        else:
                            slot = int(log_rng.integers(logged))
                            if slot < log_cap:
                                sel.append((j, slot))
                    if sel:
                        js = np.array([j for j, _ in sel])
                        sub = Candidates(ci[js], cA[js], cB[js], C.prio[js], C.size[js], C.direct[js],
                            C.hops_left[js], C.replicas[js], C.tokens[js], [copies[j] for j in js], s, e)
                        X = ctx.features(sub)
                        for (j, slot), row in zip(sel, X):
                            item = (int(ci[j]), int(cA[j]), int(cB[j]), s, e, int(C.hops_left[j]), row)
                            if slot is None:
                                logs.append(item)
                            else:
                                logs[slot] = item
                send = np.flatnonzero(action != HOLD)
                if len(send):
                    # CRITICAL first (deterministic), then direct deliveries, then policy score, then FIFO.
                    keys = np.lexsort((w.p_created[ci[send]], -score[send], ~C.direct[send], ~crit[send]))
                    send = send[keys]
                    budget = {}
                    staged = []
                    reserved = set()
                    for j in send.tolist():
                        i, frm, to = int(ci[j]), int(cA[j]), int(cB[j])
                        if (to, i) in reserved or i not in self.queues[frm]:
                            continue
                        sz = int(size[i])
                        bf = budget.get(frm, budget_bin)
                        bt = budget.get(to, budget_bin)
                        if sz > bf or sz > bt:
                            continue
                        budget[frm], budget[to] = bf - sz, bt - sz
                        stats["total_bytes"] += sz
                        stats["transmissions"] += 1
                        if not np.isnan(delivered_t[i]):
                            stats["duplicate_bytes"] += sz
                        self.battery[frm] = max(0.0, self.battery[frm] - sz / 1024 * sc.battery_cost_per_kb)
                        if mix(w.sid, 7, i, frm, to, int(s)) < sc.loss:
                            stats["failed"] += 1
                            continue
                        cp = copies[j]
                        reserved.add((to, i))
                        act = int(action[j])
                        if C.direct[j] and not crit[j]:
                            staged.append((to, i, cp.hops + 1, 1, True))
                            continue
                        if act == FORWARD and not crit[j]:
                            staged.append((to, i, cp.hops + 1, cp.tokens, False))
                            remove(frm, i)
                            continue
                        give = cp.tokens if crit[j] else policy.split_tokens(cp)
                        cp.replicas += 1
                        staged.append((to, i, cp.hops + 1, give, False))
                    for to, i, hops, tokens, direct in staged:
                        is_dest = dst[i] == to or (dst[i] < 0 and up_e[to])
                        if is_dest:
                            deliver(i, to, e, hops)
                            seen[to] |= 1 << i
                        else:
                            admit(to, Copy(i, hops, 0, tokens), e)
            if self.state is not None:
                self.state.observe_bin(s, ca, cb, up_e)
            policy.observe(self, s, ca, cb)
        stats["model_rows"] = policy.model_rows
        return self._metrics(policy, delivered_t, delivered_h, stats), logs

    def _metrics(self, policy, delivered_t, delivered_h, stats):
        w = self.w
        ok = ~np.isnan(delivered_t)
        crit = w.p_prio == CRITICAL
        normal = ~crit
        lat = delivered_t - w.p_created
        gw = w.p_dst < 0

        def pct(mask, q):
            vals = lat[mask & ok]
            return float(np.percentile(vals, q)) if len(vals) else None

        def ratio(mask):
            return float(ok[mask].mean()) if mask.any() else None

        delivered = int(ok.sum())
        return dict(policy=policy.name, episode=w.ep.key, trace=w.ep.trace, family=w.ep.family,
            scenario=w.sc.name, seed=w.seed, packets=w.packets, packets_capped=w.packets_capped,
            participants=int(w.participant.sum()), contact_bins=int(len(w.start)),
            delivery_ratio=ratio(np.ones_like(ok)), normal_delivery_ratio=ratio(normal),
            critical_delivery_ratio=ratio(crit), gateway_delivery_ratio=ratio(gw & normal),
            p50_latency=pct(normal, 50), p95_latency=pct(normal, 95),
            critical_p50_latency=pct(crit, 50), critical_p95_latency=pct(crit, 95),
            mean_hops=float(delivered_h[ok].mean()) if delivered else None,
            transmissions_per_delivered=stats["transmissions"] / delivered if delivered else None,
            expired=int((~ok).sum()), battery_cost=float((1 - self.battery).sum()), **stats)


def label_logs(world: World, logs):
    """Attach OFFLINE oracle targets to logged decisions (one reverse sweep per packet)."""
    if not logs:
        return None
    by_packet = {}
    for row_id, (i, a, b, s, e, h, _) in enumerate(logs):
        by_packet.setdefault(i, []).append(row_id)
    n = len(logs)
    ea_a = np.full(n, np.inf)
    ea_b = np.full(n, np.inf)
    for i, rows in by_packet.items():
        dest = int(world.p_dst[i])
        snaps = sweep(world.index, dest, float(world.p_expiry[i]), world.sc.ttl,
            [logs[r][4] for r in rows], world.gateway_up)
        for r in rows:
            _, a, b, s, e, h, _ = logs[r]
            arr = snaps[float(e)]
            ea_a[r] = arr[a, h]
            ea_b[r] = arr[b, h - 1]
    i = np.array([l[0] for l in logs])
    exp = world.p_expiry[i]
    life = world.sc.lifetime
    ua = utility(ea_a, exp, life)
    ub = utility(ea_b, exp, life)
    ub_or = utility(np.minimum(ea_a, ea_b), exp, life)
    X = np.stack([l[6] for l in logs])
    meta = dict(packet=i, holder=np.array([l[1] for l in logs]), peer=np.array([l[2] for l in logs]),
        t=np.array([l[3] for l in logs]), u_self=ua, u_peer=ub, reach_self=(ua > 0).astype(np.float32),
        reach_peer=(ub > 0).astype(np.float32), gain_replicate=ub_or - ua, gain_forward=ub - ua)
    return X, meta

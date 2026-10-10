"""Routing policies for the v1 simulator.

Every policy only sees ``Context`` (past contact history and local queue
state). CRITICAL packets are forced to REPLICATE and scheduled first by the
simulator AFTER ``decide`` returns, and the learned policy additionally never
computes features or scores for them.
"""
from __future__ import annotations
import logging
import math
import numpy as np
from .sim import HOLD, REPLICATE, FORWARD, CRITICAL


class Policy:
    name = "policy"
    uses_state = False
    initial_tokens = 8

    def __init__(self):
        self.model_rows = 0

    def reset(self, world):
        self.model_rows = 0

    def decide(self, ctx, C):
        raise NotImplementedError

    def eviction_order(self, victims, world):
        # Default drop policy: lowest-priority class first (MEDIA), then oldest.
        return sorted(victims, key=lambda c: (-world.p_prio[c.i], world.p_created[c.i]))

    def split_tokens(self, cp):
        return cp.tokens

    def observe(self, sim, s, ca, cb):
        pass


class Direct(Policy):
    name = "direct"

    def decide(self, ctx, C):
        return np.full(len(C.i), HOLD), np.zeros(len(C.i))


class Epidemic(Policy):
    """MeshMind-style epidemic store-and-forward (hop TTL, ACK purge, FIFO within priority)."""
    name = "epidemic"

    def decide(self, ctx, C):
        return np.full(len(C.i), REPLICATE), -C.prio.astype(float)


class SprayWait(Policy):
    """Binary Spray-and-Wait (Spyropoulos et al. 2005) with L copies."""

    def __init__(self, copies=8):
        super().__init__()
        self.initial_tokens = copies
        self.name = f"spray_wait_L{copies}"

    def decide(self, ctx, C):
        return np.where(C.tokens > 1, REPLICATE, HOLD), -C.prio.astype(float)

    def split_tokens(self, cp):
        give = cp.tokens // 2
        cp.tokens -= give
        return give


class SprayFocus(SprayWait):
    """Spray-and-Focus (Spyropoulos et al. 2007): binary spray, then single-copy handoff
    to a peer whose last-encounter timer for the destination is fresher by > margin."""
    uses_state = True

    def __init__(self, copies=8, margin=60.0):
        super().__init__(copies)
        self.margin = margin
        self.name = f"spray_focus_L{copies}"

    def decide(self, ctx, C):
        st, w = ctx.sim.state, ctx.sim.w
        d = w.dest_col(C.i)
        since_self = C.t - st.last_met[C.a, d]
        since_peer = C.t - st.last_met[C.b, d]
        better = since_peer + self.margin < since_self
        action = np.where(C.tokens > 1, REPLICATE, np.where(better, FORWARD, HOLD))
        return action, -C.prio.astype(float) + 1e-6 * np.nan_to_num(-since_peer, neginf=-1e12)


class Prophet(Policy):
    """PRoPHET delivery predictabilities (Lindgren et al.; P_init .75, beta .25, gamma .98)
    with the GRTRMax forwarding strategy: copy when P(peer,d) > P(self,d), highest P(peer,d) first.
    Queue drops use the default class/FIFO order (not the RFC's full queue-policy menu)."""
    uses_state = True

    def __init__(self, speed=0):
        super().__init__()
        self.speed = speed
        self.name = f"prophet_{'fast' if speed == 0 else 'slow'}"

    def decide(self, ctx, C):
        p = ctx.sim.state.prophet[self.speed]
        d = ctx.sim.w.dest_col(C.i)
        ps, pp = p[C.a, d], p[C.b, d]
        return np.where(pp > ps, REPLICATE, HOLD), pp - C.prio


class Heuristic(Policy):
    """The v0 MeshMind hand-written heuristic: recent destination contact rate + degree."""
    name = "meshmind_heuristic"
    uses_state = True

    def decide(self, ctx, C):
        st, w = ctx.sim.state, ctx.sim.w
        d = w.dest_col(C.i)
        rate = st.pair_rate[0]
        deg_a = ((C.t - st.last_met[C.a, :st.n]) <= 600).sum(1)
        deg_b = ((C.t - st.last_met[C.b, :st.n]) <= 600).sum(1)
        score = rate[C.b, d] - rate[C.a, d] + 0.001 * (deg_b - deg_a)
        return np.where(score > 0, REPLICATE, HOLD), score


class ValuePolicy(Policy):
    """Shared SAGE skeleton: copy when estimated replication gain > threshold; schedule by gain
    (optionally per byte); evict lowest estimated value. Subclasses supply the estimator."""
    uses_state = True

    def __init__(self, threshold=0.0, per_byte=False, max_replicas=1_000_000):
        super().__init__()
        self.threshold, self.per_byte, self.max_replicas = threshold, per_byte, max_replicas

    def estimate(self, ctx, C):
        """Return (value_self, gain) arrays for ordinary, non-direct candidates."""
        raise NotImplementedError

    def decide(self, ctx, C):
        n = len(C.i)
        action = np.full(n, HOLD)
        score = np.zeros(n)
        ordinary = (C.prio != CRITICAL) & ~C.direct      # CRITICAL never reaches the estimator
        idx = np.flatnonzero(ordinary)
        if len(idx):
            sub = _subset(C, idx)
            value, gain = self.estimate(ctx, sub)
            for c, v in zip(sub.copies, value.tolist()):
                c.value = v
            ok = (gain > self.threshold) & (sub.replicas < self.max_replicas)
            action[idx] = np.where(ok, REPLICATE, HOLD)
            score[idx] = gain / (sub.size / 512.0) if self.per_byte else gain
        return action, score

    def eviction_order(self, victims, world):
        return sorted(victims, key=lambda c: (0.5 if math.isnan(c.value) else c.value, world.p_created[c.i]))


class ProphetValue(ValuePolicy):
    """Same mechanics as SAGE, hand-crafted value: PRoPHET predictability. Isolates learning."""

    def __init__(self, speed=1, **kw):
        super().__init__(**kw)
        self.speed = speed
        self.name = f"prophet_value_{'fast' if speed == 0 else 'slow'}"

    def estimate(self, ctx, C):
        p = ctx.sim.state.prophet[self.speed]
        d = ctx.sim.w.dest_col(C.i)
        ps, pp = p[C.a, d], p[C.b, d]
        return ps, pp - ps


class Sage(ValuePolicy):
    """Learned relay value. ``predictor(X) -> dict(u_self, gain_replicate, ...)``; any failure
    falls back to the deterministic MeshMind heuristic for that batch."""

    def __init__(self, predictor, name="sage", head="gain_replicate", **kw):
        super().__init__(**kw)
        self.predictor, self.name, self.head = predictor, name, head
        self.fallback = Heuristic()
        self.fallbacks = 0

    def estimate(self, ctx, C):
        try:
            if self.predictor is None:
                raise ValueError("model unavailable")
            X = ctx.features(C)
            out = self.predictor(X)
            self.model_rows += len(X)
            value = np.asarray(out["u_self"], float)
            gain = (np.asarray(out["u_peer"], float) - value if self.head == "value_difference"
                else np.asarray(out[self.head], float))
            if not (np.isfinite(value).all() and np.isfinite(gain).all()):
                raise ValueError("nonfinite SAGE output")
            return value, gain
        except Exception:
            self.fallbacks += 1
            if self.fallbacks == 1:
                logging.warning("SAGE unavailable; deterministic heuristic fallback", exc_info=True)
            action, score = self.fallback.decide(ctx, C)
            return np.full(len(C.i), 0.5), np.where(action == REPLICATE, np.abs(score) + 1e-9, -1.0)


def _subset(C, idx):
    from .sim import Candidates
    return Candidates(C.i[idx], C.a[idx], C.b[idx], C.prio[idx], C.size[idx], C.direct[idx], C.hops_left[idx],
        C.replicas[idx], C.tokens[idx], [C.copies[j] for j in idx.tolist()], C.t, C.e)

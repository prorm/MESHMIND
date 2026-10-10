from collections import defaultdict
import logging
import math
from .types import Priority, SageDecisionOutput

BASELINES = ("direct", "epidemic", "spray", "prophet_like", "heuristic")

class Policy:
    def __init__(self, name, predictor=None, threshold=0.02, replica_limit=4):
        self.name, self.predictor = name, predictor
        self.threshold, self.replica_limit = threshold, replica_limit
        self.p = defaultdict(dict)
        self.last = 0.0
        self.fallbacks = 0

    def observe(self, contacts, now):
        decay = 0.98 ** ((now-self.last)/60)
        for values in self.p.values():
            for d in values:
                values[d] *= decay
        old = {n: dict(values) for n, values in self.p.items()}
        for c in contacts:
            for a, b in ((c.a, c.b), (c.b, c.a)):
                self.p[a][b] = self.p[a].get(b, 0) + (1-self.p[a].get(b, 0))*0.75
                for d, prob in old.get(b, {}).items():
                    if d != a:
                        prev = self.p[a].get(d, 0)
                        self.p[a][d] = max(prev, prev+(1-prev)*self.p[a][b]*prob*0.25)
        self.last = now

    def decide(self, copy, current, peer, now, history, features, direct=False):
        # This gate precedes ALL model access, replica thresholds, and fallback.
        if copy.packet.priority == Priority.CRITICAL:
            return SageDecisionOutput("REPLICATE", 1.0, "deterministic CRITICAL bypass")
        if direct:
            return SageDecisionOutput("FORWARD", 1.0, "direct destination")
        if self.name == "direct":
            return SageDecisionOutput("HOLD", 0, "direct delivery only")
        if copy.replication_count >= self.replica_limit:
            return SageDecisionOutput("HOLD", 0, "per-copy replication bound")
        dest = copy.packet.destination
        heuristic = (history.destination_rate(peer, dest, now)-history.destination_rate(current, dest, now)
            + 0.001*(len({e[1] for e in history.recent(peer, now)})
                     - len({e[1] for e in history.recent(current, now)})))
        if self.name == "epidemic":
            score = 1.0
        elif self.name == "spray":
            score = float(copy.tokens > 1)
        elif self.name == "prophet_like":
            score = self.p[peer].get(dest, 0)-self.p[current].get(dest, 0)
        elif self.name == "heuristic":
            score = heuristic
        else:
            try:
                if self.predictor is None:
                    raise ValueError("Model unavailable")
                score = float(self.predictor(features.reshape(1, -1))[0])
                if not math.isfinite(score):
                    raise ValueError("Nonfinite relay score")
                return SageDecisionOutput("REPLICATE" if score > self.threshold else "HOLD", score, "SAGE relay value")
            except Exception:
                self.fallbacks += 1
                if self.fallbacks == 1:
                    logging.warning("SAGE prediction unavailable; using deterministic heuristic", exc_info=True)
                score = heuristic
        return SageDecisionOutput("REPLICATE" if score > 0 else "HOLD", score, self.name)

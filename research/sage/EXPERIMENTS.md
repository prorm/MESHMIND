# Experiment protocol — SAGE v1

Reproduce everything from `research/sage`:

```powershell
.\.venv\Scripts\python.exe -m pytest -q                                   # 110 tests
.\.venv\Scripts\python.exe -m sage.v1.experiment --stage all --workers 6  # ~3.5 h on a Ryzen 5 5600H + RTX 3050
.\.venv\Scripts\python.exe -m sage.v1.headroom --episodes 6 --workers 3   # optional diagnostic, slow
```

Stages (`--stage episodes,labels,train,tune,test,report`) can be rerun individually. The
config is `configs/v1.json`; outputs go to `outputs/v1/` (the v0 pilot is preserved in
`outputs/v0_pilot/`). Labels are cached per job in `data/processed/v1/labels/`; delete
them to regenerate.

| Stage | What it does | Wall time here |
|---|---|---|
| episodes | Parse 11 traces once, write 145 compact `.npz` episodes + `episodes.csv` | ~30 s |
| labels | 480 replay jobs (train ×4, val ×2, test ×1 per episode) with logging + oracle labels | 17 min, 6 workers |
| train | SageNet (2 sizes) on CUDA + HistGradientBoosting; offline accuracy; ONNX export | ~5 min |
| tune | 30 policy configurations × 17 validation episodes × 4 scenarios | 57 min, 7 workers |
| test | 12 policies × 22 test episodes × 22 scenario-seeds, plus sampled ceilings | 2 h 3 min, 7 workers |
| report | Tables, paired cluster bootstrap, failure correlations, plots | ~1 min |

## 1. Data and episodes

Eleven public traces (see DATASETS.md). Each trace is cut into sessions at gaps over 3 h,
capped at 24 h. Episodes with fewer than 30 active nodes or under 4 h are dropped. Earlier
sessions of the **same population** (up to 7 days) are replayed into the history state
first, as warm-up only. Infectious days are independent populations: no warm-up.

## 2. Splits (no encounter-level shuffling)

| Split | Episodes | Rule |
|---|---|---|
| Train (106) | school 2013, high school 2011/2012, primary school, workplace 2013/2015, hospital, Copenhagen days 0–17, Infectious days 0–44 | Labels and model fitting |
| Validation (17) | Hypertext (conference), Copenhagen days 18–21, Infectious days 45–54 | Checkpoint selection, every policy's hyper-parameters |
| Test (22) | **SFHH** (unseen conference), Copenhagen days 22–27 (future weeks), Infectious days 55–68 (future days, new visitors) | Reported once |

SFHH is a domain held out from training: no conference trace is in train. Copenhagen and
Infectious test days are temporally later and, for Infectious, different people.

## 3. Replay simulator (`sage/v1/sim.py`)

These rules apply identically to every policy:

- **Bins:** 20 s contact bins (SocioPatterns) or 5-min scans (Copenhagen; at most 60 s of
  usable airtime per bin).
- **Radio:** one radio budget per phone per bin, shared by all its links, sending and
  receiving (`node_bps`, default 4 KiB/s).
- **Buffers:** byte-capped (default 256 KiB).
- **Losses:** 5% transmission loss.
- **Hop limit:** TTL 8.
- **ACK gossip:** delivery ACKs spread on every contact and purge delivered copies,
  matching MeshMind ACK/SUMMARY.
- **Gateways:** 3% of participants have Internet. Each is up 90% of the time in 10-min
  periods; local delivery happens at bin start.
- **Traffic:** the sender is a node present in the last 30 min. Destination: 30% go to
  "@gateway"; otherwise 60% go to a past contact (frequency-weighted) and the rest to
  another present node.
- **Packet mix:**

  | Class | Share | Size |
  |---|---:|---|
  | CRITICAL | 5% | 256 B |
  | CONTROL | 10% | 128 B |
  | MEDIA | 20% | 16–64 KB |
  | TEXT | rest | 512 B |

- **Load and lifetime:** 1 packet per participant per hour (capped at 1,500 per run),
  2 h lifetime.
- **Scheduling order:** CRITICAL first across the whole bin, then direct deliveries, then
  each policy's score, then FIFO.
- **Eviction:** never removes CRITICAL for ordinary traffic.
- **Shared world:** exogenous draws (adoption, kept contacts, churn, partitions, losses,
  gateway uptime) are hash functions of (seed, ids), so all policies see the same world.

## 4. Oracle labels (`sage/v1/oracle.py`)

For a packet, one **reverse sweep** over the contact bins from expiry backwards yields,
for every node and every remaining-hop budget, the earliest feasible delivery time when
holding the packet from time T. The rules:

- no same-bin chains;
- arrival must come before expiry;
- a live gateway delivers locally at bin start, or on arrival.

It is tested against the v0 Dijkstra oracle for node destinations, and against
exhaustive flooding for both node and gateway destinations (80 random graphs).

For a logged decision (holder A, peer B, bin [s, e)), with u = the remaining-lifetime
fraction on delivery:

- `u_self` = u(EA_A); `u_peer` = u(EA_B, one fewer hop);
- `gain_replicate` = u(min(EA_A, EA_B)) − u(EA_A);
- `gain_forward` = u(EA_B) − u(EA_A).

Labels ignore losses, contention and buffers, so they are optimistic.

## 5. Features (`sage/v1/state.py`) — 59 inputs, past only

| Block | Count | Contents |
|---|---:|---|
| Packet | 10 | class, gateway destination, size, age, remaining lifetime, replicas, scan interval |
| Self node view | 22 | as listed below, for the holder |
| Peer node view | 22 | the same 22, for the candidate peer |
| Pair | 5 | contact count, 1 h / 24 h rates, recency, 1 h neighbour-set Jaccard |

Each node view contains:

- **Activity:** encounter rates at 10 min / 1 h / 6 h / 24 h half-lives; unique
  neighbours over 10 min / 1 h / 24 h; time since last contact.
- **Local state:** queue fill and length, battery, gateway flag, hops left.
- **Destination-specific:** recency, never-met flag, count, 1 h and 24 h rates, PRoPHET
  predictability at two aging speeds, hour-of-day routine over the packet's lifetime,
  expected meetings before expiry.

Leakage-safety rules:

- `NodeState.observe_bin` accepts only completed bins in time order.
- The current bin is observed **after** decisions.
- A test rewrites a trace's future and asserts every earlier logged feature is
  bit-identical.
- Peer views assume the peer shares its own summaries in the handshake, as PRoPHET
  exchanges predictability vectors.

## 6. Label generation

Each training run draws a random scenario from `label_scenario_space` (load, lifetime,
buffer, bandwidth, gateway share and uptime, social share, adoption, MEDIA share, contact
thinning, churn, loss) and a behaviour policy (epidemic ×2, Spray-and-Focus L=16,
PRoPHET-slow). Candidates are Bernoulli-thinned (25%), then reservoir-sampled up to 4,000
per run. CRITICAL and direct deliveries are never logged.

## 7. Models (`sage/v1/model.py`, `learn.py`)

**SageNet.** A shared encoder E(packet ⊕ node view): LayerNorm → 64 → GELU → 48 → GELU.
Heads:

- value head on each embedding: utility (sigmoid) and reach logit;
- gain head on [h_self, h_peer, h_self ⊙ h_peer, pair]: 64 → GELU → (replicate gain
  (sigmoid), forward gain (tanh)).

Normalisation is fitted on train and embedded in the graph.

**Training.** Loss: MSE on utilities, 0.25 × BCE on reach, 4 × positive-weighted MSE on
replicate gain, MSE on forward gain. AdamW with one-cycle LR; family-balanced sampling;
40 epochs; checkpoint chosen by validation loss. Two sizes (15k, 55k parameters); the
smaller won.

**Tree.** HistGradientBoosting heads for replicate gain and self utility, with early
stopping.

## 8. Policies (`sage/v1/policies.py`)

| Policy | Rule |
|---|---|
| direct | Deliver only on meeting the destination |
| epidemic | Replicate every eligible copy; class priority, then FIFO |
| spray_wait (L) | Binary Spray-and-Wait (Spyropoulos 2005) |
| spray_focus (L) | Binary spray, then single-copy handoff to a peer whose last-encounter timer for the destination is fresher by over 60 s (Spyropoulos 2007) |
| prophet | PRoPHET predictabilities (P_init 0.75, β 0.25, γ 0.98 per time unit; unit tuned 60 s or 600 s), GRTRMax forwarding; default class/FIFO drops. Not the full RFC 6693 queue-policy menu. |
| meshmind_heuristic | The v0 hand-written rule: destination rate difference plus degree |
| prophet_value | The **same mechanics as SAGE** (threshold on gain, gain-ordered scheduling, value-ordered eviction) with PRoPHET as the value. Isolates learning from mechanics. |
| sage* | SageNet gain head; threshold and per-byte ordering tuned on validation; evicts lowest predicted value; falls back to the heuristic if the model fails |

## 9. Evaluation scenarios

All scenarios start from base: 1 packet per participant per hour, 2 h lifetime, 256 KiB
buffers, 4 KiB/s per phone.

| Group | Scenarios |
|---|---|
| Load | low_load 0.5, heavy_load 4 |
| Bursts and buffers | media_burst (60% MEDIA + 30 SOS burst), queue_saturation (64 KiB buffers, 3 packets per person per hour) |
| Population | low_adoption 50%, sparse_contacts (keep 40%), high_churn (50% uptime per 10 min) |
| Gateways | unstable_gateways (30% uptime, 2-min periods), gateway_disappearance (at 50%), more_gateways 10% |
| Topology | partition_reconnect (25–60% of the episode) |
| Lifetime | short 30 min, long 4 h |
| Traffic | uniform_traffic (no social destinations), gateway_heavy (70% to gateways) |
| Protocol | no_ack_gossip |

Seeds: 31 and 32 for base, low_load, heavy_load, queue_saturation and media_burst; 31 for
the rest. The perfect-knowledge ceiling is estimated per run on a seeded 600-packet sample.

## 10. Statistics

- **Paired comparisons:** per (episode, scenario, seed).
- **95% confidence intervals:** bootstrap over **episodes**, 4,000 resamples.
- **Win/loss rates:** fraction of paired runs.
- **Failure analysis:** Spearman correlation of the per-run delivery difference with
  episode and scenario descriptors.
- **Simulation result:** no significance claim beyond these intervals, and no claim of
  phone or field performance.

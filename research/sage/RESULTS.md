# SAGE v1 — measured results

All numbers below are measured on **held-out test episodes** that were never used for
training, labelling-model selection or threshold tuning:

- SFHH conference (2 days);
- the last 6 days of the Copenhagen smartphone-Bluetooth study;
- the last 14 days of the Infectious exhibition, with different visitors each day.

That is 22 episodes × (5 core scenarios × 2 seeds + 12 stress scenarios × 1 seed) =
**484 paired runs per policy**. Confidence intervals are 95% cluster bootstraps that
resample **episodes**, so all runs of an episode stay together. Simulation on real
human-contact traces; **not** a phone measurement.

## Did SAGE-Lite beat the strongest non-learning baseline?

**Yes — narrowly, and consistently.** The delivery-maximising SAGE policy delivered
**40.81%** of ordinary packets vs **40.27%** for bounded epidemic flooding, the strongest
classic baseline:

- **+0.54 points**, 95% CI [+0.44, +0.65];
- SAGE won 84% of paired runs and lost 5%;
- it used the same airtime (99.9% of epidemic's bytes);
- it was ahead in **all 17** stress scenarios and in all three held-out traces.

Against the strongest baseline overall, PRoPHET-value (our own baseline: identical SAGE
mechanics, hand-crafted PRoPHET value), the gain is **+0.29 points** [+0.20, +0.38]. That
gap is what learning itself adds over the best hand-written value function.

The gain is real but small. The more practically useful result is the efficiency
operating point:

**`sage_eco` matched epidemic delivery with 28% less airtime.** It delivered 40.16% vs
40.27%: −0.11 points, CI [−0.43, +0.20], statistically indistinguishable. In exchange:

- **28% fewer radio bytes** (130 vs 182 MB per run);
- **28% fewer transmissions per delivered packet** (46.3 vs 64.6);
- **28% lower** synthetic battery cost;
- **12% fewer** post-delivery duplicate bytes;
- **191 s lower** p95 delivered latency [−233, −151].

The v2 pitch target was "−25% duplicate bytes at equal delivery". On **total airtime**
that target is met; on **duplicate bytes** it is **not** met (−12%).

At low airtime budgets SAGE **ties** Spray-and-Focus rather than beating it:

| Matched pair | SAGE delivery | Baseline delivery | Difference |
|---|---:|---:|---|
| `sage_lean` vs Spray-and-Focus (L=32) | 38.60% | 38.61% | −0.01 [−0.60, +0.54] |
| `sage_frugal` vs Spray-and-Focus (L=8) | 35.99% | 35.67% | +0.31 [−0.80, +1.33] |

`sage_lean` used 90 vs 94 MB per run; `sage_frugal` used 57 vs 54 MB.

**SOS safety held in every run.** CRITICAL delivery ratio and CRITICAL p50/p95 latency
were **identical** between every SAGE variant and epidemic in all 484 paired runs
(difference exactly 0). The model never scores CRITICAL packets.

## Main held-out table

| Policy | Delivery | % of ceiling | CRITICAL delivery | p95 latency (s) | MB / run | Bytes vs epidemic | Duplicate MB |
|---|---:|---:|---:|---:|---:|---:|---:|
| **sage** (learned scheduling + eviction, replicate broadly) | **40.81%** | 94.7% | 43.42% | 4,436 | 181.6 | 1.00 | 39.1 |
| prophet_value (same mechanics, PRoPHET value) | 40.52% | 94.0% | 43.42% | 4,463 | 181.5 | 1.00 | 35.1 |
| epidemic (bounded, ACK gossip) | 40.27% | 93.4% | 43.42% | 4,476 | 181.9 | 1.00 | 33.2 |
| **sage_eco** (learned copy threshold) | 40.16% | 93.2% | 43.42% | 4,285 | 130.3 | **0.72** | 29.3 |
| spray_focus (L=32, tuned) | 38.61% | 89.6% | 43.42% | 4,237 | 94.0 | 0.52 | 11.1 |
| sage_lean | 38.60% | 89.6% | 43.42% | 4,101 | 89.8 | 0.49 | 21.8 |
| spray_wait (L=32, tuned) | 37.07% | 86.0% | 43.42% | 4,102 | 82.7 | 0.45 | 7.9 |
| sage_frugal | 35.99% | 83.5% | 43.42% | 3,858 | 56.7 | 0.31 | 14.9 |
| spray_focus (L=8) | 35.67% | 82.8% | 43.42% | 4,016 | 54.3 | 0.30 | 4.2 |
| meshmind_heuristic (v0 hand-written) | 34.29% | 79.6% | 43.41% | 4,214 | 61.0 | 0.34 | 7.2 |
| prophet (GRTRMax, tuned aging) | 33.62% | 78.0% | 43.42% | 3,963 | 55.8 | 0.31 | 7.3 |
| direct delivery | 23.50% | 54.5% | 43.42% | 3,143 | 3.0 | 0.02 | 0.2 |

The "ceiling" is the fraction of ordinary packets that a router with perfect knowledge of
the future and unlimited buffers and airtime could deliver: 43.1% on average, estimated
from a seeded sample of 600 packets per run. CRITICAL delivery is the same for all policies by
construction: SOS is flooded first and is never evicted for ordinary traffic. Its ceiling
is 44.5%. Latency counts delivered packets only, so read it together with delivery.

Per-trace delivery (SAGE minus epidemic):

| Trace | sage | sage_eco |
|---|---|---|
| Copenhagen | +0.21 | −1.06, at 61% of epidemic's bytes |
| Infectious | +0.66 | +0.42, at 90% |
| SFHH | +0.70 | −0.98, at 81% |

`sage_eco`'s "equal delivery" is an average: it trades about one point on Copenhagen and
SFHH for its airtime saving.

## Why SAGE works: offline accuracy (held-out test, 65,208 labelled decisions)

The question each method answers: will copying this packet to this neighbour raise
delivery utility by more than 1% of the packet's lifetime, according to the future-aware
oracle? (4.5% of opportunities are "useful".)

| Method | ROC AUC | Avg. precision | Precision in top 10% |
|---|---:|---:|---:|
| **SAGE-Net, gain head (learned, 15,124 params)** | **0.807** | **0.181** | **17.4%** |
| SAGE-Tree (gradient boosting, learned) | 0.814 | 0.190 | 17.9% |
| SAGE-Net, value difference only | 0.585 | 0.122 | 14.3% |
| peer degree, last hour | 0.580 | 0.063 | 8.1% |
| destination contact rate (MeshMind v0 heuristic) | 0.559 | 0.102 | 12.4% |
| encounter timer (Spray-and-Focus utility) | 0.551 | 0.089 | 10.2% |
| PRoPHET predictability, slow aging | 0.547 | 0.106 | 11.5% |
| daily routine (hour-of-day history) | 0.543 | 0.085 | 10.5% |
| PRoPHET predictability, fast aging | 0.528 | 0.115 | 13.3% |

The same pattern holds in every held-out domain:

| Domain | SAGE-Net AUC | PRoPHET (slow) AUC |
|---|---:|---:|
| Smartphone Bluetooth (Copenhagen) | 0.820 | 0.524 |
| Exhibition (Infectious) | 0.822 | 0.563 |
| Conference (SFHH) | 0.732 | 0.554 |

When SAGE-Net copies only its top 10% of opportunities, it captures 43% (Bluetooth),
41% (exhibition) and 28% (conference) of all useful copies. For "will this phone deliver
at all?", SAGE-Net reaches AUC 0.839 vs 0.673 for PRoPHET.

The gain head, which sees both phones and their relationship, is what matters. Comparing
two independently predicted node values scores only 0.585. Redundancy between companions
who move together is the signal that node-centric rules miss.

**Calibration caveat:** the gain head ranks well but is poorly calibrated. Its test MSE,
0.0087, is slightly worse than predicting zero (0.0082), because training up-weights rare
useful copies. Policies therefore use validation-tuned thresholds on its *ranking*, never
its raw value. The tree has better MSE (0.0075) but was 10–15× slower per run in the
Python harness, so it was not carried into the routing test. Its validation routing was
39.3% vs 39.5% for SAGE-Net.

## Why the routing gain is smaller than the accuracy gain

- **Most of the time resources are not binding.** With delivery ACK gossip, epidemic is
  already at 93.4% of the perfect-knowledge ceiling. A better *selection* of copies can
  only recover part of the remaining 6.6%.
- **SAGE wins more where there is more headroom.** Per-run Spearman correlations of
  (SAGE − strongest baseline):
  - +0.29 with the ceiling;
  - +0.27 with epidemic's gap to the ceiling;
  - −0.25 with contact density per participant;
  - −0.12 with packet count.

  Dense, saturated settings leave little room. Sparser and more uniform traffic leave
  more: `uniform_traffic` and `media_burst` gave the largest gains vs epidemic, +0.98 and
  +1.62 points.
- **The oracle label is optimistic.** It ignores losses, contention and buffer competition,
  so "this copy is redundant" is sometimes wrong in the real replay. Copies the oracle
  calls useless still buy robustness against 5% loss and queue drops. That is why the
  replicate-broadly variant (`sage`) delivers best and the selective variants trade
  delivery for airtime.
- **Duplicate bytes went up for `sage`** (+18% vs epidemic). Learned per-byte scheduling
  sends many small copies early, some after the destination already has the packet,
  before ACK gossip catches up. `sage_eco` removes this (−12%). Spray-family baselines
  remain much lower on duplicates (4–11 MB) at their lower delivery.

## Load and other sweeps (read with care)

Packet generation is capped at 1,500 per run for compute. The cap binds on SFHH and
Copenhagen in **all** load scenarios, so the "low/base/heavy load" curve is only meaningful on
Infectious. There, delivery and bytes per policy are:

| Load (Infectious) | sage | sage_eco | epidemic | spray_focus | prophet |
|---|---:|---:|---:|---:|---:|
| low (0.5 / person / h) | 47.4% · 53 MB | 47.2% · 46 MB | 46.8% · 52 MB | 45.3% · 28 MB | 38.8% |
| base (1 / person / h) | 45.7% · 71 MB | 45.4% · 63 MB | 45.0% · 70 MB | 43.8% · 43 MB | 37.5% |
| heavy (4, effectively capped at ~1,500) | 45.7% · 80 MB | 45.6% · 73 MB | 45.1% · 81 MB | 44.0% · 55 MB | 38.0% |

The plots in `outputs/v1/plots/` show load, gateway count, adoption, MEDIA share,
duplicate bytes, per-trace bars, a delivery-vs-bytes Pareto chart, and SAGE minus
epidemic / PRoPHET-value by scenario. Scenario plots compare paired scenarios that differ
in one parameter; do not read them as continuous response curves.

## Validation-only selection

Every tunable policy chose its hyper-parameters on validation episodes only: Hypertext,
4 Copenhagen days and 10 Infectious days, 4 scenarios, 1 seed. Choices:

- Spray-and-Wait and Spray-and-Focus: L=32 (from 4/8/16/32);
- PRoPHET: fast aging (from fast/slow);
- PRoPHET-value: replicate broadly;
- SAGE: replicate broadly with per-byte scheduling;
- `sage_eco`: threshold 0.005 per-byte, the fewest bytes within 1 point of the best
  validation delivery.

`sage_lean` (0.02 per-byte) and `sage_frugal` (0.05) are extra fixed operating points from
the same validation sweep, added to trace the trade-off curve. They were not chosen on
test. On validation, SAGE led the delivery-vs-bytes frontier at every budget, by roughly
0.5–1.5 points at matched bytes.

## Training and export (RTX 3050 Laptop GPU, 4 GB)

| Item | Value |
|---|---|
| Training data | 877,981 labelled decisions from 106 train episodes (validation 58,253; test 65,208) |
| Label generation | 480 replay jobs, 17 min on 6 CPU workers |
| Behaviour policies | epidemic, Spray-and-Focus, PRoPHET, with domain-randomised scenarios |
| SAGE-Net | 15,124 parameters; 59 inputs; siamese encoder + value head + pair gain head |
| Size sweep | 15k (selected) vs 55k params; validation loss 0.3659 vs 0.3672 |
| Training run | 40 epochs × 600k family-balanced rows, batch 2048; 116 s |
| GPU memory | Peak PyTorch reserved 394 MiB; whole training set resident on GPU |
| Tree | HistGradientBoosting, 2 heads, 22 s on CPU |
| ONNX export | `outputs/v1/checkpoints/sage_net.onnx`, 75,345 bytes, opset 17 |
| Equivalence | PyTorch vs ONNX max abs error 3.0e-7; PyTorch vs NumPy 3.4e-7 |
| Laptop CPU inference | Ryzen 5 5600H, 1 thread, onnxruntime: 0.060 ms p50 / 0.126 ms p95 per row; 0.127 ms per batch of 64. **Not Android latency.** |

## Headroom diagnostic: what would a perfect relay predictor achieve?

`python -m sage.v1.headroom` keeps SAGE's mechanics but replaces the model with a
**cheating oracle** that knows the true future gain of every copy. This is not
deployable; it bounds what better learning could still achieve. Setup: 6 validation
Infectious episodes × {base, heavy_load, queue_saturation}, seed 7, 162 runs
(`outputs/v1/headroom_runs.csv`). The table shows means over the three scenarios,
relative to epidemic:

| Policy | Delivery vs epidemic | Bytes vs epidemic |
|---|---:|---:|
| perfect gain, replicate broadly | +0.40 pts | 111% |
| **SAGE, replicate broadly** | **+0.40 pts** | 107% |
| **perfect gain, copy only if gain > 0** | +0.14 pts | **28%** |
| perfect gain, gain > 0.005 | +0.11 pts | 26% |
| perfect gain, gain > 0.02 | −0.02 pts | 24% |
| SAGE eco (gain > 0.005, per byte) | +0.15 pts | 100% |
| SAGE lean (0.02, per byte) | −0.62 pts | 85% |
| SAGE frugal (0.05) | −3.08 pts | 68% |

What this means:

1. **Delivery headroom is used up.** Even perfect knowledge of which copies matter adds
   only +0.40 points over epidemic in this mode, and SAGE already achieves the same
   +0.40. Better learning cannot buy much more delivery under these conditions.
2. **Efficiency headroom is huge and mostly untapped.** A perfect predictor matches
   epidemic delivery with **72% fewer bytes**. Under queue saturation it even *beats*
   epidemic by +0.6 points, because fewer useless copies free buffer space. On these
   Infectious episodes SAGE eco saved nothing (100% of bytes); on the held-out test set it
   saved 28% overall, mostly on Copenhagen and SFHH (61% and 81% of epidemic's bytes vs
   90% on Infectious).
3. **The gap is ranking quality at the selective end.** An AUC of 0.81 is good, but
   dropping a copy is only safe when the model is *confident* it is redundant. The next
   SAGE version should target this regime with:
   - on-policy (DAgger) relabelling under SAGE's own decisions;
   - loss- and contention-aware labels;
   - calibrated "safe to skip" probabilities, e.g. a classifier for `gain_replicate ≈ 0`
     with conformal thresholds;
   - more redundancy features.

   The prize is up to roughly 3–4× less airtime per delivered message, not more delivery.

Caveat: the oracle's notion of "useful" ignores losses and contention, the same optimism
as the training labels. Real-world savings will be smaller than this bound.

## The v0 pilot, for the record

v0 reported "SAGE 3.29% vs epidemic 5.16%" (`outputs/v0_pilot/RESULTS_v0.md`). v1
measured why: in that setup even a perfect-knowledge router could deliver only 5–12%, and
epidemic was already at that ceiling, so no policy could have won. The causes were:

- 100-ID subsets and 4-hour windows;
- 30-minute lifetimes;
- uniform traffic to absent nodes;
- no ACKs.

v1 also found and documented an edge case in the v0 oracle. It keeps only the earliest
arrival per (node, hops), so a later arrival at a gateway that comes back up is
discarded. The v1 sweep is tested against exhaustive search instead.

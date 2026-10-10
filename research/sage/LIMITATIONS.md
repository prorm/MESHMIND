# Limitations — read before citing any SAGE number

## What the evidence is

- **Simulation on real contact traces, not phones.** The traces are real human movement
  and proximity. All MeshMind traffic, gateways, losses, battery and adoption are
  synthetic. No SAGE result has been measured on an Android device. The model is not
  wired into the app.
- **Proximity is a proxy for radio contact.** SocioPatterns badges see face-to-face
  proximity of about 1–1.5 m, which under-states Bluetooth range. Copenhagen scans show
  phones were in Bluetooth range during a 5-minute window. We assume at most 60 s of usable
  airtime per window, but the real duration is unknown. Nearby Connections discovery,
  connection setup, bearer upgrades and interference are not modelled.
- **Whole-packet transfers per bin.** MeshMind chunks large payloads (16 KB) with
  receiver-confirmed backpressure. The simulator moves whole packets within a bin and has
  no partial transfers.

## Benchmark design choices that shape the numbers

- **Load is capped:** at most 1,500 packets per run. The cap binds on SFHH and Copenhagen
  in every load scenario, so the load sweep is only informative on Infectious.
  "heavy_load" is not a true 4× load on the large traces.
- **Traffic is a model.** 60% of node-addressed packets go to a past contact of the
  sender, 30% to any gateway. With `uniform_traffic` (no social destinations) SAGE's gain
  vs epidemic was larger (+0.98 points), but all absolute numbers depend on this model.
- **The ceiling is sampled:** 600 packets per run. Per-run values have about ±2–3 points
  of sampling error; averages over hundreds of runs are stable.
- **One radio budget per phone per bin.** This is a deliberate simplification of shared
  airtime. Real contention, half-duplex scheduling and Nearby's link limits differ.
- **The CRITICAL path is simplified.** It is flooded first, never evicted for ordinary
  traffic, and capped only by buffer and airtime. Broadcast SOS fan-out, SOS ACKs and
  responder acceptance are not modelled. CRITICAL delivery (43.4%) is bounded by the same
  topology as everything else; its perfect-knowledge ceiling is 44.5%.
- **ACK gossip is instantaneous and free** on every contact. Real SUMMARY exchange costs
  airtime.

## Learning-specific caveats

- **Optimistic labels.** The oracle ignores losses, contention and buffer competition.
  This is the main reason the routing gain (+0.54 points) is far smaller than the ranking
  gain (AUC 0.81 vs 0.55).
- **Poor calibration.** The gain head is trained with positive up-weighting; its MSE is
  slightly worse than predicting zero. Use thresholds tuned on validation, never raw
  values.
- **Behaviour-policy shift.** Labels come from epidemic, Spray-and-Focus and PRoPHET
  replays, not from SAGE's own decisions. On-policy (DAgger-style) relabelling was not
  done.
- **Peer features assume a handshake.** The peer sends its own history summaries,
  including per-destination statistics, the way PRoPHET exchanges predictability vectors.
  The bytes and privacy cost of that exchange are not modelled. Production needs a
  bounded, versioned telemetry exchange.
- **Calendar alignment.** Copenhagen's hour-of-day is `t mod 86400` from a relative
  origin, not local time.
- **Tree not routed on test.** The gradient-boosted tree was slightly better offline but
  far slower in the Python harness, so it was not routed on test. Its validation routing
  was on par.

## Statistical scope

- Test results come from 22 episodes across 3 traces. Confidence intervals resample
  episodes, but three traces is a small number of *venues*: SFHH is one conference
  (2 days).
- Each stress scenario was run with 1–2 seeds per episode. Per-scenario differences are
  descriptive; only the aggregate and the "positive in all 17 scenarios" pattern are
  emphasised.
- Thresholds were tuned on validation scenarios {base, heavy_load, queue_saturation,
  sparse_contacts} with one seed.

## What is not claimed

- **Phone performance:** no Android latency, battery or delivery claim for SAGE. The
  0.06 ms inference figure is a laptop CPU.
- **Scale:** no claim about 5,000–20,000-phone events. The largest test episode has about
  550 participants.
- **Emergency use:** MeshMind is not a certified emergency communication system. SAGE
  never touches CRITICAL packets, but delivery is not guaranteed by anyone.
- **Datasets:** CRAWDAD datasets (Haggle, RollerNet, Nottingham) were not used; their
  access requires licence steps.

## Data licences

Hypertext, Infectious, the high-school and primary-school sets, and the hospital set are
CC BY-NC-SA. Models and derived artefacts trained on them inherit non-commercial terms
unless the training set is restricted to CC0/MIT sources (SFHH, workplace, Copenhagen).
Check before any commercial use. The tree checkpoint is a pickle: load only trusted files.

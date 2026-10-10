# Verification log

## 2026-10-06/07 — SAGE v1

- `python -m pytest -q` passes **110 tests**: 14 v0 + 96 v1.
- **Oracle:**
  - the fast reverse sweep equals the v0 Dijkstra oracle on 40 random temporal graphs
    for node destinations;
  - it equals exhaustive forward flooding for node and gateway destinations (80 graphs);
  - hand-built cases cover temporal relays, hop budgets, expiry, the no-same-bin-chain
    rule, local gateway service and arrival at live gateways.
- **Leakage:** rewriting a trace's future after t = 9,000 s leaves every feature logged
  before t bit-identical. `NodeState` rejects time travel.
- **Safety:**
  - a hostile model that refuses every copy and asserts it never sees CRITICAL gives
    CRITICAL delivery, latency and transmissions identical to epidemic under 100%
    critical traffic;
  - in mixed traffic it scores thousands of ordinary rows and never one CRITICAL;
  - a broken model falls back to the heuristic;
  - eviction never selects CRITICAL for ordinary traffic.
  - In the 484-run held-out test, every SAGE variant's CRITICAL delivery and p50/p95
    latency equal epidemic's exactly.
- **Consistency:**
  - replay is deterministic under a seed;
  - ACK gossip reduces bytes;
  - oracle labels satisfy gain_replicate ≥ 0 and ≥ gain_forward;
  - with zero loss, no policy exceeds the perfect-knowledge ceiling.
- **Model:**
  - the NumPy forward pass equals PyTorch (≤ 1e-5);
  - the siamese value heads swap exactly when self and peer views are swapped;
  - the exported ONNX equals the checkpoint (≤ 1e-4 in the test; 3.0e-7 at export);
  - the ONNX loader returns the heuristic when the file is missing.
- **CUDA:** training ran on the NVIDIA GeForce RTX 3050 Laptop GPU (CUDA 12.8, PyTorch
  2.7.1); peak reserved memory was 394 MiB. Details are in `outputs/v1/training.json`.
- **Data:** 11 public sources were imported with SHA-256 and licence recorded in each
  `data/raw/<name>/source.json`, and per-file import reports list dropped rows. No
  CRAWDAD or other access-controlled data was downloaded.
- **Splits:** no episode appears in more than one split
  (`outputs/v1/episodes.csv`). Thresholds come from `tuning_runs.csv` (validation only);
  the test was run once with the selected policies.
- **Plots** were regenerated from `test_runs.csv` and visually checked for label overlap.
- **Headroom:** the `sage.v1.headroom` diagnostic completed (162 runs on 6 validation
  episodes); results are in RESULTS.md.

Not verified:

- Android integration: not attempted.
- Android latency, battery and phone delivery: not measured.
- **Housekeeping:** a `pytest -q` process from the earlier session (started 21:07,
  ~50 CPU-minutes) was still running during this work. Stopping it was not permitted
  from this session; end it manually if it is still alive.

## 2026-10-06 — v0 pilot (unchanged)

The Android `:core:test --rerun-tasks` ran 25 tests, all passing, and `:app:assembleDebug`
succeeded with cached JDK 17. The v0 research tests passed. Details are preserved in
`outputs/v0_pilot/`. No Android source, build configuration or wire protocol was
modified in either session.

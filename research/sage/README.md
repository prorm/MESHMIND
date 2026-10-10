# SAGE — Semantic-Aware Gateway & Encounter routing (MeshMind research)

This is a contained Python research area inside the MeshMind project. It replays real human-contact
traces and adds synthetic MeshMind traffic. A temporal oracle labels each routing decision
by looking at the future. A small model is trained to make the same decision from the past
only, then compared against classic delay-tolerant routing baselines on held-out events.
The Android `app/` and `core/` sources are unchanged. No repository was initialised and
nothing was pushed.

**Headline (SAGE v1, held-out simulation, 484 paired runs):**

- SAGE delivered **40.81% vs 40.27%** for bounded epidemic flooding: +0.54 points, 95% CI
  [+0.44, +0.65], ahead in all 17 stress scenarios.
- `sage_eco` matched epidemic delivery with **28% less airtime**.
- At spotting which copies matter, SAGE scores AUC **0.81** vs 0.53–0.58 for every
  hand-written rule.
- SOS outcomes were identical to flooding in every run.

Read [RESULTS.md](RESULTS.md) and [LIMITATIONS.md](LIMITATIONS.md) before quoting anything.

## Layout

```
research/sage/
  sage/                 v0 pilot modules (kept unchanged for reproducibility)
  sage/v1/
    traces.py           11 verified dataset adapters, episodes with same-population warm-up
    state.py            leakage-free incremental history + 59 SAGE features
    oracle.py           reverse-sweep temporal oracle (exact, hop-bounded, gateway-aware)
    sim.py              replay simulator: ACK gossip, shared airtime, buffers, gateways, logging
    policies.py         direct, epidemic, Spray-and-Wait/Focus, PRoPHET, heuristic, PRoPHET-value, SAGE
    model.py            SageNet (siamese value + gain heads), NumPy twin, tree wrapper
    learn.py            CUDA training, offline accuracy vs hand-crafted scores, ONNX export
    experiment.py       stages: episodes → labels → train → tune → test → report
    report.py           paired cluster bootstrap, failure correlations, plots
    inference.py        deployment-style ONNX loader with heuristic fallback
    headroom.py         diagnostic: same mechanics with a perfect (future-aware) gain predictor
  configs/v1.json       every experimental choice
  data/raw/<trace>/     downloaded public files + source.json (URL, SHA-256, licence)
  data/processed/v1/    parsed traces, per-episode arrays, labelled decisions (Parquet)
  outputs/v1/           checkpoints, ONNX, metrics CSV/JSON, plots, logs
  outputs/v0_pilot/     the archived v0 pilot outputs and RESULTS
  tests/                v0 + v1 tests (oracle, leakage, safety, determinism, ONNX)
```

## Run (Windows, from `research/sage`)

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m sage.v1.experiment --stage all --workers 6
```

Training **requires CUDA** and fails clearly without it. Simulation, oracle labelling and
the tree run on CPU workers. Each worker keeps one episode resident; peak RAM is a few
hundred MB per worker. The full pipeline takes about 3.5 h on a Ryzen 5 5600H with an
RTX 3050 Laptop GPU (4 GB); see EXPERIMENTS.md for per-stage times. The environment is
Python 3.11 and PyTorch 2.7.1+cu128 (`requirements-lock.txt`); `scripts/setup.ps1`
recreates it.

## What v1 changed and why

1. **The v0 benchmark could not be won.** In the v0 setup a perfect-knowledge router could
   deliver only 5–12%, and epidemic was already there. v1 uses:
   - whole sessions with history warm-up;
   - traffic from people present at the time;
   - 2 h lifetimes;
   - delivery-ACK gossip;
   - shared airtime.

   That yields a 33–69% ceiling where policies differ.
2. **More data.** 11 traces and 145 episodes, including a 69-day science-exhibition crowd
   and 28 days of real smartphone Bluetooth (Copenhagen). That gives 1,001,442 labelled
   decisions, versus 1,244.
3. **A fast exact oracle.** One reverse sweep per packet labels every node at every
   decision time. It is verified against exhaustive search and exposed a v0 oracle edge
   case.
4. **Better features and model.** 59 features with long memory: PRoPHET at two speeds,
   daily routines, pair redundancy. A siamese network whose pair gain head captures
   "these two phones move together, so copying adds nothing".
5. **Fair baselines.** Spray-and-Focus and a PRoPHET-value policy with *identical
   mechanics* were added. Every baseline's hyper-parameters are tuned on validation, the
   same as SAGE's.

## Safety

- CRITICAL/SOS packets are decided before any model call. The simulator re-applies the
  gate after the policy returns.
- CRITICAL is scheduled first and is never evicted for ordinary traffic.
- A hostile test model, which refuses everything and crashes on SOS, leaves SOS outcomes
  identical to flooding.
- A missing or corrupt model falls back to the hand-written heuristic.

See [ANDROID_INTEGRATION_PLAN.md](ANDROID_INTEGRATION_PLAN.md) for how this maps onto the
real router, and the safety gap in its advisor hook that must be closed first.

## Other documents

- [DATASETS.md](DATASETS.md): sources, formats, licences and what was not imported.
- [EXPERIMENTS.md](EXPERIMENTS.md): the full protocol.
- [VERIFICATION.md](VERIFICATION.md): what was checked, and how.
- The v3 pitch deck at `../../docs/pitch/MeshMind_Pitch_v3.pdf` uses these results with
  status labels.

# MeshMind 3-Phone Physical Benchmark

Session: `physical_20261009_03`. Report generated 2026-10-09T20:29:37.050763+05:30.

## Hardware

| Role | User-supplied phone label | Detected model | Android | ADB | Node |
|---|---|---|---|---|---|
| A | OnePlus 12R | CPH2585 | 16 | USB `ac7626fb` | `A-fbd6` |
| C | Samsung Galaxy S24 Ultra | SM-S928B | 16 | wireless `192.168.29.146:37103` | `C-507b` |
| B | Realme 12 Pro (user label; model identifier is authoritative) | RMX5108 | 16 | wireless `192.168.29.70:46817` | `B-e84d` |

## Topology

A ↔ B ↔ C. The topology is software-enforced using the existing Nearby peer filter/topology lock. Disallowed A↔C application links are rejected; this is **not physical RF isolation**.

Preflight passed: `True`. Every delivered measured packet requires matching full-UUID relay receive/send logs, a two-hop destination route, and matching reverse ACK evidence (or Internet response evidence).

## Build / Test Conditions

APK SHA-256: `ac8d8d0020d44b7f0585cdf154fa387cfd91d00cd0e39289c755fc52fd74af05`. Version 0.1.0 / code 1; debug-only shell-permission control receiver added. Production routing, persistence, priority and transport code unchanged.

The harness never changes radios. B and C retain Wi-Fi for wireless ADB; A remains controlled over USB. A began preflight with Wi-Fi enabled and was later observed with airplane mode on and Wi-Fi off. Per-attempt radio observations are captured in full runs. Bluetooth remains available. Outages stop only MeshMind, never the radio. Nearby P2P_CLUSTER chooses the available bearer; results do not establish Bluetooth-only performance. Existing non-disruptive Wi-Fi upgrade policy retained.

Only C has gateway consent. INET_REQUEST follows the remote app path A→B→C. The smoke gateway attempt had validated Internet on A; all five mini gateway attempts reported no validated Internet on A. Full runs record A radio, airplane and validated state separately. Remote gatewaying is proven; do not infer isolation for a sample without its radio evidence. Endpoint is always https://example.com/. Gateway cadence is ≥11 seconds to respect 6 requests/minute.

The harness observes snapshots and logs through ADB and saves complete raw observations. Snapshot polling and durable Room writes are part of the instrumented test conditions. Logcat is continuously captured on all phones for MeshMind plus fatal AndroidRuntime errors; structured app logs accompany each command.

## Method

Stages: preflight → one smoke attempt per scenario → five mini attempts per scenario → inspect → full matrix. Warmups are retained and excluded from full measured summaries. All attempts, including failed infrastructure attempts, remain in JSONL/CSV. No failures are replaced. Success denominators include every full attempt; latency distributions use successful valid attempts only.

Percentiles use Python statistics.quantiles with the inclusive method, equivalent to Hyndman–Fan type 7 linear interpolation at (n−1)p. Standard deviation is population standard deviation. P99 is descriptive and unstable below 100 samples; no statistical significance is claimed. Primary RTT uses source app enqueue and final ACK times on A; Internet RTT uses full-response acceptance on A. Store recovery uses C restore and C delivery, both on C. Relay recovery uses B restore and queued packet forwarding, both on B. Cross-device times are preserved but not presented as precise one-way latencies. Each snapshot records a host/device offset estimate and its round-trip uncertainty.

Store outage buckets (5/10/20/30s) describe the deliberate wait after B retains the packet. Actual absence, including loss detection and queue confirmation, is separately recorded. Discovery latency starts at A's start command and includes subsequent B/C starts, ending when A learns C via B; a subsequent text+ACK validates route usability.

SOS load levels use repeatable 128KiB/512KiB/1MiB LOW transfers (8/32/64 chunks of 16KiB). SOS is queued immediately after transfer creation through unchanged APIs. Pending chunks at injection, source completion after SOS ACK, destination SOS before bulk completion, and destination SHA-256 are required. Queued-byte values are payload estimates (chunk count ×16KiB), excluding wire headers. Queue depth at a later polling observation is not an exact depth at SOS delivery.

## Results

| Test / outage | Attempts | Success | p50 ms | p90 ms | p95 ms | p99 ms† | Mean ms | Min ms | Max ms | Std ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| text | 0 | 0/0 | — | — | — | — | — | — | — | — |
| internet | 0 | 0/0 | — | — | — | — | — | — | — | — |
| store_forward / 5s | 0 | 0/0 | — | — | — | — | — | — | — | — |
| store_forward / 10s | 0 | 0/0 | — | — | — | — | — | — | — | — |
| store_forward / 20s | 0 | 0/0 | — | — | — | — | — | — | — | — |
| store_forward / 30s | 0 | 0/0 | — | — | — | — | — | — | — | — |
| sos_idle | 20 | 20/20 | 187.5 | 733.7 | 867.7 | 1167.1 | 338.9 | 110.0 | 1242.0 | 291.7 |
| sos_low | 0 | 0/0 | — | — | — | — | — | — | — | — |
| sos_medium | 0 | 0/0 | — | — | — | — | — | — | — | — |
| sos_high | 0 | 0/0 | — | — | — | — | — | — | — | — |
| discovery | 0 | 0/0 | — | — | — | — | — | — | — | — |
| relay_recovery | 0 | 0/0 | — | — | — | — | — | — | — | — |

## SAGE shadow mode A/B

Same suites with SAGE shadow scoring on vs off (`--sage`). Shadow mode must not change outcomes: compare success and latency per row.

| Test | SAGE | Attempts | Success | p50 ms | p95 ms |
|---|---|---:|---:|---:|---:|
| sos_idle | off | 10 | 10/10 | 208.5 | 1064.7 |
| sos_idle | on | 10 | 10/10 | 172.5 | 501.8 |

SAGE on-phone scoring (from the app's shadow statistics at the end of each `--sage on` stage):

| File | Role | Scored | Eco would skip | Peer summaries | Score p50 µs | Score p95 µs | Errors |
|---|---|---:|---:|---:|---:|---:|---:|
| sage_stats_full_f15556.json | A | 1 | 0 | 1 | 3390 | 3390 | 0 |
| sage_stats_full_f15556.json | B | 0 | 0 | 2 | 0 | 0 | 0 |
| sage_stats_full_f15556.json | C | 0 | 0 | 1 | 0 | 0 | 0 |

† P99 is unstable at these sample sizes. See benchmark_summary.csv for separate overall RTT and recovery distributions and HTTP execution times.

## Stage audit

| Stage | Attempts | Passed | Failed |
|---|---:|---:|---:|
| full | 20 | 20 | 0 |
| mini | 45 | 45 | 0 |
| smoke | 9 | 9 | 0 |
| warmup | 10 | 10 | 0 |

## Failures and outliers

No recorded attempted-run failures.

Largest successful total latency: sos_idle #8, 1242ms. Store/relay runs include intentional outage time.

## Claims discipline

**VALIDATED:** Only the physical attempts actually recorded in this session (20 full-matrix attempts so far). Detailed per-packet evidence is in parsed run JSON files.

**SOFTWARE-ENFORCED:** Two-hop A–B–C application topology and rejection of direct A–C links. All phones are in the same test environment.

**SIMULATED:** No simulated measurements included in these tables. Existing JVM tests are functional checks, not physical evidence.

**NOT TESTED:** Event-scale operation, 20,000 phones, emergency guarantees, RF isolation, field range, battery longevity, Bluetooth-only performance, Internet isolation at the OS level, simultaneous mixed-traffic suite, single-hop comparison, or statistical significance.

## Artifacts

Session directory: `sessions/physical_20261009_03/`. `raw/`: logcat, dumpsys, command observations. `parsed/`: per-attempt evidence, CSVs. `plots/`: distributions, visible outliers and success rates. Original JSONL is append-only. Tests with missing measurements are shown as untested, never filled from earlier demos.

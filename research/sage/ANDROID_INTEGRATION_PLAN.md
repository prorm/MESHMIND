# Android integration plan — SAGE v1 → MeshMind router

> **Status, 9 Oct 2026: steps 2–4 and rollout step 1 (shadow mode) are built and JVM-tested.**
> - **Safety fix (§2):** `Router.adviseLocked`, tested by `AdvisorSafetyTest`. The advisor never sees CRITICAL, ACKs, beacons or light controls. A throwing, empty or foreign answer falls back to flooding, and 3 consecutive answers slower than 5 ms switch the advisor off.
> - **§3 on-phone pieces:**
>   - `ContactHistory` is the phone's own row.
>   - `SageFeatures` holds the 59 features.
>   - `SageNet` is a pure-Kotlin forward pass instead of ONNX Runtime/LiteRT: 15k parameters need no native runtime, and APK size and JNI risk stay at zero.
>   - Parity with the golden vectors from `scripts/export_kotlin.py`: features within 4.8e-7, outputs within 2.4e-7.
>   - The peer exchange is a bounded `Frame.Extension` summary, not a new SAGE_SUMMARY packet.
> - **Persistence:** a versioned file, not Room. Avoiding a schema bump avoids `fallbackToDestructiveMigration` wiping the store-and-forward buffer.
> - **Field telemetry:** `traces.load_meshmind_field`.
> - **Still to do:** the §6 phone measurements (`benchmarks/benchmark_all.py --sage on|off`). Ranking authority and eco mode stay off.

No Android production source has been changed. This plan says what must be built, in what
order, and what must stay deterministic. Research validation (RESULTS.md) is simulation
only; nothing here is a phone performance claim.

## 1. The existing router (inspected)

| Existing implementation | Behaviour relevant to SAGE |
|---|---|
| `core/.../model/Packet.kt` | Packet fields: UUID packet ID; source and destination IDs; type; priority (`CRITICAL/HIGH/NORMAL/LOW`); `createdAt`/`expiresAt` in ms; hop TTL (default 8); `hopCount`; payload bytes; `routeHistory`. `*` means broadcast; `@gateway` means any Internet gateway. |
| `routing/Router.kt` | Epidemic store-and-forward with two shortcuts: direct destination and known gateway next hop. TTL is decremented per hop; route history prevents loops; `peerHas` inventories avoid resending. |
| `NextHopAdvisor.choose(packet, candidates)` | Optional hook that may narrow candidate links. **It currently runs for CRITICAL packets too.** A non-empty narrowed list is accepted; the empty-list fallback is not a complete safety bypass. |
| `DuplicateTracker`, ACK / SUMMARY / LINK_ACK | Seen cache; delivery ACKs and summary lists stop replication. This is the behaviour the v1 simulator's ACK gossip models. LINK_ACK windows chunks: 2 in flight, CRITICAL bypasses. |
| `PacketPriorityQueue` | Per-link rank, then FIFO. CRITICAL pre-empts between chunks. |
| `GatewayTable` | Distance-vector-lite gateway beacons; entries expire after 90 s; nearest by hop count. |
| `MeshTransport` / `NearbyTransport` | Nearby P2P_CLUSTER, 32 KB BYTES payloads, 16 KB application chunks; HELLO maps endpoints to persistent node IDs. |
| `MeshState` (`PeerInfo`, `MeshStats`, `edges`, `knownNodes`) | Peers, bytes sent, connection times, delivery metrics and topology edges. Usable as raw telemetry. |

## 2. Prerequisite safety fix (before any model is attached)

1. In `Router`, call the advisor **only** for non-CRITICAL, non-SOS packets. The CRITICAL
   path must be byte-for-byte what it is today.
2. Add a JVM test, the Kotlin twin of `tests/test_v1_sim.py::Adversary`: an advisor that
   throws or returns `[]` for everything, and asserts that CRITICAL/SOS delivery, route and
   latency in `MeshSimulation` are unchanged.
3. Add a timeout and an exception guard around the advisor; on any failure, use the
   existing epidemic candidates.

## 3. What SAGE needs on the phone

SAGE v1 is one ONNX graph (`outputs/v1/checkpoints/sage_net.onnx`, 75 KB). It takes
input `features[N, 59]` (float32) and produces `outputs[N, 6]`:

```
u_self, u_peer, p_reach_self, p_reach_peer, gain_replicate, gain_forward
```

Feature names, order and units are in `sage_net.json`; `sage/v1/state.py` is the reference
implementation. A Kotlin port must match it numerically. Ship a golden-vector test: 256
rows of research features, compared to Kotlin features and ONNX outputs within 1e-4.

### New component: `ContactHistory` (Kotlin twin of `NodeState`)

A bounded per-phone store, persisted in Room so it survives restarts and "phones remember
yesterday":

| State | Kotlin source of truth | Memory for N known peers |
|---|---|---|
| `lastMet[peer]`, `count[peer]`, EWMA rates (1 h, 24 h) | Contact bins: every *connected* or *discovered* peer counts once per scan interval (`ctx_bin_log` encodes that interval; training used 20 s and 300 s) | O(N) |
| PRoPHET tables, fast and slow aging, incl. the virtual `@gateway` column | Update on encounter start; transitive update from the peer's shared vector | O(N) per table |
| Node activity EWMAs (10 m, 1 h, 6 h, 24 h), unique neighbours | The same contact bins | O(1) + O(N) |
| Hour-of-day histogram per peer | The same contact bins | O(24·N), capped (LRU at a few thousand peers) |

Only the phone's **own** rows are kept: the research matrices are N×N because they
simulate everyone, but a phone needs one row.

### Feature mapping

| Feature block | Phone source |
|---|---|
| `p_*` (10) | `Packet`: priority → `p_media` (LOW/CHUNK), `p_control` (HIGH), `p_text` (NORMAL); CRITICAL never reaches the model. Also `@gateway`; payload size; `(now−createdAt)/(expiresAt−createdAt)`; remaining lifetime; replicas from `peerHas`; scan interval. |
| `self_*` (22) | `ContactHistory` own row; `PacketStore` queue bytes/count vs the configured cap; `BatteryManager`; `GatewayService` (is this phone a consenting gateway); `ttl − hopCount`. |
| `peer_*` (22) | **Sent by the peer** in a compact, versioned `SAGE_SUMMARY` frame on connect: its activity EWMAs, queue fill, battery bucket, gateway flag, plus per-destination stats for the packet IDs offered (pairs the existing inventory exchange). Bound the size, rate-limit, and treat values as untrusted (clip to training ranges). |
| `pr_*` (5) | Own `ContactHistory` row for the peer; neighbour-set Jaccard from a small Bloom-filter sketch in `SAGE_SUMMARY`. |

## 4. Decision flow

```
Router.onLinkReady / schedule(link)
  ├─ CRITICAL / SOS ──────────────► existing deterministic path (no feature build, no model)
  ├─ direct destination / gateway ► existing shortcut
  └─ ordinary candidates
        build features (off the router mutex, on a single-thread dispatcher)
        ONNX Runtime Mobile (1 thread, batch per link wake-up, ≤5 ms budget)
        ├─ timeout / error / NaN / schema mismatch → existing epidemic order (+ log fallback)
        └─ scores → advisory rank inside PacketPriorityQueue *within the same priority class*
                     + "skip this copy" if gain_replicate < τ (eco mode only)
  revalidate TTL, expiry, inventory, link state → transport
Eviction (PacketStore full): ordinary packets only, lowest u_self first; CRITICAL never.
```

Operating points carried over from validation:

| Mode | Threshold | Ordering | Held-out sim result |
|---|---|---|---|
| Max-delivery | none (replicate broadly) | per-byte | +0.54 points vs epidemic, same airtime |
| Eco | `gain_replicate` > 0.005 | per-byte | Equal delivery, −28% airtime |

## 5. Rollout

1. **Shadow mode.** Compute SAGE scores and log what SAGE *would* do: rank, skip,
   eviction choice. The router stays exactly as today. Log the outcome fields from
   RESULTS.md, using pseudonymous IDs and no message content.
2. **Offline replay of field logs.** Feed shadow telemetry into the research simulator to
   recalibrate airtime, contact duration and loss. Retrain SAGE v2 on real MeshMind
   contacts. This is the data flywheel in the v3 deck.
3. **Ranking-only authority.** Within-class ordering only, on staff devices, behind a
   remote kill switch. Copies and evictions stay deterministic.
4. **Eco mode.** Skip low-gain copies only after a held-out field A/B shows equal delivery
   and lower airtime or battery.

Each step requires the previous one's measured win, mirroring the research rule: if ML
loses, ship the baseline.

## 6. What must be measured on phones before any claim

- ONNX Runtime Mobile vs LiteRT latency and memory on the three test phones, with the
  screen off and in Event Mode.
- Feature-build cost per link wake-up, and battery over a 2 h Event Mode run.
- `SAGE_SUMMARY` bytes per contact, and its effect on SOS latency (it must ride below
  CRITICAL).
- Numerical parity of Kotlin features with research features (golden vectors).

## 7. Do not change during integration

- the rule-based SOS classifier and CRITICAL bypass;
- the wire format of existing frames (add new frames; do not alter old ones);
- Room schema compatibility;
- chunking and backpressure;
- gateway HTTP controls.

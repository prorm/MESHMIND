# MeshMind — when the cell network dies, the crowd becomes the network

## Quick start

| Part | What it is | Where |
|---|---|---|
| `core/` | Pure-Kotlin mesh: routing, store-and-forward, priority, gateway fair-share scheduler, signed packets | `./gradlew :core:test` |
| `app/` | Android app (Compose + Google Nearby Connections) | `./gradlew :app:assembleDebug` |
| `backend/` | Python service for Internet-connected gateways: AI answers (Gemini or Claude), phone verification, live-info board | [backend/README.md](backend/README.md) |
| `research/sage/` | SAGE routing model research (the phones run it in shadow mode) | [research/sage/README.md](research/sage/README.md) |

```powershell
# 1. Backend on a laptop (same Wi-Fi as the gateway phone)
cd backend
uv venv .venv --python 3.12; uv pip install --python .venv\Scripts\python.exe -r requirements.txt
copy .env.example .env          # then put your GEMINI_API_KEY in backend\.env
.venv\Scripts\python -m meshmind_backend.server   # prints the authority key, the provider, the admin token

# 2. App (JDK 17 + Android SDK)
./gradlew :core:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In the app, put `http://<laptop-ip>:8787` in **Share my Internet** (gateway phone) and **Identity** (to verify each phone).
The phone test plan is in [docs/sprint2-testing.md](docs/sprint2-testing.md).

**Sprint 2: Ask, fair share, trust.**
- **Ask through the mesh.** Phones with no Internet ask questions and read pages through a gateway phone. Answers come from the backend (Gemini 3.5 Flash-Lite by default; Google Search grounding when billing is enabled) and are signed by the MeshMind authority. Every phone caches verified answers, so the same question is not fetched from the Internet twice.
- **Need-aware fair-share queue.** One gateway serves a crowd: SOS first, then emergency, health and safety questions. Waiting raises priority, phones that were just served drop back, and "urgent" claims are capped per phone. Every decision shows its reasons.
- **Signed packets and verified identity.** Every packet and HELLO is signed (ECDSA P-256), so messages cannot be altered or sent in someone else's name. Phones verify their number once to get a ✓; organizer and responder roles can broadcast notices. Phone numbers stay on the backend.
- **Live info board.** Weather, organizer notices and official alerts, signed and broadcast to every phone.

Status: 80 JVM tests, 10 backend tests and a live backend↔client check pass; both APKs build. Not yet run on phones.

**New: MeshMind Conductor.** Orchestrate joined phones from the host phone or a paired laptop browser over USB: spatial waves, ripples, spirals, constellation twinkles, group/position assignment, beat-aligned scenes, twelve-cue plans, blackout/restore, and a five-act finale. Simple Light Show presets remain available. Playback uses a buffered timeline and monotonic clock-offset probes; SOS retains higher priority. Implemented and covered by JVM/browser checks; Android USB, actual display skew and crowd-scale capacity still need phone measurements. See [usage, protocol and device acceptance procedure](docs/light-show.md), and the updated [v5 brochure source](docs/pitch/MeshMind_Pitch_v5.html).

MeshMind is a proof-of-concept Android app (with a pure-Kotlin core) for packed places like concerts, stadiums, festivals and disaster zones, where mobile data stops working. Nearby phones relay messages for each other over Bluetooth / Wi-Fi using **Google Nearby Connections**. All multi-hop routing is done **at the application layer**. A phone that does have Internet can act as a **gateway** that runs small, allowlisted requests on behalf of phones that have none.

```
NO INTERNET ❌            NO INTERNET ❌            NO INTERNET ❌            Internet ✓
   A  ●────────────────────●  B  ●────────────────────●  C  ●────────────────────●  D (gateway) ──► Internet
   └── INET_REQUEST ──► B ──► C ──► D ── HTTPS GET (allowlisted) ──► response ──► C ──► B ──► A
```

---

## 1. Status: what is proven and what is not

| Milestone | State | Evidence |
|---|---|---|
| 1. Builds; core JVM module + SimulatedTransport | ✅ done | `./gradlew :core:test`, `./gradlew :app:assembleDebug` |
| 2. Flooding, dedupe, TTL, expiry, route history (tests 1, 4, 5) | ✅ done | `RoutingTest` |
| 3. Store-and-forward + persistence + SUMMARY exchange (test 2) | ✅ done; also run on 2 real phones | `RoutingTest`; §1a |
| 4. Priority queue + chunking + SOS preemption (test 3) | ✅ done; **preemption verified on 2 real phones** after adding link-level flow control | `PriorityTest`, `FlowControlTest`; §1a |
| 5. Gateway beacons, GatewayTable, INET_REQUEST/RESPONSE, allowlist (tests 6–8) | ✅ done | `GatewayTest`; one relay hop on real phones (§1a) |
| 6. NearbyTransport on real phones: discover, connect, direct text | ✅ **verified: OnePlus 12R ↔ Galaxy S24 Ultra** | §1a |
| 7. Real multi-hop on 3 phones + store-and-forward | 🟡 **store-and-forward verified on 2 phones; multi-hop (A↔B↔C) NOT yet run on radios.** It needs a 3rd phone; only 2 were available | §6 procedure |
| 8. Real gateway demo | 🟡 **verified with one relay hop on real phones**: an airplane-mode phone → Bluetooth → gateway phone → Internet → back. The 3–4 phone version needs more phones | §1a |
| 9. Network graph + debug screen | ✅ built and used on the phones (all numbers below come from its exports) | — |
| 10. Semantic priority polish, progressive images | ⛔ intentionally not started | The spec only allows this after 6–8 are fully proven; multi-hop on radios is still open |
| 12. Fast detection (gateway learned behind a stale backlog) | ✅ fixed in the router; JVM-reproduced from the phone logs: **25.9 s → 0.25 s** after the link; phones to re-measure | `FastDetectionTest`; §9 |
| 13. SAGE v1 on the phone, shadow mode | ✅ built; Kotlin features/model match research within 5e-7; scores ordinary traffic, never changes routing | `SageGoldenTest`, `SageShadowTest`, `AdvisorSafetyTest`; §9 |
| 11. Offline Light Show + Conductor | ✅ implemented; JVM/browser-tested, Android APK builds | 17 feature/core HTTP tests; 10 headless console checks; Android USB, real display skew and crowd capacity still need measurements; [device procedure](docs/light-show.md) |

**To be clear:**
- **Proven on the JVM:** routing, store-and-forward, priority, gateway, Light Show timing, choreography and loopback console behaviour, fast gateway detection, handshake recovery, the advisor safety envelope and SAGE parity, against the same core code the app runs (57 tests).
- **Proven on two real phones:** the direct link, store-and-forward, the Internet relay and SOS preemption.
- **Not yet proven on radios:** multi-hop forwarding through a relay phone. It needs a third device.

### 1a. What has run on real Android (2026-10-05)

**Real phones:**
- **Phone A:** OnePlus 12R (CPH2585), Android 16, over USB adb.
- **Phone B:** Samsung Galaxy S24 Ultra (SM-S928B), Android 16, over wireless adb.
- Both have Google Play services; Bluetooth link via Nearby P2P_CLUSTER.
- RTT is measured on the sender's own clock. The phones' clocks differed by ~0.9 s, so one-way numbers are not reported.

| # | Scenario | Result |
|---|---|---|
| R1 | Both phones start the mesh | ✅ Linked in **~17 s**, HELLO exchanged persistent IDs (`A-77c9` ↔ `B-1195`) |
| R2 | Text A→B ("Meet me at Gate C") | ✅ `A-77c9 → B-1195 ✓ DELIVERED`, **ACK RTT 343 ms** |
| R3 | Store-and-forward: B stops its mesh; A sends; B restarts 30 s later | ✅ A shows `QUEUED: no peer yet, held here`. After B returns, SUMMARY/want delivers it **~12 s after B's restart**, A flips to DELIVERED (total RTT 39.8 s) |
| R4 | **Headline demo:** A in airplane mode (Wi-Fi off, Bluetooth on, `Internet: ✕ none`); B is the gateway on Wi-Fi; A requests `https://example.com/` | ✅ A learns `Internet gateways: 1 (nearest B-1195, 1 hop)` from the beacon. `OUT A-77c9 → B-1195 → 🌐 Internet / BACK B-1195 → A-77c9`, HTTP 200, 577 B, real page shown on the offline phone, **round trip 1172 ms** |
| R5 | SOS on an idle link | ✅ CRITICAL, **ACK RTT 116 ms** |
| R6 | 1 MB LOW transfer (64 × 16 KB chunks) A→B, MEDICAL SOS fired ~7 s in (after the flow-control fix) | ✅ SOS DELIVERED, **ACK RTT 145 ms**, 18 chunks still queued behind it; transfer then completed: **1 MB in 9.99 s (~105 KB/s)** |
| R6-before | Same, before the fix | ⚠️ SOS jumped 38 queued chunks but took **2374 ms**, because chunks were already sitting inside Nearby's buffer (see bug 6) |
| R7 | OnePlus alone as gateway, GET example.com | ✅ HTTP 200, 577 B, **286 ms** via `GatewayPolicy` + `JvmHttpExecutor` |

**Emulators** (2 × API 35, Play services 24.23, emulator virtual Bluetooth), run before the second phone was available:
- Direct text: ACK RTT 1234 ms.
- Offline A → gateway B → Internet: 1717 ms.
- `https://www.google.com/` → `REJECTED: host 'www.google.com' is not on the gateway allowlist`, nothing fetched.

**Platform observations**
- Nearby's **Bluetooth Classic** advertising times out on both phones (`Failed to set Bluetooth scan mode to 23`). Android 13+ won't let an app make the phone Classic-discoverable without a system prompt, so Nearby runs on BLE (+ Wi-Fi when available). It still works, but expect BLE-class throughput (~100 KB/s measured).
- Switching A's Wi-Fi off dropped the existing Nearby link: it had upgraded to Wi-Fi LAN. It re-formed over Bluetooth in ~70 s.

**Bugs found only by running on devices (all fixed, with JVM regression tests where possible):**
1. **Location permission:** Nearby discovery failed with `MISSING_PERMISSION_ACCESS_COARSE_LOCATION (8034)` on API 35 / Play services 24.23, although the docs say location isn't needed on Android 12+. Location is now requested on every API level.
2. **Keyboard covered inputs:** on Android 15+ (targetSdk 35, edge-to-edge forced) the soft keyboard covered the text fields and their buttons, so the rename "Set" tap landed on the keyboard. Fixed with `imePadding()`. ID fields also turn off autocorrect, which was mangling node names.
3. **Truncated transfers:** leaving the screen right after starting a transfer cancelled the UI coroutine mid-way. Only 62 of 64 chunks were created, so the transfer could never complete. Router send APIs now run `NonCancellable` (`CancellationTest`).
4. **No connection retry:** a BLE connection attempt can fail with `STATUS_ENDPOINT_IO_ERROR (8012)`, and the transport never retried, so two phones sat side by side unlinked for minutes. There are now per-endpoint retries with exponential backoff + jitter, plus a periodic re-attempt for visible-but-unlinked peers.
5. **Endless ACKs:** ACK packets were never acknowledged themselves, so relays replicated them for 24 h. ACKs now live 30 min; delivery knowledge also travels in SUMMARYs.
6. **Nearby reports SUCCESS too early.** Per-chunk logs from both phones showed SUCCESS arriving **1.3–2.9 s before the peer received the chunk**. Chunks therefore piled up inside Nearby, beyond the reach of our priority queue, and an SOS waited behind them.
   - **Fix:** a hop-by-hop `LINK_ACK` frame for chunks. The sender keeps at most `bulkWindow = 2` un-acked chunks per link; non-bulk traffic bypasses the window, and a 5 s timeout stops older peers from stalling it.
   - **Modelled in `FlowControlTest`** (a simulated transport that reports success early): no window → SOS 8501 ms; window 2 → 311 ms.
   - **On phones:** 2374 ms → 145 ms.
7. **UI labels:** a rejected Internet request was drawn as `→ 🌐 Internet`; it now reads `✕ refused by gateway`. The radio status also didn't update after a discovery failure.

---

## 2. Project layout

```
MeshMind/
├── core/                         pure Kotlin/JVM, no Android dependencies
│   └── src/main/kotlin/dev/meshmind/core/
│       ├── model/        Packet, payloads, WireCodec (compact binary, 16-byte UUIDs)
│       ├── routing/      Router, PacketStore(+InMemory), DuplicateTracker, PacketPriorityQueue,
│       │                 GatewayTable, MeshState (snapshots, logger, metrics)
│       ├── ai/           PriorityClassifier, RuleBasedPriorityClassifier
│       ├── gateway/      GatewayPolicy, RateLimiter, GatewayService, InternetExecutor,
│       │                 JvmHttpExecutor (real HTTPS), FakeInternetExecutor
│       ├── transport/    MeshTransport interface, SimulatedNetwork / SimulatedTransport
│       └── sim/          MeshSimulation (virtual nodes running real Routers)
└── app/                          Android (Compose, Room, Nearby, foreground service)
    └── src/main/java/dev/meshmind/app/
        ├── net/          NearbyTransport (P2P_CLUSTER), InternetMonitor
        ├── data/         Room database + RoomPacketStore
        ├── service/      MeshService (foreground, type connectedDevice)
        ├── sim/          SimulationController (in-app A–E simulator)
        ├── ui/           Home, Messages, SOS, Internet via Mesh, Network, Debug, Simulation
        └── MeshRuntime   node identity, settings, router/transport lifecycle
```

Transport and routing are kept fully separate. `Router` only sees `MeshTransport`, a one-hop byte pipe with `send(endpointId, bytes): Boolean` plus link-up, link-down and received callbacks. `NearbyTransport` and `SimulatedTransport` are the two implementations, so the tests run exactly the same routing code that the phones run.

---

## 3. How it works

### Packet
`packetId (UUID) · sourceId · destinationId (node | "*" broadcast | "@gateway") · type · priority · createdAt · expiresAt · ttl (8) · hopCount · payload · routeHistory · label`

Types: `TEXT, SOS, ACK, GATEWAY_BEACON, INET_REQUEST, INET_RESPONSE, SUMMARY, CHUNK, LIGHT_SHOW`. Each link starts with a `HELLO` frame that exchanges the persistent NodeID, because Nearby endpoint IDs change every session. Light Show controls are short-lived, memory-only packets without end-to-end ACKs; playback uses a separately synchronized monotonic timeline.

### Routing (epidemic first, then two deterministic shortcuts)
1. **Flooding:** a packet is offered to every handshaked neighbour except the one it came from, the nodes already in its `routeHistory`, and peers known to hold it already.
2. **No infinite forwarding:** three mechanisms work together:
   - a seen-ID cache (`DuplicateTracker`)
   - `ttl`, decremented on every transmission
   - `expiresAt`, enforced on receive, on send and by the periodic `tick()`

   The JVM tests cover a three-node loop and a 12-node line (the packet stops at hop 8).
3. **Shortcuts:**
   - If the destination is a direct neighbour, send only to it.
   - An `@gateway` request goes only to the next hop toward the nearest gateway in the `GatewayTable`, and floods only when no gateway is known.

   `NextHopAdvisor` is the empty hook for later mobility-aware or ML next-hop choice.
4. **Destination delivers once.** It then sends an **ACK** carrying the route it saw and its delivery time.

### Store-and-forward
- Every packet is persisted with a state: `QUEUED → FORWARDING → DELIVERED | EXPIRED`. The app stores it in Room; tests use the in-memory store.
- On every new link, each side sends a **SUMMARY**. It lists the IDs the node holds (up to 500) and the IDs it has seen ACKed (up to 500). It is link-local and never forwarded.
- The receiver marks the ACKed IDs as done and replies with a `want` list of IDs it is missing. The holder then sends those packets.
- Seeing an ACK, directly or inside a summary, **stops replication** everywhere: the ID is removed from every link queue.
- **Broadcast ACKs are the exception.** Each recipient of a broadcast SOS acks it, but those acks don't stop the broadcast spreading.
- A full summary is 2 × 500 × 16 B ≈ 16 KB, which fits in one 32 KB Nearby payload (`WireCodecTest`).

### Priority, chunking, preemption
- Nearby sends payloads in submission order and has no priority. So the router keeps **one frame in flight per link**: `send()` returns only when Nearby reports the transfer SUCCESS or FAILURE.
- The next frame always comes from a per-link `PacketPriorityQueue`, ordered by rank (CRITICAL < HIGH < NORMAL < LOW), then FIFO.
- Large data is split into **16 KB CHUNK packets**, each its own BYTES payload. FILE/STREAM payloads are never used.
- **Flow control (added after the phone tests):** Nearby reports a payload SUCCESS before the peer actually has it (measured 1.3–2.9 s early), so "one frame in flight" alone still let chunks pile up inside Nearby. The receiver therefore sends a tiny hop-by-hop `LINK_ACK` for each CHUNK, and the sender keeps at most `bulkWindow` (2) un-acked chunks per link. Control frames and non-bulk packets bypass the window, and a 5 s timeout frees a slot if a peer never acks.
- A CRITICAL SOS therefore waits for at most about two chunks per hop: 145 ms ACK RTT during a 1 MB transfer on real phones.
- SOS actions are **always CRITICAL**: `Router.sendSos` overrides whatever the classifier says.
- `RuleBasedPriorityClassifier` handles the rest. It uses keywords (e.g. "injured", "fire" → HIGH; "meet", "gate" → NORMAL), packet type and size. It sits behind the `PriorityClassifier` interface so an on-device model can replace it.

### Internet gateway
- **Becoming a gateway:** a phone needs both:
  - the owner's **explicit consent** toggle
  - Internet that Android has validated (`NET_CAPABILITY_VALIDATED`, re-checked every 60 s), or the **demo override** switch
- **Beacons:** an active gateway floods a `GATEWAY_BEACON` every 20 s, and also whenever a new link handshakes (rate-limited).
- **Gateway table:** distance is the beacon's `hopCount`. Each node keeps the best `(gateway, distance, viaPeer, lastSeen, expiry 90 s)`. Routes through a peer are dropped when that link goes down.
- **Request routing:** an `INET_REQUEST` addressed to `@gateway` is routed toward the nearest gateway. With no gateway known, it floods and waits.
- **Execution:** the first gateway to receive the request executes it once (deduped by packetId). It ACKs the request, so relays stop carrying it, and sends an `INET_RESPONSE` back to the requester through the normal router. The response includes the request's route, so A can show the whole round trip.
- **Gateway failure:** the request stays parked in the mesh. When a gateway is reachable again, the reconnect SUMMARY or the next beacon re-sends it (test 7).
- **V1 request types:**
  - HTTPS **GET** of an allowlisted URL, with the response body capped at 16 KB
  - **POST** of a short text (≤ 1 KB) to the **gateway's own configured webhook URL**. The requester cannot choose that URL.

### Gateway safety (it is not an open proxy)
- Nothing is relayed without the gateway owner's explicit consent.
- **HTTPS only**, standard port only, no credentials in the URL.
- **Allowlisted hosts** only, including their subdomains. The list is editable on the gateway phone.
- No request headers can be set by the requester; there are no other HTTP methods and no binary uploads.
- Redirects are not followed, so an allowlisted host can't bounce the gateway somewhere else.
- **Rate limits:** 6 requests/min per requester and 30/min in total.
- Response bodies are capped at 16 KB and truncation is flagged.
- Every rejection returns a `REJECTED` response that states the reason (test 8).

### Why this is not general Internet sharing
An Android app can't route other phones' IP traffic through itself over Nearby:
- Nearby Connections hands an app byte payloads between apps. It is not a network interface.
- The system hotspot / tethering is one hop only, and apps can't control it.
- `LocalOnlyHotspot` has no Internet uplink.

The only full-IP route is a `VpnService` on every client, tunnelling all IP packets over a multi-hop Bluetooth mesh to a gateway that does userspace NAT. That would be:
- far too slow (tens of KB/s shared across hops, and every TCP round trip crossing the whole mesh)
- battery-destructive
- an open proxy that makes the gateway owner answerable for strangers' traffic

So MeshMind relays **small, explicit, allowlisted application requests** instead.
- **It can:** fetch small allowlisted pages or APIs (weather, a status page), and post an SOS or message to a webhook (e.g. ntfy.sh, which shows the message on any browser or phone subscribed to the topic).
- **It cannot:** browse arbitrary sites, stream, run apps' own traffic, or carry anything larger than 16 KB per response.

---

## 4. Android constraints and how they shaped the design

| Constraint | Effect on the design |
|---|---|
| Nearby needs **Google Play services** | The Home screen warns when it is missing. No Play services means no mesh, so de-Googled phones and iOS are not supported. |
| Nearby has **no multi-hop** | All routing, dedupe, TTL, ACKs and store-and-forward live in `core`. |
| **Few links per phone** (Bluetooth realistically 3–4) | Links are capped at 4 per phone and the design never assumes a full mesh; flooding + store-and-forward tolerate sparse graphs. |
| **No payload priority**; BYTES payloads ≤ 32 KB | Chunking in the app, one frame in flight per link, a priority queue per link. |
| **Endpoint IDs change** each session | The `HELLO` handshake carries the persistent NodeID; the router keys everything on NodeID. |
| **Simultaneous connection requests** collide | The smaller name initiates first; the other side retries after 6 s. |
| **Bandwidth upgrade** to Wi-Fi hotspot/Direct can disconnect the phone from its Wi-Fi network | `ConnectionOptions.setDisruptiveUpgrade(false)`. Prefer mobile data on the gateway phone. |
| **Permissions differ by API level** | 12+: `BLUETOOTH_SCAN/ADVERTISE/CONNECT`. 13+: `NEARBY_WIFI_DEVICES` (+ optional `POST_NOTIFICATIONS`). **Location (coarse + fine) on every level**: the docs say 12+ doesn't need it, but Play services 24.23 on API 35 refused discovery without it (observed). Requested at runtime before the service starts. |
| **Background limits** | Foreground service of type `connectedDevice` (required on Android 14+), with a persistent notification and a Stop action. Doze can still slow it down; battery-optimisation exemption is not requested. |
| **Battery** | Discovery is continuous only while isolated. With peers it runs 20 s per minute, and it stops at the link cap. Beacons every 20 s, housekeeping every 10 s, no rebroadcast loops (dedupe), SUMMARY only on new links. |
| **Emulators don't support Nearby** | Real phones are needed for milestones 6–8. The in-app Simulation screen and the JVM tests cover the logic. |
| **Phone clocks disagree** | "One-way latency" uses two clocks and is marked `*`. **ACK RTT** uses one clock and is the reliable number to report. |
| **Range** | Bluetooth reaches roughly 10–30 m and 2.4 GHz is absorbed by bodies. Dense crowds help (more relays) and hurt (attenuation, interference). |

Security note: this is a prototype.
- Links are encrypted by Nearby, but connections are auto-accepted without checking the auth token.
- Packets are neither signed nor end-to-end encrypted, so any MeshMind phone can read relayed messages or inject packets.
- The gateway's protections (consent, allowlist, caps, rate limits) are the only hardened part.

---

## 5. Build and test

**Requirements**
- **JDK 17** for the Gradle daemon. `gradle/gradle-daemon-jvm.properties` asks for 17, and Gradle picks up an installed JDK 17 automatically, even when the shell's `JAVA_HOME` points elsewhere.
- **Android SDK** with platform 35 and build-tools 35.
- `local.properties` must point at the SDK: `sdk.dir=D\:/sonictools/android-sdk` on this machine. Android Studio writes this file for you.

Versions: AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Gradle 8.13, Compose BOM 2024.12.01, Room 2.6.1, play-services-nearby 19.3.0.

```bash
# All JVM tests: no phones, no Android SDK needed for :core itself
./gradlew :core:test                      # Windows: gradlew.bat :core:test

# Watch every node's log for one scenario
MESH_TRACE=1 ./gradlew :core:test --tests '*finalDemoScenario*'

# Build and install the app
./gradlew :app:assembleDebug              # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the `MeshMind/` folder in Android Studio and run the `app` configuration.

### Test suite (42 tests, all passing)

| Spec # | Test | What it proves |
|---|---|---|
| 1 | `RoutingTest.multiHopTextFollowsPathAndIsAcknowledged` | A↔B↔C: A→C takes path A→B→C, C gets it with 2 hops, A sees DELIVERED with the ACKed route |
| 2 | `RoutingTest.storeAndForwardDeliversWhenPeerAppears` | C absent: B holds the packet (QUEUED). B↔C connects 60 s later, SUMMARY/want delivers it, and the ACK flips B to DELIVERED |
| 3 | `PriorityTest.sosPreemptsChunkedTransfer` | LOW 160 KB transfer (10 chunks) on A↔B↔C↔D. A CRITICAL SOS sent at 250 ms reaches D at 595 ms after only **3/10** chunks; the transfer finishes at 1386 ms and reassembles byte-for-byte |
| 3 (control) | `PriorityTest.withoutPriorityDifferenceMessageWaitsBehindTransfer` | Same scenario with equal priority: the message arrives after **all 10** chunks, so the preemption comes from the scheduler |
| 4 | `RoutingTest.duplicateViaTwoPeersIsProcessedOnce` | Diamond A–B–D / A–C–D: D delivers exactly once and rejects ≥1 duplicate |
| 5 | `RoutingTest.loopDoesNotPropagateForever`, `ttlLimitsHops` | A↔B↔C↔A loop terminates with bounded frames; on a 12-node line the packet stops at hop 8 (TTL) |
| 6 | `GatewayTest.requestReachesGatewayAndResponseReturns` | A learns gateway D at 3 hops via B. The request goes A→B→C→D, the response returns D→C→B→A, it runs exactly once, and side-branch node E never sees the request |
| 7 | `GatewayTest.requestWaitsForGatewayToReturn` | C↔D cut: the request parks on C. C↔D restored: the request completes |
| 8 | `GatewayTest.gatewayRejectsDisallowedAndOversizeRequests`, `policyUnitChecks` | Non-allowlisted host, oversize POST and `http://` are REJECTED with a reason, and nothing reaches the Internet. Also covers ports, credentials, look-alike domains, consent and rate limits |
| extra | `storedPacketSurvivesRelayRestart`, `expiredPacketIsNotDelivered`, `ackStopsReplication`, `responseBodyIsCapped`, `firstGatewayResponseWins`, `WireCodecTest.*`, `classifierRules`, `queueOrdersByPriorityThenFifo` | Persistence across restart, expiry, ACK replication stop, 16 KB cap, multiple gateways, wire format fits Nearby's 32 KB limit, classifier and queue order |
| bugfix | `CancellationTest.cancellingTheCallerDoesNotTruncateATransfer` | Cancelling the caller (user leaves the screen) can't truncate a transfer; the store suspends like Room |
| bugfix | `FlowControlTest.bulkWindowKeepsSosFastWhenTransportBuffers` | With a transport that reports success early (like Nearby): no window → SOS 8501 ms behind every chunk; `bulkWindow=2` → 311 ms, 42 chunks behind it |
| demo | `SuccessCriteriaTest.finalDemoScenario` | The four success criteria end to end; prints the table below |

**Simulated numbers.** These come from `SuccessCriteriaTest`, with each link modelled as 40 ms per frame + 64 KB/s. They are **simulation numbers, not phone measurements**:

```
scenario                     route                    hops  one-way   rtt / round-trip
1 text A→C                   A→B→C                    2     82 ms     164 ms
2 store&fwd (C away 30 s)    A→B→C                    2     30205 ms  30287 ms
3 GET via gateway D          A→B→C→D→🌐→D→C→B→A       6     -         293 ms
4 SOS during 256 KB LOW      A→B→C→D                  3     917 ms    1080 ms
   transfer: 16 chunks; 6 arrived before the SOS, 10 after; transfer took 5387 ms
```

---

## 6. Real-phone test procedure (milestones 6–8 and the final demo)

**Hardware:** 3–4 Android 8+ phones with Google Play services.
- Phones A–C: airplane mode on, then **Bluetooth back on**. Wi-Fi can be on but must not be connected to any network.
- Phone D: Internet over **mobile data** (preferred) or Wi-Fi.

**Setup on every phone**
1. `adb install -r app-debug.apk`, then open MeshMind.
2. On Home, while the mesh is stopped, **Rename** the phone to `A`, `B`, `C` or `D`. Its ID becomes e.g. `A-3f9c`.
3. Tap **Start mesh** and grant the permissions. On Android ≤ 12, also turn on Location.
4. **Topology lock** (Network screen): phones on one table all hear each other, so force a line:
   - A: `B`
   - B: `A, C`
   - C: `B, D`
   - D: `C`

   You can skip this if the phones are physically far enough apart that A can't hear C.

**M6 – direct link (A, B):**
- Home on each phone should show `Connected peers: 1` and the other phone's ID.
- On A, open Messages, pick B and send.
- Pass: B receives it and A shows `A → B ✓ DELIVERED`.

**M7 – multi-hop + store-and-forward (A, B, C):**
1. On A, send "Meet me at Gate C" to C.
   - Expect on A: `A → B ✓  B → C ✓  DELIVERED`, 2 hops.
   - Expect on C: route `A → B → C`.
2. On C, tap **Stop mesh**. On A, send again.
   - Expect: A shows "in transit / stored on relays", and B's Debug log shows `stored TEXT … no eligible peer now (store-and-forward)`.
3. On C, tap **Start mesh**.
   - Expect: after the handshake, B's log shows `SUMMARY … requesting` / `serving`, C receives the message, and A flips to DELIVERED.

**M8 – Internet gateway (A, B, C, D):**
1. On D, open Internet via Mesh and turn on **"I consent to relay…"**.
   - D shows `Gateway role: ACTIVE`, and within about 20 s the other phones show `Internet gateways: 1 (nearest D-…, N hops)`.
2. On A, open Internet via Mesh, pick `example.com` and tap **GET via mesh**.
   - Expect: `OUT A → B → C → D → 🌐 Internet` and `BACK D → C → B → A`, with the HTML shown on A.
3. Webhook (optional): on D, set the webhook URL to `https://ntfy.sh/<some-random-topic>` and open that URL in a laptop browser. On A, tap **POST to webhook via mesh**. The message appears in the browser.
4. Gateway failure: stop the mesh on D, send a GET from A (it shows WAITING), then start D again. The request completes.

**Final demo, criterion 4 (preemption):**
1. On A, open Messages, pick D and tap **1024 KB** (64 LOW chunks).
2. While its progress bar moves, open SOS, pick D and tap **🚨 MEDICAL SOS**.
3. Expect:
   - On A: the SOS shows DELIVERED while the transfer is still incomplete, and the transfer then finishes.
   - On D: the Debug "Delivery metrics" table shows the SOS row before the last chunks.

**Recording results:** on each phone, Debug → **Export to text** shares a report containing:
- the counters
- a CSV of per-packet `side, packetId, type, priority, src, dst, hops, route, oneWayMs, rttMs`
- the timestamped log

Report the **RTT** column; one-way latency depends on the phones' clocks agreeing.

What to write down per run:

| Run | Phones / Android versions | Topology | Scenario | Hops | ACK RTT | Notes (link drops, Nearby errors) |
|---|---|---|---|---|---|---|

Troubleshooting:
- `STATUS_RADIO_ERROR` / `MISSING_PERMISSION`: check that Bluetooth is on, the permissions are granted and Location is on (Android ≤ 12).
- Phones never connect: make sure both run the same APK (same `SERVICE_ID`) and that the topology lock isn't excluding them.
- Gateway phone drops its Wi-Fi: use mobile data on D.

---

## 7. Decisions made without asking (and why)

- **Nearby Connections, P2P_CLUSTER.** It is still the most practical radio API that needs no root and no pairing. Wi-Fi Aware is supported on too few phones. Wi-Fi Direct needs user dialogs and gives one group per phone. Raw BLE GATT is very slow and fiddly. P2P_CLUSTER is the strategy that allows M-to-N links.
- **Pull-based SUMMARY on connect, push for live traffic.** Pull avoids re-sending everything on every reconnect; push keeps live traffic low-latency.
- **Unicast and gateway shortcuts on top of flooding.** They cut traffic without giving up flooding's robustness; any other case falls back to flooding.
- **Beacons are not persisted.** Gateway reachability is fresh state; a stored beacon from yesterday would mislead.
- **At-most-once per gateway, not globally.** If a request floods to two gateways before an ACK spreads, both may execute it. The requester keeps the first response (`firstGatewayResponseWins`). Exactly-once across gateways would need coordination the mesh can't offer.
- **NodeID = chosen name + 4 random hex characters** (e.g. `A-3f9c`), so routes are readable in the UI and logs. It can only be renamed while the mesh is stopped.
- **Compact binary wire format** instead of JSON. A SUMMARY of 1000 IDs and a full 16 KB chunk both fit inside Nearby's 32 KB BYTES limit.
- **Topology lock** (a per-phone peer allowlist) is a testing aid. It does not fake anything: links really are refused at the Nearby layer.
- **Pinned library versions** to what builds against the installed SDK 35 toolchain. Lint lists newer versions; upgrading needs compileSdk 36.
- **The project lives in `D:\New folder\MeshMind\`.** `New folder` is the open VS Code workspace, so it was not renamed from under the editor. Move or rename it freely.

## 8. Future hooks (interfaces only, by design)
- `PriorityClassifier`: swap `RuleBasedPriorityClassifier` for an on-device model.
- `NextHopAdvisor`: mobility-aware or ML next-hop selection; it receives the candidate peers the flooding router would use. SAGE's shadow advisor plugs in here (§9). It is only consulted for TEXT/CHUNK/Internet traffic, never CRITICAL, and a throwing, empty or slow advisor falls back to flooding.
- `LinkExtension`: link-local side channel (one `Frame.Extension` on handshake, refreshed every 5 min, ranked below CRITICAL/beacons/HIGH). SAGE uses it to swap contact summaries.
- `InternetExecutor`: other gateway back-ends.
- `MeshTransport`: other radios (e.g. Wi-Fi Aware) can be added without touching routing.

## 9. October 2026: fast detection, then SAGE on the phone (shadow mode)

**Why A took ~71 s to find the gateway (session `physical_20261006_01`).** The links formed in 15–25 s. Then:
- C restored ~3,400 old ACKs from earlier runs. ACKs were never acknowledged, so C re-flooded them for their whole 30 min lifetime after every restart.
- The relay's link to A was busy forwarding those ACKs (HIGH), and A rejected them as duplicates.
- Gateway beacons (NORMAL) queued behind them. Beacons #2–#4 never got through; #5 arrived at 71 s.

**Fixes (core router, all covered by tests):**
- **Send order:** CRITICAL, then gateway beacons, then HIGH/NORMAL/LOW. ACKs older than 60 s go after fresh NORMAL traffic.
- **On handshake:** a node immediately hands the newcomer the gateway beacons and SOS it holds, instead of waiting for the 10 s tick.
- **ACK delivery is recorded too:** when an ACK reaches its destination, its own ID joins the acked set. SUMMARYs then stop relays carrying it, and a relay drops an ACK already known as delivered.
- **Warm-restart OOM:** finished chunks/ACKs/responses now load at startup as IDs only (duplicate detection still works), and finished bulk rows are pruned after 6 h. `RoomPacketStore.loadStartup`; no schema change.
- **Simulated result** (1,500 stale ACKs, phone-like 15 ms/frame): gateway learned 0.25 s after the link (was 25.9 s); a fresh text behind the backlog takes 50 ms (was 6.25 s); SOS unchanged at 50 ms.

**SAGE v1 on the phone, shadow mode (`core/.../sage`):**
- **`ContactHistory`:** one phone's own row of the research `NodeState`. Covers EWMAs, PRoPHET fast/slow (including the `@gateway` column), hour-of-day and day counts. It is persisted in `files/sage_history_<node>.bin`, so phones remember yesterday.
- **Contacts:** the app records a contact bin every 20 s: every MeshMind phone linked *or* only discovered.
- **`SageFeatures` + `SageNet`:** the 59 research features and a pure-Kotlin forward pass, with weights in `core/src/main/resources/sage/` (no native runtime, ~0.1 ms per row on a laptop JVM).
- **Parity:** checked against research golden vectors (`research/sage/scripts/export_kotlin.py`). Features match within 4.8e-7 and outputs within 2.4e-7.
- **`SageShadow`:** the router's advisor plus link extension. Phones swap bounded contact summaries (≤96 columns, values clipped, sender ID checked against the link). SAGE scores each ordinary (packet, neighbour) opportunity and logs what eco mode would skip. It **always returns the router's own choice**.
- **Controls:** Debug screen → "SAGE shadow mode" (toggle, stats). Export appends `# sage_contacts` and `# sage_decisions` CSV, readable by `research/sage` via `traces.load_meshmind_field`.
- **Benchmarks:** `benchmarks/benchmark_all.py --sage on|off` runs the A/B on phones.

**On the 3 phones, 9 October 2026** (sessions `physical_20261009_02`, `physical_20261009_03` and `lightshow_20261009_01` under `benchmarks/sessions/`). These are small samples, not the full matrix:
- **Discovery:** A learned the 2-hop gateway 7.2–31.4 s after its start command in 11 runs (smoke + mini, two builds). It had been ~70 s.
- **Smoke + mini on the final router:** 54/54 passed (text, Internet via C, store-and-forward, SOS idle/128 KiB/512 KiB/1 MiB load, discovery, relay restart).
- **SOS, SAGE shadow off vs on:** 10/10 and 10/10 delivered. p50 was 208 ms off and 173 ms on, p95 1065 ms and 502 ms. Shadow mode did not slow SOS at this sample size.
- **Light Show:** 14/15 acceptance checks passed (`benchmarks/light_show_physical.py`):
  - **Join and sync:** C saw the offer 2 hops away in 0.4 s, joined through the UI and synchronized in 1 s. Its clock-offset error lay within the ADB reference bound of −66…+82 ms.
  - **Relay and screens:** B relayed without playing. A's and C's screenshots matched the Rainbow timeline.
  - **Join, leave and stop:** a late joiner entered mid-show, a local leave stuck, and the host's Stop ended C's show in 3.7 s.
  - **Isolation:** an isolated C ignored the unreachable stop and ended on schedule.
  - **The failure** was the test's timing (it sampled before `startAt`); it is fixed in the script.
  - **Not run:** the laptop-console scenario was not run on phones.

**Fixed after the phone runs:**
- **Half-open links (router):**
  - *Problem:* after a relay restart, A's side of the new Nearby connection came up ~0.7 s before B's. A's HELLO failed four sends and was dropped, so B discarded everything from A ("data before HELLO") and a queued text never left A.
  - *Fix:* HELLO is now never dropped while the link lives. A watchdog re-sends it every `handshakeRetryMs` (3 s) until the peer's SUMMARY confirms the handshake.
  - *Evidence:* `HandshakeRecoveryTest` fails on the old router. On phones the watchdog fired 9 times in the next session, and no frame was dropped.
- **SAGE scoring off the routing path:**
  - *Problem:* on the phones (debug build) one score took 1–3.4 ms per neighbour, measured inside the router lock. Three slow calls tripped the 5 ms advisor guard after about one decision per session, so shadow mode logged almost nothing.
  - *Fix:* `SageShadow` now hands scoring to a single low-priority worker (`scoreOn`, a bounded queue of 256 that drops the oldest; the Debug screen shows `dropped`), warms the model up at start, and returns the router's choice at once.
  - *Status:* JVM-tested only (`SageShadowTest.offThreadScoringReturnsImmediatelyAndScoresLater`). Phone scoring rate, latency and battery still need a run.

**Still to measure on phones:** the full statistical matrix, SAGE with the async scorer (scored count, score p50/p95, dropped, battery over 2 h), and the Light Show laptop-console scenario.

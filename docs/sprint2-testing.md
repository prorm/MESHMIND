# Sprint 2 testing: Ask, fair share, trust, live info

This covers testing the new features: signed packets and verified identity, the need-aware gateway queue, Ask/Read through the mesh, and the live-info board.

## What is already verified on the laptop

| Check | Result |
|---|---|
| `:core:test`: 57 existing tests plus 23 new ones (security, scheduler, ask, bulletin, Python↔Kotlin signature interop) | 80/80 pass |
| Backend `unittest` (canonical bytes, signatures, enrollment, ask cache, reader SSRF guard, notices) | 9/9 pass |
| Live backend ↔ Kotlin `BackendClient` (enroll → certificate verifies, signed answer, real Open-Meteo board) | pass |
| Reader against a real page (Wikipedia → 5.4 KB of signed text) | pass |
| `:app:assembleDebug` and `:app:assembleRelease` | build |

What is **not** verified yet:

- The new screens have not been seen on a real device.
- Signing cost on real phones has not been measured.
- Real Claude answers have not been tried, because each one costs money and I did not run any without your go-ahead.

## What you need

- **Phones.**
  - At least 1 phone, for the simulator walkthrough and the single-phone checks (A1–A3).
  - 2 phones for a gateway plus a requester (B1–B6).
  - 3 phones to show relay caching and multi-hop (C1–C3).
  - All phones must run the **new** APK. Protocol v2 refuses v1 phones, and the database is reset on upgrade, so packets stored on the phone are cleared.
- **A laptop** on the same Wi-Fi as the gateway phone, running the backend (`backend/README.md`). Note its IP, for example `http://192.168.1.20:8787`.
- **For real answers:** your Anthropic API key on the laptop (`$env:ANTHROPIC_API_KEY`). Without it, use `--provider demo` and you get canned answers labelled "demo".
- **The debug APK** (`app/build/outputs/apk/debug/app-debug.apk`). Only debug builds may talk to the laptop over plain http.

Start the backend:

```powershell
cd backend
.venv\Scripts\python -m meshmind_backend.server --demo-otp --provider claude   # or --provider demo
```

## A. One phone (do these first)

**A1. Simulator: the whole story without radios.** Open Simulation, then the "Ask, fair share & live info" section.
1. Tap **A: metro?**. A gets a ✓ signed answer through gateway E, served from `upstream`, need about 60.
2. Tap **C: same (cache)**. C's answer comes back with `relay-cache` or `gateway-cache`, and the simulated backend is not called a second time.
3. Tap **Crowd rush**. In the queue panel, A's "friend fainted, first aid" question goes first. B's fake "URGENT" questions show "urgent allowance used" after two. The "What people need" bars fill in.
4. Tap **Live info**, then **D: organizer notice**. Both boards appear at A, marked ✓ Official and ✓ organizer.

**A2. Verify the phone.**
1. Go to Identity. Enter the backend URL and your phone number, then tap Send code. The demo code appears in the app.
2. Enter the code and tap Verify. The badge changes to ✓ Verified and the fingerprint is shown.
3. Do it again with role Organizer and code `demo-organizer`. The badge shows ✓ Organizer.

**A3. Gateway on one phone.**
1. Turn on Wi-Fi. In Share my Internet, turn on consent and fill in the backend URL.
2. Open Ask and ask "Is the metro running?". The phone answers its own question through the backend.
3. Check the card: signed ✓, `via <this phone>`, and "Why this priority?" lists the reasons.
4. Tap a source chip. The Read card shows the page as plain text.

## B. Two phones: gateway G (Wi-Fi, verified) and requester R (airplane mode with Bluetooth on, verified)

| # | Steps | Expected | Record |
|---|---|---|---|
| B1 | Start the mesh on both. Open Ask on R. | The banner says "Internet reachable via G · 1 hop". | Time until the banner appears |
| B2 | R asks "nearest hospital". | A ✓ signed answer, Health category, wait time shown | Round trip in seconds (shown on the card) |
| B3 | R asks the same question again in different words. | "⚡ Instant · saved on this phone"; nothing is sent over the radio | — |
| B4 | On G, tap "Fetch & broadcast live info now". | R shows the Live info card (weather plus notices); the card also appears on Home | Delay |
| B5 | On the laptop, post a notice (curl in the backend README), then repeat B4. | The new notice appears on R and replaces the old board | — |
| B6 | Debug on R, after B2. | "refused (trust)" is 0 and "answer cache" is at least 1 | Export the Debug report |

## C. Three phones in a line: R1 – M – G (lock the topology with the peer filter, as in Sprint 1)

| # | Steps | Expected |
|---|---|---|
| C1 | R1 asks a question. | The answer route shows R1 → M → G → 🌐 and back. |
| C2 | After C1, M asks the same question in its own words. | "⚡ Instant · saved on this phone": M cached the signed answer while relaying it. With a 4th phone next to M, that phone's question is answered by M from its cache (`relay-cache`) and G is not asked. |
| C3 | Crowd test: R1 and M each fire 5 or more mixed questions quickly (leisure and health). | G's queue screen shows health questions ahead of leisure; nobody waits more than 3 minutes; the "What people need" bars fill in. |

## D. Regression checks from Sprint 1 (signing must not slow them down)

- **SOS A/B with SAGE shadow on.** The Sprint 1 p50 was 172–208 ms. Report the new p50/p95. The new cost is about 1–2 ms per packet for signing and verifying.
- **Store-and-forward and the 1 MB transfer with SOS preemption (R6).** Compare the throughput (was about 105 KB/s) and the SOS ACK RTT.
- **Light Show:** the `--only console` run that was still pending, plus one 2-phone show (light controls are now signed too).

## What to send back

- The Debug export from each phone.
- The backend console log and `backend/data/audit.jsonl`.
- Screenshots of Ask, the queue panel and Identity, so we can do a design pass on the real screens.
- Anything that looked confusing in the UI.

## Known limitations in this build

- Verification needs Internet once per phone. Enrolling *through* the mesh is not built.
- Questions are visible to the phones that relay them, because they are signed but not encrypted. Don't put personal details in questions.
- Any phone can claim to be a gateway. A fake gateway cannot forge answers (they are signed), but it can silently drop questions. Verified gateways are preferred when routing.
- A neighbour's SUMMARY can still mark ordinary (non-SOS, unicast) packets as delivered. SOS and broadcasts are protected.
- Real SMS OTP is not wired; the backend is in demo mode.
- The answer cache, notices and node-key pins are kept in memory only; they reset when the app or backend restarts.

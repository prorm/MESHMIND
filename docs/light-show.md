# Light Show

MeshMind distributes timed screen-color shows over the existing offline mesh. One phone hosts; each participant explicitly joins. Relay phones forward controls without displaying the show. Simple presets remain **Rainbow**, **Ocean pulse**, and **Sunset**, with 30/60/120-second durations. The new **Conductor** adds eight spatial effects, beat-aligned scene changes, group/position assignments, up to twelve timed cues, blackout/restore, and a five-act finale. Conduct from the host phone or from its paired USB laptop browser. It does not control the flashlight or automatically sense phone positions.

## Conduct from a laptop

1. Install the updated APK on **every phone**. This release uses light-control protocol v2; the previous Light Show APK is not compatible. Start the mesh on the phone that will act as the conductor bridge.
2. On that phone open **Light Show → Enable USB browser console**. The screen displays a pairing URL and the port-forward command. The listener is disabled by default and is bound only to the phone's loopback interface.
3. Connect the phone by USB and enable USB debugging. Accept Android's USB-debugging authorization for your laptop. Run `adb forward tcp:8765 tcp:8765` on the laptop; when multiple devices are attached, use `adb -s <host-device-serial> forward tcp:8765 tcp:8765`.
4. Open the full pairing URL displayed on the phone in the laptop browser. The token is in the URL fragment, is removed from the address bar once loaded, and is held only in memory. Reloading requires reopening that pairing URL. No Internet, cloud account, or laptop Nearby Connections implementation is required.
5. Choose a scene and show length, then **Start show**. Other phones open Light Show and explicitly join. Start has the same ten-second lead as the simple presets. The laptop console remains functional if the bridge phone's Light Show screen closes or its app backgrounds, while the mesh service is running.
6. **Disable laptop control** on the host phone to close the listener, revoke that token and end its hosted show. Stopping the mesh also closes the console. Optionally run `adb forward --remove tcp:8765` on the laptop to remove its USB forwarding rule.

### The performance desk

| Control | Result |
|---|---|
| Traveling wave | A color wave moves from one assigned side of the room to the other, with group phase offsets |
| Center ripple | Expanding color rings use each phone's assigned distance from the center |
| Spiral vortex | Angle and radius make phones participate in a rotating spiral |
| Constellation | Stable per-node seeds give each phone its own smooth twinkle phase |
| Checker dance | Alternating coordinate cells trade color across four musical beats |
| Rainbow sweep | A spectrum sweeps through assigned positions and groups |
| Color wash / breathing glow | Solid group washes or smooth shared breathing transitions |
| Light / shadow colors | Choose the two colors blended by most spatial effects |
| Tempo / Tap | 30-180 BPM; tap several beats to set tempo, then Fire to apply it |
| Travel spread / reverse motion | Set wave propagation spread or reverse the scene's phase direction |
| Master brightness | Dim the rendered colors from 0-100%; device window brightness remains its own setting |
| Group selection | Light any combination of four groups; untargeted groups fade to black |
| Fire on the next beat | Queue a scene at the first host beat at least three seconds ahead |
| Blackout / Restore | Schedule a blackout with a 1.5-second minimum distribution lead, then restore the last lit program |
| Five-act finale | Wave → spiral → constellation → ripple → rainbow, spread over the remaining show duration |
| Arrange virtual grid | Assign sorted joined phones to a grid and distribute them across four groups |
| Drag a phone / assign position | Move its virtual coordinate, or use the group and position inspector |
| Cue plan | Add up to twelve scene cues relative to the show start, arm the sequence, or save/load a validated JSON plan |
| Keyboard | Space: Fire; B: Blackout; R: Restore; F: Finale; G: Grid. Shortcuts stay inactive while typing or focusing controls |

The canvas renders the current host program and assigned positions. When no phones are joined it clearly labels the 64-phone **simulated preview**. Actual joined phones come from readiness reports. The roster and cue revision report last-known synchronization and program receipt, not proof of screen visibility or exact physical alignment. A disconnected browser keeps a labelled last snapshot and disables commands.

Positions are **manual or virtual**, not measured locations. Place phones according to the assigned grid to make a physical wave or ripple correspond to its preview. Participants can choose a group and position on the phone; a conductor assignment takes precedence and is re-sent on readiness reports until acknowledged. The roster has a 64-participant software bound, which is not a measured radio-capacity claim.

Live controls are scheduled events, not zero-latency commands. A pending normal cue blocks another normal cue until it takes effect. Blackout can supersede a pending cue. A revision number ensures an older delayed cue cannot take over after a newer one. Updates fade over one second; sequence transitions also fade over one second. Late joiners receive the active and pending program. An isolated phone can play buffered cues but cannot receive a new scene, position assignment or remote stop until a route returns.

## Run a show

1. Install the new APK on every phone. Start the mesh on Home and wait for links to form.
2. Open **Light Show** on the host. Choose a preset, duration and color cycle, then tap **Host · start in 10 seconds**.
3. Open Light Show on other phones and tap **Join [host]**. Each phone exchanges timing probes before it enables full-screen playback.
4. Tap **Open full screen**. The app keeps the screen awake; the full-screen window uses 80% brightness and restores its prior setting when closed. **Controls** returns to the settings without leaving the show.
5. A host's **Stop show** broadcasts a stop. A participant's **Leave show** affects only that phone. Back navigation, switching away from Light Show, app backgrounding, or stopping the mesh ends screen participation. A host with laptop control enabled keeps conducting in the background; otherwise when the host leaves, it sends a stop. Participants re-entering require joining again.

Joining after playback starts uses the current host phase rather than restarting the sequence. A phone with a buffered show and a timing sample can continue through a link loss until the scheduled end. A remote stop requires connectivity; if it cannot arrive, the isolated participant ends at the original duration. Shows never resume after process restart. A phone that cannot synchronize within 15 seconds returns to the join controls with a retry message.

## Timing and transport

- `LightShowController` lives in the pure Kotlin core. Android supplies `SystemClock.elapsedRealtime()` for both controller time and router receive timestamps; each phone's unrelated boot-time origin is compensated explicitly. Wall-clock time is not used for playback.
- Four timestamps estimate the host offset: `offset = ((t2 - t1) + (t3 - t4)) / 2`; `roundTrip = (t4 - t1) - (t3 - t2)`. The host's processing interval is subtracted. Probes with invalid ordering or round trips above 3 seconds are rejected. The controller picks the lowest round trip from up to ten samples in the last 20 seconds.
- Five initial probes are spaced approximately 500 ms apart; subsequent probes occur every 5 seconds. These are scheduled at the application layer, so congestion can delay them. Asymmetric routes can bias the estimate: the displayed sync round trip is a diagnostic, not a measured screen-alignment guarantee.
- The host starts 10 seconds ahead, advertises its immutable show descriptor every 3 seconds, and receives periodic readiness reports. Its count shows other phones that reported synchronization within the last 15 seconds; it is not a census of the mesh or proof that their screens are currently visible.
- `LIGHT_SHOW` is appended to `PacketType` to preserve existing ordinals. Controls carry a versioned, bounded binary payload (at most 512 bytes): OFFER, STOP, DISCOVER, PING, PONG, READY. All participating phones must run this APK; older APKs do not understand this packet type.
- Controls reuse routing, deduplication, TTL (8 hops), the existing per-link queues, and short in-memory store-and-forward. Each relay applies a local five-second lifetime so different wall clocks cannot invalidate clock probes. Show data is never written to Room, and controls generate no end-to-end ACKs or message inbox entries. Protocol v2 adds PROGRAM (revision, host effective time, up to twelve compact cues), SEAT, DEPART and richer READY reports; even the maximum score stays below 512 bytes.
- OFFER/probes/readiness/discovery/programs/positions are NORMAL priority; STOP and BLACKOUT are HIGH; SOS remains CRITICAL. The router hands delivered controls to a bounded 64-event queue without waiting for the show controller. Saturation drops controls and logs an error; repeat advertisements/probes provide retries. There is no per-frame network stream or automatic discovery flood while the feature is idle. Phone position edits throttle readiness updates to at most twice per second; normal five-second timing probes also report the latest position.
- Session IDs and matched probe tokens reject unrelated replies. Offers must match the packet source, repeated descriptors cannot mutate an existing timeline, and source-scoped stop tombstones suppress delayed offers for ten minutes. This is consistency checking, not an authenticated organizer or event-access system.

## Validation and limits

`LightShowTest` exercises all control codecs and malformed input, clock offset/processing compensation, deterministic colors, a real-router A-B-C simulation with different boot and wall clocks, opt-in participation, late joins, local leave, stop-before-offer and delayed advertisements, non-host stops, disconnected completion, synchronization failure, and SOS preemption during a 1 MiB transfer with light controls active.

These are JVM simulations; they do not establish radio performance or actual screen synchronization accuracy. Existing core regression tests cover the underlying router. The Android build checks Compose integration. Use the device procedure below to establish real display behavior before advertising a timing tolerance or supported crowd size.

Validation on 7 October 2026: all **42 core tests** passed and `:app:assembleDebug` succeeded. The conductor extension adds maximum-score codec checks, actual spatial differentiation, cue crossfades, program recovery for late joiners, blackout supersession/restore, source checks, group targeting, departure handling and loopback HTTP authorization/origin/body bounds. `tools/check_light_console.cjs` passed **10 headless browser checks** using an explicitly simulated bridge, covering command dispatch, drag assignment, plan validation, keyboard controls, tap tempo and desktop/narrow-screen layout. It does not substitute for Android USB/mesh/display tests; no devices were connected for those checks.

**Physical run, 9 October 2026** (`benchmarks/light_show_physical.py`, session `lightshow_20261009_01`; A = OnePlus CPH2585 host over USB, B = Realme RMX5108 relay, C = Galaxy S24 Ultra participant; A↔B↔C software topology lock). 14/15 checks passed.

**Joining and sync:**
- C received the offer over two hops 0.4 s after hosting.
- C joined through the real UI and synchronized 1 s later.
- The host listed C as ready.
- B relayed without starting playback.

**Clock accuracy:**
- C's estimated host offset was within the measurement error of an ADB `/proc/uptime` reference: error bound −66…+82 ms, best probe round trip 86 ms.
- This bounds timeline error only. It is **not** optical display skew.

**Screens:**
- Full-screen screenshots on A and C matched the expected Rainbow color within the capture window.
- C showed at least three distinct colors.

**Late join, leave and stop:**
- B joined about 26 s into the show and entered the current phase.
- After a local leave, advertisements did not rejoin it.
- The host's full-screen **Stop show** ended C's show 3.7 s later (host-observed, including ADB polling).

**Isolation:**
- With B's mesh stopped, C kept the buffered show after the host's stop, which could not reach it.
- C ended with "Show finished" at the scheduled time.
- The one failed check sampled C's color 2.2 s *before* `startAt`, when the idle color is correct; the script now waits for playback.

**Still needed:**
- The laptop-console scenario (`--only console`), high-speed-camera skew and crowd scale were not measured.

The browser bridge serves bundled HTML/CSS/JS through a bounded, four-client loopback HTTP server. Each API request needs the per-enable 192-bit bearer token; API state contains no token. Requests reject foreign Origins/Hosts, duplicate headers, chunked encoding and bodies above 16 KB. The server has no CORS allowance, sends a same-origin CSP and no-store responses, and closes with the mesh lifecycle. USB debugging is required for forwarding. This is a local development/event-controller path, not an Internet-facing management endpoint or authenticated mesh-organizer protocol.

### Physical-phone acceptance procedure

1. Use three mixed Android phones on the same APK. Disable Internet on the participants. Confirm A-B-C links; use the existing topology lock to exercise a two-hop route and label it as software-enforced topology.
2. Host Rainbow on A, join C, and leave B out of the show. Confirm B relays but its display does not change. Check the host's ready count and C's timing diagnostic.
3. Record A and C together with a high-frame-rate camera. Compare color transitions across several cycles and report the observed skew with the video frame resolution. Do not substitute packet RTT for display skew.
4. Join another phone halfway through. Confirm it enters the current phase. Leave locally and confirm advertisements do not rejoin it.
5. Stop on the host while links are live. Confirm participating screens end. Restart a new show, then isolate C; confirm it continues and ends at its scheduled duration. Document that an isolated phone cannot receive an immediate remote stop.
6. Start a 1 MiB LOW transfer and send a MEDICAL SOS while the show is active. Verify the SOS arrives before the final chunk and measure RTT. Check backgrounding/back navigation, screen-awake behavior, full-screen brightness restoration, and a mesh stop.
7. Pair the laptop over USB, start a two-minute show, and arrange the joined phones in a physical line or grid matching their assigned coordinates. Fire WAVE, RIPPLE and SPIRAL; record transitions and compare each display's phase with its assigned position. Test four-group targeting, blackout/restore during a queued cue, and joining halfway through a multi-cue sequence.
8. Background the conductor phone with laptop control enabled and verify the browser still schedules scenes. Disable laptop control and confirm its old token/listener no longer works. Disconnect USB and confirm commands disable without halting already buffered phone playback. Reconnect, re-open the pairing URL and confirm state recovery.

## Brochure

`docs/pitch/MeshMind_Pitch_v5.html` extends the v4 brochure with the Conductor controls and accurate validation/operating limits on the Light Show page and claims table. Earlier v3/v4 HTML/PDF editions remain available. `render_brochure.cjs` produces the current A4 PDF using headless Chromium and checks page geometry before exporting. See `docs/pitch/README.md` for the reproducible command.

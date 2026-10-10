# Physical benchmark harness

Run inside the existing MeshMind project. No Git initialization or production routing changes.

The harness identifies devices dynamically, deduplicates ADB aliases by hardware serial, checks A=USB and B/C=wireless, verifies exact peer filters and live links, hashes installed APKs, and refuses to start a full matrix before staged validation. It does not expose Wi-Fi, Bluetooth, airplane-mode, tethering, or network-route changes. Mesh outages use the existing runtime/service stop/start APIs only.

The debug-only receiver is in `app/src/debug`; release builds do not include it. Its exported entry point requires Android's DUMP permission, which the ADB shell has but ordinary apps do not. It calls existing send/lifecycle APIs and writes observations into the app's private files. There are no scheduler/routing changes. SHA-256 checks use deterministic bulk fixture bytes.

Build with JDK 17 and the installed Android SDK:

```powershell
$env:JAVA_HOME='D:\sonictools\jdk\jdk-17.0.20.1+1'
.\gradlew.bat '-Porg.gradle.java.installations.paths=D:\sonictools\jdk\jdk-17.0.20.1+1' '-Pkotlin.compiler.execution.strategy=in-process' :core:test :app:assembleDebug --console=plain
```

Use the Windows CPython 3.12 desktop runtime below; the shell's default Python may be a different installation. Plot dependencies are available in `D:\sonictools\venv\Lib\site-packages`. Set `MESHMIND_ADB` to override the ADB executable.

```powershell
$benchmarkPython='C:\Users\prana\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$env:MPLCONFIGDIR=Join-Path (Get-Location) 'benchmarks\.matplotlib'
& $benchmarkPython benchmarks/benchmark_all.py --stage preflight --install --session SESSION_ID
& $benchmarkPython benchmarks/benchmark_all.py --stage smoke --session SESSION_ID
& $benchmarkPython benchmarks/benchmark_all.py --stage mini --session SESSION_ID
& $benchmarkPython benchmarks/benchmark_all.py --stage full --session SESSION_ID
& $benchmarkPython benchmarks/finalize.py --session SESSION_ID
& $benchmarkPython -m unittest discover -s benchmarks -p test_metrics.py
```

Preflight is required. Failures stop smoke/mini collection for inspection. A resumed stage preserves recorded attempts and does not replace them. `--suites` selects scenarios. Full results include every attempt; latency distributions use only successful, verified samples. Warmups and staged validation are retained in the CSV but excluded from full distributions.

Session `physical_20261006_01` used five cold-process discovery runs after a preserved warm-restart heap crash, and relay recovery was capped at ten attempts by the user. `--restart-mode cold` restarts app processes without clearing data or changing radios. To resume relay collection with that cap, use `--stage full --suites relay_recovery --restart-mode cold --count 10 --session physical_20261006_01`. Original plan counts and session adjustments are retained separately in metadata and REPORT.md.

Session outputs live under `benchmarks/sessions/SESSION_ID`; `attempts.jsonl` is the source of truth. Every command has a unique raw JSON file. The parsed per-run record points to its evidence. Logcat is captured from all devices continuously for MeshMind and fatal AndroidRuntime errors. No global logcat buffers are cleared.

For longer sessions, `capture_logs.py --session SESSION_ID` adds read-only logcat streams that restart after a disconnected or silent stream. It never restarts the ADB server. Create `sessions/SESSION_ID/stop_log_capture` to close that auxiliary capture cleanly. Its connection events are retained in raw JSONL.

Nearby selects the bearer. B/C keep Wi-Fi for wireless ADB; USB-controlled A can have Wi-Fi off. A remote gateway request alone does not establish that A is OS-offline, so full runs record its Wi-Fi and airplane-mode state and the app's validated-Internet observation. Percentiles use Python statistics.quantiles(method="inclusive"), equivalent to Hyndman–Fan type 7; p99 below 100 samples is unstable. See REPORT.md for timing definitions, conditions and limitations.

## SAGE shadow-mode A/B

`--sage on|off` switches SAGE shadow scoring on all three phones before a stage (default `keep` leaves the app setting alone). Every attempt records `sage_mode`, and REPORT.md gains a "SAGE shadow mode A/B" table: shadow mode must leave success and latency unchanged. After an `--sage on` stage the phones' scoring statistics (decisions scored, eco would-skip, peer summaries, on-phone score latency p50/p95) are saved to `parsed/sage_stats_*.json`. Typical run:

```powershell
& $benchmarkPython benchmarks/benchmark_all.py --stage full --suites sos_idle sos_high text --sage off --session SESSION_ID
& $benchmarkPython benchmarks/benchmark_all.py --stage full --suites sos_idle sos_high text --sage on --count 60 --session SESSION_ID
```

A resumed stage counts previous attempts, so give the `on` run a higher `--count` (or a separate session) to collect a full second set.

## Light Show on phones

`light_show_physical.py --session SESSION_ID [--only ui console isolated]` runs the Light Show acceptance procedure (docs/light-show.md). A hosts, C joins, and B relays without playing.
- **What it uses:** the real UI for navigation, join, full screen and stop. The debug receiver (`light_host`, `light_join`, `light_leave`, `light_discover`, `light_console`) handles the rest, and the laptop console API is reached through `adb forward tcp:8765` on A.
- **What it measures:** offer, sync and cue delivery times; clock-offset error against an ADB `/proc/uptime` reference; and screenshot colors against the expected timeline.
- **Output:** `sessions/SESSION_ID/light_results.json` and `reports/LIGHT_SHOW.md`.
- **Not covered:** it does not measure optical skew (that needs a camera).

## Setup notes (9 October 2026)

- **Signature mismatch:** phones carrying an APK signed with another debug key reject `install -r`. Back up `run-as dev.meshmind.app tar -cf - shared_prefs databases`, uninstall, install, then restore with `run-as … tar -xf`. In Git Bash set `MSYS_NO_PATHCONV=1`, or device paths get rewritten.
- **Permissions:** OnePlus/Realme (ColorOS) refuse `pm grant` from the shell. Accept the app's permission prompt on the phone; Samsung accepts shell grants.
- **Gateway on A:** A must not be a gateway itself. If A has Internet and `gw.consent=true` it serves `internet` requests locally and the attempt fails the 2-hop check. Set `gw.consent` to false on A for the run, and restore it afterwards.

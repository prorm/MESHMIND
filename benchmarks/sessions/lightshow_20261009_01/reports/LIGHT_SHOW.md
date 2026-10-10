# Light Show — physical phones

Session `lightshow_20261009_01`, 2026-10-09T20:26:59.735035+05:30. A hosts, C joins, B relays (software topology lock A↔B↔C).

**14/15 checks passed.**

| Check | Result | Detail |
|---|---|---|
| phones on Light Show screen (A, C) | PASS | `{}` |
| host schedules show | PASS | `{"showId": "34b78edf-b845-45f4-accd-78b7af45331d"}` |
| C sees the offer over 2 hops | PASS | `{"host_observed_ms": 408}` |
| C joins via UI and synchronizes | PASS | `{"via": "UI", "sync_ms": 1000, "clock": {"measuredAt": 334977903, "offsetMs": -154046736, "roundTripMs": 280}}` |
| host counts C as ready | PASS | `{"readyCount": 1, "participants": [{"lastSeen": 180932075, "nodeId": "C-507b", "revision": 0, "roundTripMs": 131, "seat": {"group": 0, "x": 500, "y": 500}}]}` |
| B relays without playing | PASS | `{"b_show": null}` |
| C clock offset matches reference (|error| bound < 50 ms) | PASS | `{"app_offset_ms": -154046816, "reference_interval_ms": [-154046898.2, -154046749.7], "error_interval_ms": [-66.3, 82.2], "sync_rtt_ms": 86, "adb_min_rtt_ms": [64.5, 70.2]}` |
| full screen opened on A and C | PASS | `{}` |
| screens render the host timeline (A and C) | PASS | `{}` |
| late joiner B enters current phase | PASS | `{"position_ms": 26346}` |
| B stays out after leaving (adverts do not rejoin) | PASS | `{}` |
| host Stop ends C's show | PASS | `{"via": "UI", "stop_ms": 3730, "note": "The host stopped this show."}` |
| C synchronized before isolation | PASS | `{}` |
| isolated C keeps playing after unreachable stop | **FAIL** | `{"position_ms": -2216, "color": [11, 18, 20]}` |
| isolated C ends at the scheduled duration | PASS | `{"note": "Show finished. Choose another show or host your own."}` |

Clock error uses an ADB `/proc/uptime` reference bounded by command round trips; it bounds timeline error, not optical skew.
Screen checks read the centre-upper pixel of `screencap` and compare it with the expected Rainbow color across the capture window.
Optical display skew still needs a high-frame-rate camera (procedure step 3).

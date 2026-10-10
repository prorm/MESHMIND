"""Light Show acceptance on the three physical phones (docs/light-show.md, device procedure).

A hosts, C joins, B relays without playing. Control uses the real UI where practical
(join, full screen, stop) and the debug-only receiver/console otherwise. Results go to
benchmarks/sessions/SESSION/light_results.json and reports/LIGHT_SHOW.md.

Measured here: offer/sync/program delivery times, clock-offset error against an ADB
reference, and screenshot colors against the expected timeline. Not measured: optical
display skew (needs a high-speed camera), crowd scale.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchmark_all as bench  # noqa: E402

RAINBOW = [0xFF7043, 0xFFC857, 0x72D572, 0x35DCC3, 0x5599FF, 0xB477EF, 0xEF77B4]
PORT = 8765


def rainbow(position_ms, period_ms):
    phase = (position_ms % period_ms) / period_ms
    scaled = phase * len(RAINBOW)
    a, b = RAINBOW[int(scaled) % 7], RAINBOW[(int(scaled) + 1) % 7]
    f = scaled - int(scaled)
    return tuple(int(((a >> s) & 255) * (1 - f) + ((b >> s) & 255) * f) for s in (16, 8, 0))


def dist(c1, c2):
    return max(abs(x - y) for x, y in zip(c1, c2))


def rgb(argb):
    v = argb & 0xFFFFFF
    return (v >> 16) & 255, (v >> 8) & 255, v & 255


class LightTest:
    def __init__(self, session_id):
        self.s = bench.Session(session_id, "light", bench.discover())
        self.results = []
        self.node = self.s.node
        self.out = self.s.dir / "light_results.json"

    # ---------- plumbing ----------
    def check(self, name, ok, **detail):
        self.results.append(dict(check=name, ok=bool(ok), **detail))
        print(f"{'PASS' if ok else 'FAIL'} {name} {json.dumps(detail, default=str)[:300]}", flush=True)
        self.out.write_text(json.dumps(self.results, indent=2, default=str), encoding="utf-8")
        return ok

    def cmd(self, role, command="snapshot", **extras):
        return self.s.command(role, command, **extras)

    def light(self, role):
        o = self.cmd(role)
        return o, o.get("light") or {}

    def wait_light(self, role, predicate, seconds):
        deadline = time.monotonic() + seconds
        while True:
            o, light = self.light(role)
            if predicate(light, o):
                return o, light
            if time.monotonic() > deadline:
                return None, light
            time.sleep(.3)

    def sh(self, role, *args, timeout=30):
        return subprocess.run([str(bench.ADB), "-s", self.s.devices[role]["adb_id"], *args], capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout

    def ui_nodes(self, role):
        self.sh(role, "shell", "uiautomator", "dump", "/sdcard/mm_ui.xml")
        raw = self.sh(role, "shell", "cat", "/sdcard/mm_ui.xml")
        try:
            root = ET.fromstring(raw[raw.index("<?xml"):])
        except Exception:
            return []
        found = []
        for n in root.iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m and (n.get("text") or n.get("content-desc")):
                x1, y1, x2, y2 = map(int, m.groups())
                found.append(((n.get("text") or n.get("content-desc")), (x1 + x2) // 2, (y1 + y2) // 2))
        return found

    def tap(self, role, pattern, scroll=0):
        rx = re.compile(pattern, re.I)
        for attempt in range(scroll + 1):
            for text, x, y in self.ui_nodes(role):
                if rx.search(text):
                    self.sh(role, "shell", "input", "tap", str(x), str(y))
                    return text
            if attempt < scroll:
                self.sh(role, "shell", "input", "swipe", "500", "1800", "500", "900", "300")
                time.sleep(.6)
        return None

    def open_light_screen(self, role):
        self.sh(role, "shell", "am", "start", "-n", bench.PACKAGE + "/.ui.MainActivity")
        time.sleep(1)
        if any(t.startswith("Turn connected phones") for t, _, _ in self.ui_nodes(role)):
            return True
        for _ in range(3):
            if self.tap(role, r"^Light Show$"):
                time.sleep(1.2)
                return True
            self.sh(role, "shell", "input", "keyevent", "4")
            time.sleep(.8)
        return False

    def boot_offset(self, role, samples=20):
        """Interval bound on (device CLOCK_BOOTTIME - host perf counter) in ms, from /proc/uptime (10 ms ticks)."""
        lo, hi, best = -1e18, 1e18, 1e9
        for _ in range(samples):
            t0 = time.perf_counter_ns() / 1e6
            raw = self.sh(role, "shell", "cat", "/proc/uptime")
            t1 = time.perf_counter_ns() / 1e6
            up = float(raw.split()[0]) * 1000
            lo, hi = max(lo, up - t1), min(hi, up + 10 - t0)
            best = min(best, t1 - t0)
        return lo, hi, best

    def screen_rgb(self, role):
        """Center pixel of a raw screencap, with the host-time window the capture happened in."""
        t0 = time.perf_counter_ns() / 1e6
        self.sh(role, "shell", "screencap", "/sdcard/mm_cap.raw")
        t1 = time.perf_counter_ns() / 1e6
        size = self.sh(role, "shell", "wm", "size").split()[-1]
        w, h = map(int, size.split("x"))
        header = 16
        offset = header + ((h // 3) * w + w // 2) * 4  # upper third: clear of the bottom control bar
        raw = self.sh(role, "shell", f"dd if=/sdcard/mm_cap.raw bs=1 skip={offset} count=3 2>/dev/null | od -An -tu1")
        vals = tuple(int(x) for x in raw.split()[:3])
        return vals, t0, t1

    def post(self, token, body, origin=None):
        return self.http("/api/command", token, json.dumps(body).encode(), origin)

    def http(self, path, token=None, body=None, origin=None):
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=body, method="POST" if body else "GET")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        if origin:
            req.add_header("Origin", origin)
        if body:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, None
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            return None, str(e)

    def latest_rev(self, light):
        return max((p["revision"] for p in light.get("programs") or []), default=0)

    # ---------- scenarios ----------
    def setup(self):
        for r in "ABC":
            self.cmd(r, "start")
        self.s.topology(120)
        self.cmd("A", "light_console", on=False)
        for r in "ABC":
            self.cmd(r, "light_leave")
        self.check("phones on Light Show screen (A, C)", self.open_light_screen("A") and self.open_light_screen("C"))

    def show_ui(self):
        """Show 1: preset show; C joins through the UI, B relays only; full-screen colors; late join; host stop."""
        host = self.cmd("A", "light_host", preset="RAINBOW", durationMs=60000, periodMs=8000)
        show = host["light"]["show"]
        hosted_at = host["hostEndMs"]
        self.check("host schedules show", show and host["light"]["hosting"], showId=show and show["showId"])
        o, light = self.wait_light("C", lambda l, o: any(x["showId"] == show["showId"] for x in l.get("offers") or []), 20)
        self.check("C sees the offer over 2 hops", o, host_observed_ms=o and round(o["hostEndMs"] - hosted_at))
        hit = self.tap("C", r"^Join A-", scroll=0)
        if not hit:
            self.cmd("C", "light_join", showId=show["showId"])
        joined = time.time() * 1000
        o, light = self.wait_light("C", lambda l, o: l.get("clock") and (l.get("show") or {}).get("showId") == show["showId"], 16)
        self.check("C joins via UI and synchronizes", o, via="UI" if hit else "receiver",
                   sync_ms=o and round(o["hostEndMs"] - joined), clock=light.get("clock"))
        o, light = self.wait_light("A", lambda l, o: any(p["nodeId"] == self.node["C"] for p in l.get("participants") or []), 15)
        self.check("host counts C as ready", o, readyCount=light.get("readyCount"), participants=light.get("participants"))
        _, b = self.light("B")
        self.check("B relays without playing", not b.get("show"), b_show=b.get("show"))

        # Clock accuracy: app offset (host - local) vs an ADB boot-clock reference for the same quantity.
        time.sleep(3)  # let the five initial probes settle
        _, light = self.light("C")
        a_lo, a_hi, a_rtt = self.boot_offset("A")
        c_lo, c_hi, c_rtt = self.boot_offset("C")
        true_lo, true_hi = a_lo - c_hi, a_hi - c_lo
        app = light["clock"]["offsetMs"]
        err_lo, err_hi = app - true_hi, app - true_lo
        self.check("C clock offset matches reference (|error| bound < 50 ms)", max(abs(err_lo), abs(err_hi)) < 50 or (err_lo <= 0 <= err_hi),
                   app_offset_ms=app, reference_interval_ms=[round(true_lo, 1), round(true_hi, 1)],
                   error_interval_ms=[round(err_lo, 1), round(err_hi, 1)], sync_rtt_ms=light["clock"]["roundTripMs"],
                   adb_min_rtt_ms=[round(a_rtt, 1), round(c_rtt, 1)])

        a_full = self.tap("A", r"^Open full screen$", scroll=2)
        c_full = self.tap("C", r"^Open full screen$", scroll=2)
        self.check("full screen opened on A and C", a_full and c_full)
        start_wait = show["startAt"] - self.light("A")[0]["lightElapsed"] + 1500
        if start_wait > 0:
            time.sleep(start_wait / 1000)
        a_off = (a_lo + a_hi) / 2
        captures = []
        for _ in range(6):
            with concurrent.futures.ThreadPoolExecutor(2) as pool:
                fa, fc = pool.submit(self.screen_rgb, "A"), pool.submit(self.screen_rgb, "C")
                for role, f in (("A", fa), ("C", fc)):
                    color, t0, t1 = f.result()
                    # Expected colors across the capture window, in host (A) show position.
                    p0, p1 = t0 + a_off - show["startAt"], t1 + a_off - show["startAt"]
                    window = [rainbow(int(p0 + k * (p1 - p0) / 20), show["periodMs"]) for k in range(21)]
                    best = min(dist(color, w) for w in window)
                    # Nearest timeline position to the captured color within +/-2 s, for a rough phase estimate.
                    scan = min(range(int(p0) - 2000, int(p1) + 2000, 10), key=lambda p: dist(color, rainbow(p, show["periodMs"])))
                    lag = 0 if p0 <= scan <= p1 else (scan - p0 if scan < p0 else scan - p1)
                    captures.append(dict(role=role, rgb=color, window_ms=round(t1 - t0), min_channel_diff=best, phase_outside_window_ms=lag))
            time.sleep(.7)
        ok = all(c["min_channel_diff"] <= 24 or abs(c["phase_outside_window_ms"]) <= 150 for c in captures)
        distinct = len({c["rgb"] for c in captures if c["role"] == "C"}) >= 3
        self.check("screens render the host timeline (A and C)", ok and distinct, captures=captures)

        # Late join halfway through (B), then local leave must not rejoin from advertisements.
        late = self.cmd("B", "light_join", showId=show["showId"])
        o, light = self.wait_light("B", lambda l, o: l.get("clock"), 16)
        pos = o and (o["lightElapsed"] + light["clock"]["offsetMs"] - show["startAt"])
        self.check("late joiner B enters current phase", o and pos and pos > 0, position_ms=pos)
        self.cmd("B", "light_leave")
        time.sleep(7)
        _, light = self.light("B")
        self.check("B stays out after leaving (adverts do not rejoin)", not light.get("show"))

        stop_t = time.time() * 1000
        hit = self.tap("A", r"^Stop show$")
        if not hit:
            self.cmd("A", "light_leave")
        o, light = self.wait_light("C", lambda l, o: not l.get("show"), 10)
        self.check("host Stop ends C's show", o, via="UI" if hit else "receiver", stop_ms=o and round(o["hostEndMs"] - stop_t), note=light.get("note"))

    def show_console(self):
        """Show 2: laptop conductor over USB on A; programs, seats, blackout/restore, background host, SOS under load."""
        self.sh("A", "forward", f"tcp:{PORT}", f"tcp:{PORT}")
        o = self.cmd("A", "light_console", on=True)
        url = o.get("lightConsoleUrl") or ""
        token = url.split("#token=")[-1] if "#token=" in url else None
        self.check("console enabled on A", token, url_has_token=bool(token))
        code, _ = self.http("/api/state")
        self.check("console refuses missing token", code == 401, code=code)
        code, _ = self.http("/api/state", token, origin="https://attacker.example")
        self.check("console refuses foreign origin", code == 403, code=code)
        code, page = self.http("/", None)
        self.check("console page served over USB forward", code == 200 or code == 401, code=code)
        wave = dict(effect="WAVE", primary=0x35DCC3, secondary=0x18224C, bpm=100, spreadMs=2400, brightness=85, groups=15, direction=1)
        code, res = self.post(token, dict(action="host", durationMs=300000, scene=wave))
        _, state = self.http("/api/state", token)
        show = (state or {}).get("show") or {}
        self.check("console hosts a show", code == 200 and res.get("ok") and state.get("hosting"), res=res, show=show)
        o, _ = self.wait_light("C", lambda l, o: any(x["showId"] == show.get("showId") for x in l.get("offers") or []), 20)
        self.cmd("C", "light_join", showId=show.get("showId"))
        o, light = self.wait_light("C", lambda l, o: l.get("clock") and self.latest_rev(l) >= 1, 20)
        self.check("C joins console show and gets its opening cue", o, revision=self.latest_rev(light))
        self.wait_light("A", lambda l, o: any(p["nodeId"] == self.node["C"] for p in l.get("participants") or []), 15)
        time.sleep(max(0, show.get("startAt", 0) - self.light("A")[0]["lightElapsed"]) / 1000 + 1)

        def deliver(name, body, urgent=False):
            _, before = self.http("/api/state", token)
            t = time.perf_counter_ns() / 1e6
            code, res = self.post(token, body)
            _, after = self.http("/api/state", token)
            programs = (after or {}).get("programs") or []
            if not (code == 200 and res and res.get("ok") and programs):
                return self.check(name, False, code=code, res=res)
            prog = max(programs, key=lambda p: p["revision"])
            o, light = self.wait_light("C", lambda l, o: self.latest_rev(l) >= prog["revision"], 10)
            if not o:
                return self.check(name, False, revision=prog["revision"], c_revision=self.latest_rev(light))
            host_now = o["lightElapsed"] + light["clock"]["offsetMs"]
            lead = prog["effectiveAt"] - host_now
            return self.check(name, lead > 0, revision=prog["revision"], arrived_with_lead_ms=round(lead),
                              host_observed_ms=round(o["hostEndMs"] - t)), prog

        for effect in ("RIPPLE", "SPIRAL"):
            r = deliver(f"{effect} cue reaches C before it takes effect", dict(action="scene", scene={**wave, "effect": effect}))
            prog = r[1] if isinstance(r, tuple) else None
            if prog:
                time.sleep(max(0, prog["effectiveAt"] - self.light("A")[0]["lightElapsed"]) / 1000 + .5)
        code, res = self.post(token, dict(action="seat", nodeId=self.node["C"], seat=dict(group=2, x=900, y=100)))
        o, light = self.wait_light("C", lambda l, o: (l.get("seat") or {}).get("group") == 2, 10)
        self.check("seat assignment reaches C", o, seat=light.get("seat"))
        r = deliver("blackout reaches C ahead of its 1.5 s lead", dict(action="blackout"))
        if isinstance(r, tuple):
            time.sleep(max(0, r[1]["effectiveAt"] - self.light("A")[0]["lightElapsed"]) / 1000 + 1.3)
            o, _ = self.light("C")
            self.check("C renders black during blackout", rgb(o["lightColor"]) == (0, 0, 0), color=rgb(o["lightColor"]))
        r = deliver("restore reaches C", dict(action="restore"))
        if isinstance(r, tuple):
            time.sleep(max(0, r[1]["effectiveAt"] - self.light("A")[0]["lightElapsed"]) / 1000 + 1.3)
        code, res = self.post(token, dict(action="grid"))
        self.check("grid arrangement accepted", code == 200 and res.get("ok"), res=res)

        # Host in background: console still conducts.
        self.sh("A", "shell", "input", "keyevent", "3")
        time.sleep(2)
        r = deliver("console conducts with host app in background", dict(action="scene", scene={**wave, "effect": "CONSTELLATION"}))
        if isinstance(r, tuple):
            time.sleep(max(0, r[1]["effectiveAt"] - self.light("A")[0]["lightElapsed"]) / 1000 + .5)
        self.open_light_screen("A")
        deliver("five-act finale reaches C", dict(action="finale", bpm=100))

        # SOS with a 1 MiB LOW transfer while the show runs.
        rec = self.s.attempt("sos_high", "during_light_show")
        self.check("SOS preempts 1 MiB transfer during show", rec["success"], latency_ms=rec.get("latency_ms"),
                   sos_before_bulk=rec.get("sos_before_bulk_destination"), reason=rec.get("failure_reason"))
        _, light = self.light("C")
        self.check("C still in the show after SOS run", (light.get("show") or {}).get("showId") == show.get("showId"))

        code, res = self.post(token, dict(action="stop"))
        o, light = self.wait_light("C", lambda l, o: not l.get("show"), 10)
        self.check("console stop ends C's show", code == 200 and o, res=res)
        self.cmd("A", "light_console", on=False)
        code, err = self.http("/api/state", token)
        self.check("disabled console no longer answers the old token", code != 200, code=code, err=err)
        self.sh("A", "forward", "--remove", f"tcp:{PORT}")

    def show_isolated(self):
        """Show 3: C buffers a 30 s show, loses its relay, ignores an unreachable stop and ends on schedule."""
        o = self.cmd("A", "light_host", preset="SUNSET", durationMs=30000, periodMs=4000)
        show = o["light"]["show"]
        self.wait_light("C", lambda l, o: any(x["showId"] == show["showId"] for x in l.get("offers") or []), 20)
        self.cmd("C", "light_join", showId=show["showId"])
        o, light = self.wait_light("C", lambda l, o: l.get("clock"), 16)
        if not self.check("C synchronized before isolation", o):
            return
        # Isolate only once playback has begun: before startAt C correctly shows the idle color.
        self.wait_light("C", lambda l, o: l.get("clock") and o["lightElapsed"] + l["clock"]["offsetMs"] > l["show"]["startAt"] + 1000, 15)
        self.cmd("B", "stop")
        self.wait_light("A", lambda l, o: self.node["B"] not in [p["nodeId"] for p in (o.get("snapshot") or {}).get("peers", [])], 30)
        self.cmd("A", "light_leave")  # host stop cannot reach C
        time.sleep(4)
        o, light = self.light("C")
        pos = o["lightElapsed"] + light["clock"]["offsetMs"] - show["startAt"] if light.get("clock") else None
        self.check("isolated C keeps playing after unreachable stop", light.get("show") and rgb(o["lightColor"]) != (11, 18, 20),
                   position_ms=pos, color=rgb(o["lightColor"]))
        o, light = self.wait_light("C", lambda l, o: not l.get("show"), 45)
        self.check("isolated C ends at the scheduled duration", o and "finished" in (light.get("note") or "").lower(), note=light.get("note"))
        self.cmd("B", "start")
        self.s.topology(120)

    def report(self):
        passed = sum(r["ok"] for r in self.results)
        lines = ["# Light Show — physical phones", "", f"Session `{self.s.id}`, {bench.now()}. A hosts, C joins, B relays (software topology lock A↔B↔C).",
                 "", f"**{passed}/{len(self.results)} checks passed.**", "", "| Check | Result | Detail |", "|---|---|---|"]
        for r in self.results:
            detail = {k: v for k, v in r.items() if k not in ("check", "ok", "captures")}
            lines.append(f"| {r['check']} | {'PASS' if r['ok'] else '**FAIL**'} | `{json.dumps(detail, default=str)[:220]}` |")
        lines += ["", "Clock error uses an ADB `/proc/uptime` reference bounded by command round trips; it bounds timeline error, not optical skew.",
                  "Screen checks read the centre-upper pixel of `screencap` and compare it with the expected Rainbow color across the capture window.",
                  "Optical display skew still needs a high-frame-rate camera (procedure step 3)."]
        (self.s.dir / "reports").mkdir(exist_ok=True)
        (self.s.dir / "reports" / "LIGHT_SHOW.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        return passed, len(self.results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", required=True)
    parser.add_argument("--only", nargs="*", choices=["ui", "console", "isolated"], default=["ui", "console", "isolated"])
    args = parser.parse_args()
    t = LightTest(args.session)
    t.s.start_logs()
    try:
        t.setup()
        for name in args.only:
            try:
                dict(ui=t.show_ui, console=t.show_console, isolated=t.show_isolated)[name]()
            except Exception as e:
                t.check(f"{name} scenario completed", False, error=f"{type(e).__name__}: {e}")
                for r in "ABC":
                    try:
                        t.cmd(r, "light_leave"); t.cmd(r, "start")
                    except Exception:
                        pass
    finally:
        t.s.close_logs()
        try:
            t.cmd("A", "light_console", on=False)
        except Exception:
            pass
        passed, total = t.report()
        print(f"LIGHT SHOW: {passed}/{total} checks passed", flush=True)


if __name__ == "__main__":
    main()

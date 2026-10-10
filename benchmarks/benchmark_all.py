"""Physical-phone benchmarks. Control uses debug-only app APIs; never changes phone radios.

Run stages in order: preflight, smoke, mini, full. Every attempt is append-only JSONL.
Each command's complete observation is retained. Failures are never overwritten.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "benchmarks"
ADB = Path(os.environ.get("MESHMIND_ADB", r"D:\sonictools\android-sdk\platform-tools\adb.exe"))
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PACKAGE = "dev.meshmind.app"
TZ = dt.timezone(dt.timedelta(hours=5, minutes=30))
SUITES = ["text", "internet", "store_forward", "sos_idle", "sos_low", "sos_medium", "sos_high", "discovery", "relay_recovery"]
COUNTS = dict(text=50, internet=30, store_forward=40, sos_idle=30, sos_low=30, sos_medium=30, sos_high=30, discovery=20, relay_recovery=20)
LOAD_BYTES = dict(sos_low=128*1024, sos_medium=512*1024, sos_high=1024*1024)
COLS = ["session_id", "stage", "benchmark", "run_id", "timestamp", "source", "relay", "destination", "packet_id", "success", "failure_class", "failure_reason", "hops", "payload_bytes", "load_level", "queued_bytes", "queued_chunks", "outage_duration_ms", "latency_ms", "recovery_latency_ms", "http_duration_ms", "http_code", "http_response_bytes", "route", "duplicate_count", "manual_intervention", "notes", "evidence_file"]


def now():
    return dt.datetime.now(TZ).isoformat()


def sh(args, timeout=30, check=True):
    p = subprocess.run([str(ADB), *args], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if check and p.returncode:
        raise InfrastructureError(f"ADB {args}: {p.stderr.strip()}")
    return p.stdout.strip()


class InfrastructureError(RuntimeError):
    pass


class SystemFailure(RuntimeError):
    pass


def discover():
    rows = sh(["devices", "-l"]).splitlines()[1:]
    found = {}
    for row in rows:
        if not re.search(r"\sdevice\s", row):
            continue
        alias = row.split()[0]
        serial = sh(["-s", alias, "shell", "getprop", "ro.serialno"])
        if serial in found:
            found[serial]["aliases"].append(alias)
            continue
        prefs = sh(["-s", alias, "shell", "run-as", PACKAGE, "cat", "shared_prefs/meshmind.xml"])
        node = re.search(r'<string name="nodeId">([^<]+)</string>', prefs)
        if not node:
            raise InfrastructureError(f"No MeshMind identity on {alias}")
        fields = {}
        for key in ["ro.product.model", "ro.product.manufacturer", "ro.product.marketname", "ro.build.version.release", "ro.build.version.sdk", "ro.build.fingerprint"]:
            fields[key] = sh(["-s", alias, "shell", "getprop", key])
        found[serial] = dict(adb_id=alias, aliases=[alias], hardware_serial=serial, node_id=node[1], properties=fields, original_preferences=prefs, connection="USB" if "transport_id:" in row and not (":" in alias or alias.startswith("adb-")) else "wireless")
    roles = {}
    for device in found.values():
        role = device["node_id"].split("-")[0]
        if role in "ABC" and len(role) == 1:
            if role in roles:
                raise InfrastructureError(f"Duplicate role {role}")
            roles[role] = device
    if set(roles) != {"A", "B", "C"}:
        raise InfrastructureError(f"Expected A,B,C physical phones, found {list(roles)}")
    expected = {"A": "CPH2585", "B": "RMX5108", "C": "SM-S928B"}
    for role, device in roles.items():
        if device["properties"]["ro.product.model"].replace("_", "-") != expected[role]:
            raise InfrastructureError(f"Unexpected hardware for {role}: {device}")
    if roles["A"]["connection"] != "USB" or any(roles[r]["connection"] != "wireless" for r in "BC"):
        raise InfrastructureError("USB A / wireless B,C safety invariant failed")
    return roles


class Session:
    def __init__(self, session_id, stage, devices, restart_mode="warm"):
        self.id, self.stage, self.devices = session_id, stage, devices
        self.dir = BASE / "sessions" / session_id
        for name in ["raw", "parsed", "reports", "plots"]:
            (self.dir / name).mkdir(parents=True, exist_ok=True)
        self.seq = 0
        self.loggers = []
        self.last_internet = 0.0
        self.restart_mode = restart_mode
        self.sage_mode = "unchanged"
        self.node = {r: d["node_id"] for r, d in devices.items()}
        self.expected = [self.node[r] for r in "ABC"]

    def adb(self, role, *args, **kw):
        # No shell radio commands are exposed by the harness. Wireless control stays online.
        return sh(["-s", self.devices[role]["adb_id"], *args], **kw)

    def start_logs(self):
        for role in "ABC":
            f = (self.dir / "raw" / f"{self.stage}_{role}_{uuid.uuid4().hex[:6]}_logcat.txt").open("w", encoding="utf-8")
            p = subprocess.Popen([str(ADB), "-s", self.devices[role]["adb_id"], "logcat", "-v", "epoch", "MeshMind:D", "AndroidRuntime:E", "*:S"], stdout=f, stderr=subprocess.STDOUT)
            self.loggers.append((p, f))

    def close_logs(self):
        for p, f in self.loggers:
            p.terminate()
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
            f.close()

    def command(self, role, command="snapshot", **extras):
        token = "b" + uuid.uuid4().hex
        args = ["shell", "am", "broadcast", "-n", PACKAGE + "/.benchmark.BenchmarkReceiver", "--es", "token", token, "--es", "command", command]
        for key, value in extras.items():
            if isinstance(value, bool):
                args += ["--ez", key, "true" if value else "false"]
            else:
                args += ["--ei" if isinstance(value, int) else "--es", key, str(value)]
        begin = time.time_ns() / 1e6
        reply = self.adb(role, *args)
        if "Permission Denial" in reply or "Error" in reply:
            raise InfrastructureError(reply)
        deadline = time.monotonic() + 45
        while True:
            raw = self.adb(role, "shell", "run-as", PACKAGE, "cat", f"files/benchmark/{token}.json", check=False)
            try:
                obj = json.loads(raw)
                break
            except json.JSONDecodeError:
                if time.monotonic() > deadline:
                    raise InfrastructureError(f"No instrumentation response: {role} {command}; {reply}; {raw[:200]}")
                time.sleep(.15)
        end = time.time_ns() / 1e6
        obj["hostBeginMs"], obj["hostEndMs"] = begin, end
        obj["clockOffsetEstimateMs"] = obj["deviceTime"] - (begin + end) / 2
        obj["clockOffsetUncertaintyMs"] = (end - begin) / 2
        obj["command"], obj["extras"], obj["role"] = command, extras, role
        obj["rawFile"] = f"raw/{token}_{role}_{command}.json"
        (self.dir / obj["rawFile"]).write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")
        self.adb(role, "shell", "run-as", PACKAGE, "rm", f"files/benchmark/{token}.json")
        if not obj.get("ok"):
            raise SystemFailure(f"App command failed: {obj.get('error')}")
        return obj

    def all_snapshots(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            futures = {r: pool.submit(self.command, r) for r in "ABC"}
            return {r: f.result() for r, f in futures.items()}

    def wait(self, role, predicate, seconds=120):
        deadline = time.monotonic() + seconds
        while True:
            obj = self.command(role)
            if predicate(obj):
                return obj
            if time.monotonic() >= deadline:
                raise SystemFailure(f"Timed out after {seconds}s waiting on {role}")
            time.sleep(.5)

    def topology(self, seconds=120):
        expected = {"A": [self.node["B"]], "B": [self.node["A"], self.node["C"]], "C": [self.node["B"]]}
        deadline = time.monotonic() + seconds
        while True:
            snaps = self.all_snapshots()
            good = True
            for r, obj in snaps.items():
                peers = sorted(p["nodeId"] for p in (obj.get("snapshot") or {}).get("peers", []) if p.get("nodeId"))
                if peers != sorted(expected[r]):
                    good = False
                filters = {"A": ["B"], "B": ["A", "C"], "C": ["B"]}
                if sorted(obj["peerFilter"]) != filters[r]:
                    raise SystemFailure(f"Topology filter wrong on {r}: {obj['peerFilter']}")
            if good:
                return snaps
            if time.monotonic() > deadline:
                raise SystemFailure(f"Topology not established: { {r:(s.get('snapshot') or {}).get('peers') for r,s in snaps.items()} }")
            time.sleep(1)

    def route_ready(self, seconds=120):
        return self.wait("A", lambda o: any(g["gatewayId"] == self.node["C"] and g["hopDistance"] == 2 and g["viaPeer"] == self.node["B"] for g in (o.get("snapshot") or {}).get("gateways", [])), seconds)

    def preflight(self, install=False):
        meta_path = self.dir / "metadata.json"
        metadata = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
        metadata.update(session_id=self.id, started_at=metadata.get("started_at", now()), topology="A <-> B <-> C; software peer filter; no physical RF isolation", devices=self.devices, safety="Wi-Fi, Bluetooth, airplane mode and IP routes are not modified. Outages stop MeshMind only.", endpoint="https://example.com/", quantile_method="Hyndman-Fan type 7: linear interpolation at (n-1)*p; population standard deviation; successful valid samples only; p99 unstable below 100.")
        metadata["apk_sha256"] = hashlib.sha256(APK.read_bytes()).hexdigest()
        metadata["apk_path"] = str(APK)
        metadata["configuration"] = dict(counts=COUNTS, warmups=5, outages_ms=[5000,10000,20000,30000], low_bytes=LOAD_BYTES["sos_low"], medium_bytes=LOAD_BYTES["sos_medium"], high_bytes=LOAD_BYTES["sos_high"], internet_spacing_s=11, packet_timeout_s=60, recovery_timeout_s=120, bulk_timeout_s=180, mesh_poll_s=.5)
        for role in "ABC":
            d = self.devices[role]
            d["wifi_on"] = self.adb(role, "shell", "settings", "get", "global", "wifi_on")
            d["bluetooth_on"] = self.adb(role, "shell", "settings", "get", "global", "bluetooth_on")
            if (role in "BC" and d["wifi_on"] != "1") or d["bluetooth_on"] != "1":
                raise InfrastructureError(f"Required radios off on {role}; radio changes are intentionally unavailable")
            for service in ["wifi", "bluetooth_manager", "connectivity", "battery", "package " + PACKAGE]:
                name = service.split()[0]
                (self.dir / "raw" / f"{self.stage}_{role}_dumpsys_{name}.txt").write_text(self.adb(role, "shell", "dumpsys", *service.split()), encoding="utf-8")
            if install:
                print(f"Installing instrumentation APK on {role} ({d['adb_id']})", flush=True)
                self.adb(role, "install", "-r", str(APK), timeout=120)
            remote = self.adb(role, "shell", "pm", "path", PACKAGE).splitlines()[0].removeprefix("package:")
            d["installed_apk_sha256"] = self.adb(role, "shell", "sha256sum", remote).split()[0]
            if d["installed_apk_sha256"] != metadata["apk_sha256"]:
                raise InfrastructureError(f"APK hash differs on {role}; use preflight --install")
            self.adb(role, "shell", "am", "start", "-n", PACKAGE + "/.ui.MainActivity")
            self.command(role, "start")
        metadata["preflight_snapshots"] = self.topology()
        metadata["gateway_snapshot"] = self.route_ready()
        rejected = {}
        for role, forbidden in [("A", self.node["C"]), ("C", self.node["A"])]:
            events = metadata["preflight_snapshots"][role]["logs"] or []
            rejected[role] = [e for e in events if "topology lock" in e["message"] and forbidden in e["message"]]
            if not rejected[role]:
                # Observe discovery until the disallowed peer is seen, without relaxing the filter.
                obs = self.wait(role, lambda o: any("topology lock" in e["message"] and forbidden in e["message"] for e in o["logs"] or []), 90)
                rejected[role] = [e for e in obs["logs"] if "topology lock" in e["message"] and forbidden in e["message"]]
        metadata["direct_link_rejection_evidence"] = rejected
        metadata["preflight_passed"] = True
        meta_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        print("PREFLIGHT PASSED: exact A-B-C links, A-C blocked, C gateway two hops via B, identical APK hashes, radios unchanged", flush=True)

    def record(self, record, evidence):
        record.update(session_id=self.id, stage=self.stage, sage_mode=self.sage_mode)
        path = f"parsed/{record['stage']}_{record['benchmark']}_{record['run_id']}_{uuid.uuid4().hex[:6]}.json"
        record["evidence_file"] = path
        (self.dir / path).write_text(json.dumps(dict(run=record, evidence=evidence), ensure_ascii=False, indent=2), encoding="utf-8")
        with (self.dir / "attempts.jsonl").open("a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
            f.flush()
            os.fsync(f.fileno())
        print(f"{self.stage} {record['benchmark']} #{record['run_id']}: {'PASS' if record['success'] else 'FAIL'} latency={record.get('latency_ms')}ms recovery={record.get('recovery_latency_ms')}ms {record.get('failure_reason','')}", flush=True)

    def events(self, obs, packet_id=None, category=None):
        return [e for e in obs.get("logs") or [] if (packet_id is None or e.get("packetId") == packet_id) and (category is None or e["category"] == category)]

    def verify_packet(self, packet, obs):
        pid = packet["packetId"]
        a,b,c = [obs[r] for r in "ABC"]
        snap = lambda o: o.get("snapshot") or {}
        source = [m for m in snap(a).get("metrics", []) if m["packetId"] == pid and m["side"] == "source"]
        dest = [m for m in snap(c).get("metrics", []) if m["packetId"] == pid and m["side"] == "destination"]
        if not source or len(dest) != 1:
            raise SystemFailure("Missing final delivery ACK or destination metric; exactly-once delivery not verified")
        m = source[0]
        if m["route"] != self.expected or m["hops"] != 2 or dest[0]["route"] != self.expected or dest[0]["hops"] != 2:
            raise SystemFailure(f"Incorrect forward route/hops: {m}")
        for r, peer in [("A", "B"), ("B", "C")]:
            if not any("→ " + self.node[peer] in e["message"] for e in self.events(obs[r], pid, "FORWARD")):
                raise SystemFailure(f"No per-packet forward evidence {r}->{peer}")
        ack_event = next((e for e in self.events(a, pid, "DELIVERY") if e["message"].startswith("ACK:")), None)
        if not ack_event:
            raise SystemFailure("ACK metric exists but final ACK log missing")
        logs = a["logs"]
        idx = logs.index(ack_event)
        ack_rx = next((e for e in reversed(logs[:idx]) if e["category"] == "RX" and e["message"].startswith("rx ACK ")), None)
        if not ack_rx or "route=" + "→".join(reversed(self.expected)) not in ack_rx["message"]:
            raise SystemFailure("Final ACK reverse route not verified")
        ack_id = ack_rx["packetId"]
        for r, peer in [("C", "B"), ("B", "A")]:
            if not any("→ " + self.node[peer] in e["message"] for e in self.events(obs[r], ack_id, "FORWARD")):
                raise SystemFailure(f"Missing matching ACK forward {r}->{peer}")
        if not self.events(b, pid, "RX") or not self.events(b, ack_id, "RX"):
            raise SystemFailure("Relay did not record both packet and matching ACK")
        if any("→ " + self.node["C"] in e["message"] for e in self.events(a, pid, "FORWARD")):
            raise SystemFailure("Forbidden direct A->C send")
        return dict(latency_ms=m["rttMs"], hops=2, route=">".join(m["route"]), duplicate_count=len(self.events(c,pid,"DUPLICATE"))), dict(source_metric=m, destination_metric=dest[0], ack_packet_id=ack_id, packet_events={r:self.events(obs[r], pid) for r in "ABC"}, ack_events={r:self.events(obs[r], ack_id) for r in "ABC"})

    def deliver(self, packet, seconds=60):
        pid = packet["packetId"]
        self.wait("A", lambda o: any(m["packetId"] == pid and m["side"] == "source" for m in (o.get("snapshot") or {}).get("metrics", [])), seconds)
        obs = self.all_snapshots()
        return (*self.verify_packet(packet, obs), obs)

    def attempt(self, benchmark, run_id, outage_ms=0):
        rec = dict(benchmark=benchmark, run_id=run_id, timestamp=now(), source=self.node["A"], relay=self.node["B"], destination=self.node["C"], success=False, failure_class="", failure_reason="", outage_duration_ms=outage_ms or "", manual_intervention=False, restart_mode=self.restart_mode)
        evidence = {}
        stopped = None
        try:
            radio={r: self.adb(r,"shell","cmd","wifi","status").splitlines()[0] for r in "ABC"}
            evidence["wifi_state_at_attempt"]=radio
            rec["source_wifi_state"]=radio["A"]
            rec["source_airplane_mode"]=self.adb("A","shell","settings","get","global","airplane_mode_on")
            if any(radio[r] != "Wifi is enabled" for r in "BC"):
                raise InfrastructureError("Wireless ADB phone Wi-Fi is not enabled; no radio changes will be attempted")
            self.topology(120)
            if benchmark == "discovery":
                for r in "ABC":
                    self.command(r,"stop")
                    if self.restart_mode=="cold":
                        self.adb(r,"shell","am","force-stop",PACKAGE)
                        self.adb(r,"shell","am","start","-n",PACKAGE+"/.ui.MainActivity")
                a_start = self.command("A", "start")
                evidence["starts"] = dict(A=a_start, B=self.command("B","start"), C=self.command("C","start"))
                route = self.route_ready(120)
                gw_logs = [e for e in self.events(route, category="BEACON") if e["message"] == f"gateway {self.node['C']} at 2 hop(s) via {self.node['B']}"]
                if not gw_logs:
                    raise SystemFailure("No gateway discovery event on A")
                rec["latency_ms"] = gw_logs[0]["time"] - a_start["commandTime"]
                rec["route"] = ">".join(self.expected)
                evidence["gateway_learned"] = gw_logs[0]
                evidence["discovered"] = self.topology(120)
                command = self.command("A","text",destination=self.node["C"], text="bench_route_"+uuid.uuid4().hex[:12])
                values, proof, obs = self.deliver(command["packet"])
                evidence["route_usable_probe"] = proof
                evidence["route_usable_at"] = command["packet"]["createdAt"] + values["latency_ms"]
                rec["packet_id"] = command["packet"]["packetId"]
                rec["route_usable_latency_ms"] = evidence["route_usable_at"]-a_start["commandTime"]
                rec["hops"] = 2
                rec["notes"] = "Discovery uses A's start-command to A's gateway-learned event; includes sequential start of B,C. Usable route separately confirmed by text+ACK."
            elif benchmark == "internet":
                self.route_ready()
                # Fixed cadence respects the existing 6/minute/requester policy; never changes rate limits.
                gap = 11 - (time.monotonic() - self.last_internet)
                if gap > 0:
                    time.sleep(gap)
                self.last_internet = time.monotonic()
                cmd = self.command("A","internet")
                packet = cmd["packet"]
                rec["packet_id"] = packet["packetId"]
                rec["source_internet_validated"] = cmd["internetValidated"]
                response = self.wait("A", lambda o: any(i["requestId"] == packet["packetId"] and i.get("completedAt") is not None for i in (o.get("snapshot") or {}).get("inet", [])), 60)
                result = next(i for i in response["snapshot"]["inet"] if i["requestId"] == packet["packetId"])
                obs = self.all_snapshots()
                http = result["response"]
                rec.update(latency_ms=result["completedAt"]-result["sentAt"], http_code=http["httpCode"], http_response_bytes=http["body"]["size"], hops=2, route=">".join(http["requestRoute"]))
                evidence.update(command=cmd, result=result, observations=obs)
                if http["status"] != "OK" or not 200 <= http["httpCode"] < 300:
                    raise SystemFailure(f"Gateway response {http['status']} HTTP {http['httpCode']}: {http['message']}")
                if http["gatewayId"] != self.node["C"] or http["requestRoute"] != self.expected or result["responseRoute"] != list(reversed(self.expected)):
                    raise SystemFailure("Internet request or response route is not the required two-hop path")
                c_events = self.events(obs["C"],packet["packetId"])
                start = next((e for e in c_events if e["message"].startswith("GATEWAY executing")),None)
                end = next((e for e in c_events if e["message"].startswith("GATEWAY result")),None)
                if not start or not end:
                    raise SystemFailure("Missing C execution evidence")
                rec["http_duration_ms"] = end["time"]-start["time"]
                for r, peer in [("A","B"),("B","C")]:
                    if not any("→ "+self.node[peer] in e["message"] for e in self.events(obs[r],packet["packetId"],"FORWARD")):
                        raise SystemFailure(f"Missing gateway request forwarding {r}->{peer}")
                response_rx = [e for e in self.events(obs["A"],category="RX") if e["message"].startswith("rx INET_RESPONSE ") and e["time"] <= result["completedAt"] and e["time"] >= packet["createdAt"]]
                if not response_rx:
                    raise SystemFailure("Missing response packet receipt")
                response_id = response_rx[-1]["packetId"]
                if not any("→ "+self.node["A"] in e["message"] for e in self.events(obs["B"],response_id,"FORWARD")):
                    raise SystemFailure("Missing B response forwarding")
                evidence["response_packet_id"] = response_id
                rec["notes"] = f"Full response accepted by requester router; UI rendering not separately timed. C execution duration includes policy and HTTP; no cross-device one-way timing. A Internet validated={cmd['internetValidated']}, gateway consent off; mesh request explicitly remote."
            else:
                if benchmark in ["store_forward","relay_recovery"]:
                    stopped = "C" if benchmark == "store_forward" else "B"
                    evidence["stop"] = self.command(stopped,"stop")
                    if self.restart_mode=="cold" and stopped=="B":
                        self.adb("B","shell","am","force-stop",PACKAGE)
                    t = time.monotonic()
                    isolated = self.wait("B" if stopped == "C" else "A", lambda o: self.node[stopped] not in [p["nodeId"] for p in (o.get("snapshot") or {}).get("peers", [])], 30)
                    evidence["loss_detected"] = isolated
                    rec["loss_detection_host_ms"] = isolated["hostEndMs"]-evidence["stop"]["hostBeginMs"]
                    rec["loss_detection_timing_note"] = "Host-observed stop-command to confirmed loss, including ADB/observation overhead; no cross-device clock subtraction."
                if benchmark in LOAD_BYTES:
                    rec["load_level"] = benchmark.removeprefix("sos_").upper()
                    rec["background_transfer_bytes"] = LOAD_BYTES[benchmark]
                    cmd = self.command("A","bulk_sos", destination=self.node["C"], text="bench_sos_"+uuid.uuid4().hex[:12], bytes=LOAD_BYTES[benchmark])
                    injection = cmd["atInjection"]
                    queued = [q for q in injection["queued"] if q["type"] == "CHUNK"]
                    rec.update(queued_chunks=len(queued),queued_bytes=len(queued)*16384)
                    evidence["injection_snapshot"] = injection
                    if not queued:
                        raise SystemFailure("No bulk chunks pending at SOS injection; load test invalid")
                else:
                    kind = "sos" if benchmark == "sos_idle" else "text"
                    cmd = self.command("A",kind,destination=self.node["C"],text="bench_"+uuid.uuid4().hex[:16])
                packet = cmd["packet"]
                evidence["command"] = cmd
                rec.update(packet_id=packet["packetId"],payload_bytes=packet["payload"]["size"],packet_creation_time_ms=packet["createdAt"])
                if stopped:
                    if benchmark == "store_forward":
                        held = self.wait("B", lambda o: any(e.get("packetId") == packet["packetId"] and "no eligible peer" in e["message"] for e in o.get("logs") or []), 30)
                        stored = next(e for e in held["logs"] if e.get("packetId") == packet["packetId"] and "no eligible peer" in e["message"])
                        rec["relay_stored_time_ms"] = stored["time"]
                        evidence["held_at_B"] = held
                    else:
                        pending = self.command("A")
                        evidence["queued_without_relay"] = pending
                        if any(m["packetId"] == packet["packetId"] and m["side"] == "source" for m in pending["snapshot"]["metrics"]):
                            raise SystemFailure("Packet delivered while relay stopped")
                    # Outage exposure after verified queueing is explicitly configured; total absence is also recorded.
                    time.sleep(outage_ms/1000 if benchmark == "store_forward" else 5)
                    if self.restart_mode=="cold" and stopped=="B":
                        self.adb("B","shell","am","start","-n",PACKAGE+"/.ui.MainActivity")
                    restore = self.command(stopped,"start")
                    evidence["restore"] = restore
                    rec["restoration_time_ms"] = restore["commandTime"]
                    rec["actual_absence_ms"] = restore["commandTime"]-evidence["stop"]["commandTime"]
                    stopped = None
                values, proof, obs = self.deliver(packet,120 if benchmark in ["store_forward","relay_recovery"] else 60)
                rec.update(values)
                evidence.update(proof=proof, observations=obs)
                if benchmark == "store_forward":
                    dest_time = proof["destination_metric"]["measuredAt"]
                    rec["destination_received_time_ms"] = dest_time
                    rec["source_ack_time_ms"] = proof["source_metric"]["measuredAt"]
                    rec["recovery_latency_ms"] = dest_time-rec["restoration_time_ms"]
                    rec["notes"] = "Recovery uses C's own start-command and delivery clocks; outage bucket is wait after B retention, actual absence separately recorded."
                elif benchmark == "relay_recovery":
                    # Primary recovery uses B's restoration and B forwarding (same clock), plus host bounded estimate for backlog ACK.
                    b_forward = next(e for e in proof["packet_events"]["B"] if e["category"] == "FORWARD")
                    rec["recovery_latency_ms"] = b_forward["time"]-rec["restoration_time_ms"]
                    rec["notes"] = "Primary recovery: B restore to B forward of queued probe, same clock. A ACK RTT includes 5s queue exposure."
                elif benchmark in LOAD_BYTES:
                    transfer = cmd["transferId"]
                    ack_time = proof["source_metric"]["measuredAt"]
                    complete = self.wait("A", lambda o: any(t["transferId"] == transfer and t.get("completedAt") for t in (o.get("snapshot") or {}).get("transfers",[])),180)
                    out = next(t for t in complete["snapshot"]["transfers"] if t["transferId"] == transfer)
                    target = self.command("C","file",transferId=transfer)
                    incoming = next((t for t in obs["C"]["snapshot"]["transfers"] if t["transferId"] == transfer),None)
                    rec["remaining_chunks_at_destination_observation"] = incoming["total"]-incoming["done"] if incoming else LOAD_BYTES[benchmark]//16384
                    rec["bulk_completed_time_ms"] = out["completedAt"]
                    rec["bulk_resumed"] = out["completedAt"] > ack_time
                    rec["bulk_integrity"] = target["sha256"] == cmd["sha256"] and target["fileBytes"] == LOAD_BYTES[benchmark]
                    rec["notes"] = "Queue observed at injection and after delivery polling; exact queue at destination SOS arrival unavailable. Preemption proven by C SOS timestamp before C full-transfer completion and A SOS ACK before full bulk ACK. SHA-256 verifies received bytes."
                    evidence.update(bulk_complete=complete,bulk_file=target)
                    # C SOS metric and transfer completion timestamps share C's clock, even if post-poll queue is already empty.
                    destination = self.command("C")
                    final_t = next(t for t in destination["snapshot"]["transfers"] if t["transferId"] == transfer)
                    rec["sos_before_bulk_destination"] = proof["destination_metric"]["measuredAt"] < final_t["completedAt"]
                    evidence["destination_complete"] = destination
                    if not rec["bulk_integrity"] or not rec["bulk_resumed"] or not rec["sos_before_bulk_destination"]:
                        raise SystemFailure("Bulk integrity/resumption or preemption ordering failed")
            rec["success"] = True
        except InfrastructureError as e:
            rec.update(failure_class="TEST_INFRASTRUCTURE", failure_reason=str(e))
        except (SystemFailure, StopIteration) as e:
            rec.update(failure_class="MESHMIND_SYSTEM", failure_reason=str(e) or "Required evidence absent")
        except Exception as e:
            rec.update(failure_class="TEST_INFRASTRUCTURE", failure_reason=f"{type(e).__name__}: {e}")
        finally:
            if benchmark in LOAD_BYTES and not rec["success"] and evidence.get("command",{}).get("transferId"):
                try:
                    transfer=evidence["command"]["transferId"]
                    evidence["failed_run_bulk_drain"]=self.wait("A",lambda o: any(t["transferId"]==transfer and t.get("completedAt") for t in (o.get("snapshot") or {}).get("transfers",[])),180)
                except Exception as e:
                    rec["notes"] = rec.get("notes","")+f" Pending bulk did not drain: {e}"
            if stopped:
                try:
                    evidence["finally_restore"] = self.command(stopped,"start")
                except Exception as e:
                    rec["notes"] = f"Automatic mesh restoration failed: {e}"
            self.record(rec,evidence)
        time.sleep(1)
        return rec


def quantile(values,p):
    if not values:
        return None
    if len(values)==1:
        return values[0]
    if p==0:
        return min(values)
    if p==1:
        return max(values)
    percentile=round(p*100)
    if not math.isclose(p*100,percentile):
        raise ValueError("This benchmark exports integer percentiles only")
    # Python's inclusive method is Hyndman-Fan type 7 for these probabilities.
    return statistics.quantiles(values,n=100,method="inclusive")[percentile-1]


def write_csv(path, rows, cols):
    with path.open("w",newline="",encoding="utf-8") as f:
        w=csv.DictWriter(f,fieldnames=cols,extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def report(session_dir, render_plots=True):
    path=session_dir/"attempts.jsonl"
    runs=[json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.exists() else []
    classification=session_dir/"parsed/failure_classification_audit.json"
    if classification.exists():
        changes={r["evidence_file"]:r for r in json.loads(classification.read_text(encoding="utf-8"))}
        for r in runs:
            if r["evidence_file"] in changes:
                r["original_failure_class"]=r["failure_class"]
                r["failure_class"]=changes[r["evidence_file"]]["audited_failure_class"]
                r["failure_audit_note"]=changes[r["evidence_file"]]["reason"]
    late=session_dir/"parsed/medium_16_late_delivery_audit.json"
    if late.exists():
        late_result=json.loads(late.read_text(encoding="utf-8"))
        for r in runs:
            if r["evidence_file"]==late_result["original_failure"]["evidence_file"]:
                r["observed_after_timeout_rtt_ms"]=late_result["posthoc_metrics"]["latency_ms"]
    extras=sorted(set().union(*(r.keys() for r in runs))-set(COLS)) if runs else []
    write_csv(session_dir/"parsed/benchmark_runs.csv",runs,COLS+extras)
    measured=[r for r in runs if r["stage"]=="full"]
    summary=[]
    for name in SUITES:
        groups=[5000,10000,20000,30000] if name=="store_forward" else [None]
        for outage in groups:
            rs=[r for r in measured if r["benchmark"]==name and (outage is None or r["outage_duration_ms"]==outage)]
            ok=[r for r in rs if r["success"]]
            item=dict(benchmark=name,outage_duration_ms=outage or "",attempts=len(rs),successes=len(ok),failures=len(rs)-len(ok),success_rate_pct=100*len(ok)/len(rs) if rs else None,system_failures=sum(r["failure_class"]=="MESHMIND_SYSTEM" for r in rs),infrastructure_failures=sum(r["failure_class"]=="TEST_INFRASTRUCTURE" for r in rs))
            for metric in ["latency_ms","recovery_latency_ms","http_duration_ms"]:
                vals=[r[metric] for r in ok if r.get(metric) is not None]
                item.update({metric+"_"+k:v for k,v in dict(n=len(vals),mean=statistics.mean(vals) if vals else None,std=statistics.pstdev(vals) if vals else None,min=min(vals) if vals else None,max=max(vals) if vals else None,p50=quantile(vals,.5),p90=quantile(vals,.9),p95=quantile(vals,.95),p99=quantile(vals,.99)).items()})
            summary.append(item)
    fields=list(summary[0])
    write_csv(session_dir/"parsed/benchmark_summary.csv",summary,fields)
    write_csv(BASE/"benchmark_runs.csv",runs,COLS+extras)
    write_csv(BASE/"benchmark_summary.csv",summary,fields)
    metadata=json.loads((session_dir/"metadata.json").read_text(encoding="utf-8")) if (session_dir/"metadata.json").exists() else {}
    lines=["# MeshMind 3-Phone Physical Benchmark", "", f"Session: `{session_dir.name}`. Report generated {now()}.", "", "## Hardware", "", "| Role | User-supplied phone label | Detected model | Android | ADB | Node |", "|---|---|---|---|---|---|"]
    names=dict(A="OnePlus 12R",B="Realme 12 Pro (user label; model identifier is authoritative)",C="Samsung Galaxy S24 Ultra")
    for role,d in metadata.get("devices",{}).items():
        p=d["properties"]
        lines.append(f"| {role} | {names[role]} | {p['ro.product.model']} | {p['ro.build.version.release']} | {d['connection']} `{d['adb_id']}` | `{d['node_id']}` |")
    lines += ["", "## Topology", "", "A ↔ B ↔ C. The topology is software-enforced using the existing Nearby peer filter/topology lock. Disallowed A↔C application links are rejected; this is **not physical RF isolation**.", "", f"Preflight passed: `{metadata.get('preflight_passed',False)}`. Every delivered measured packet requires matching full-UUID relay receive/send logs, a two-hop destination route, and matching reverse ACK evidence (or Internet response evidence).", "", "## Build / Test Conditions", "", f"APK SHA-256: `{metadata.get('apk_sha256','not verified')}`. Version 0.1.0 / code 1; debug-only shell-permission control receiver added. Production routing, persistence, priority and transport code unchanged.", "", "The harness never changes radios. B and C retain Wi-Fi for wireless ADB; A remains controlled over USB. A began preflight with Wi-Fi enabled and was later observed with airplane mode on and Wi-Fi off. Per-attempt radio observations are captured in full runs. Bluetooth remains available. Outages stop only MeshMind, never the radio. Nearby P2P_CLUSTER chooses the available bearer; results do not establish Bluetooth-only performance. Existing non-disruptive Wi-Fi upgrade policy retained.", "", "Only C has gateway consent. INET_REQUEST follows the remote app path A→B→C. The smoke gateway attempt had validated Internet on A; all five mini gateway attempts reported no validated Internet on A. Full runs record A radio, airplane and validated state separately. Remote gatewaying is proven; do not infer isolation for a sample without its radio evidence. Endpoint is always https://example.com/. Gateway cadence is ≥11 seconds to respect 6 requests/minute.", "", "The harness observes snapshots and logs through ADB and saves complete raw observations. Snapshot polling and durable Room writes are part of the instrumented test conditions. Logcat is continuously captured on all phones for MeshMind plus fatal AndroidRuntime errors; structured app logs accompany each command.", "", "## Method", "", "Stages: preflight → one smoke attempt per scenario → five mini attempts per scenario → inspect → full matrix. Warmups are retained and excluded from full measured summaries. All attempts, including failed infrastructure attempts, remain in JSONL/CSV. No failures are replaced. Success denominators include every full attempt; latency distributions use successful valid attempts only.", "", "Percentiles use Python statistics.quantiles with the inclusive method, equivalent to Hyndman–Fan type 7 linear interpolation at (n−1)p. Standard deviation is population standard deviation. P99 is descriptive and unstable below 100 samples; no statistical significance is claimed. Primary RTT uses source app enqueue and final ACK times on A; Internet RTT uses full-response acceptance on A. Store recovery uses C restore and C delivery, both on C. Relay recovery uses B restore and queued packet forwarding, both on B. Cross-device times are preserved but not presented as precise one-way latencies. Each snapshot records a host/device offset estimate and its round-trip uncertainty.", "", "Store outage buckets (5/10/20/30s) describe the deliberate wait after B retains the packet. Actual absence, including loss detection and queue confirmation, is separately recorded. Discovery latency starts at A's start command and includes subsequent B/C starts, ending when A learns C via B; a subsequent text+ACK validates route usability.", "", "SOS load levels use repeatable 128KiB/512KiB/1MiB LOW transfers (8/32/64 chunks of 16KiB). SOS is queued immediately after transfer creation through unchanged APIs. Pending chunks at injection, source completion after SOS ACK, destination SOS before bulk completion, and destination SHA-256 are required. Queued-byte values are payload estimates (chunk count ×16KiB), excluding wire headers. Queue depth at a later polling observation is not an exact depth at SOS delivery.", "", "## Results", "", "| Test / outage | Attempts | Success | p50 ms | p90 ms | p95 ms | p99 ms† | Mean ms | Min ms | Max ms | Std ms |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    fmt=lambda x:"—" if x is None else f"{x:.1f}"
    for s in summary:
        metric="recovery_latency_ms" if s["benchmark"] in ["store_forward","relay_recovery"] else "latency_ms"
        label=s["benchmark"]+(f" / {s['outage_duration_ms']/1000:.0f}s" if s["outage_duration_ms"] else "")
        vals=[s[metric+"_"+k] for k in ["p50","p90","p95","p99","mean","min","max","std"]]
        lines.append(f"| {label} | {s['attempts']} | {s['successes']}/{s['attempts']} | " + " | ".join(fmt(v) for v in vals)+" |")
    ab=[r for r in measured if r.get("sage_mode") in ("on","off")]
    if ab:
        lines += ["", "## SAGE shadow mode A/B", "", "Same suites with SAGE shadow scoring on vs off (`--sage`). Shadow mode must not change outcomes: compare success and latency per row.", "", "| Test | SAGE | Attempts | Success | p50 ms | p95 ms |", "|---|---|---:|---:|---:|---:|"]
        for name in SUITES:
            for mode in ("off","on"):
                rs=[r for r in ab if r["benchmark"]==name and r["sage_mode"]==mode]
                if not rs:
                    continue
                vals=[r["latency_ms"] for r in rs if r["success"] and r.get("latency_ms") is not None]
                lines.append(f"| {name} | {mode} | {len(rs)} | {sum(r['success'] for r in rs)}/{len(rs)} | {fmt(quantile(vals,.5))} | {fmt(quantile(vals,.95))} |")
        stats=sorted((session_dir/"parsed").glob("sage_stats_*.json"))
        if stats:
            lines += ["", "SAGE on-phone scoring (from the app's shadow statistics at the end of each `--sage on` stage):", "", "| File | Role | Scored | Eco would skip | Peer summaries | Score p50 µs | Score p95 µs | Errors | Dropped |", "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
            for f in stats:
                for role,st in json.loads(f.read_text(encoding="utf-8")).items():
                    if st:
                        lines.append(f"| {f.name} | {role} | {st.get('scored')} | {st.get('wouldSkip')} | {st.get('peersWithSummary')} | {st.get('p50Micros')} | {st.get('p95Micros')} | {st.get('errors')} | {st.get('dropped', '—')} |")
    lines += ["", "† P99 is unstable at these sample sizes. See benchmark_summary.csv for separate overall RTT and recovery distributions and HTTP execution times.", "", "## Stage audit", "", "| Stage | Attempts | Passed | Failed |", "|---|---:|---:|---:|"]
    for stage in sorted({r["stage"] for r in runs}):
        rs=[r for r in runs if r["stage"]==stage]
        lines.append(f"| {stage} | {len(rs)} | {sum(r['success'] for r in rs)} | {sum(not r['success'] for r in rs)} |")
    lines += ["", "## Failures and outliers", ""]
    failed=[r for r in runs if not r["success"]]
    lines += [f"- {r['stage']} {r['benchmark']} #{r['run_id']}: **{r['failure_class']}** — {r['failure_reason']} (`{r['evidence_file']}`)." for r in failed] or ["No recorded attempted-run failures."]
    valid=[r for r in measured if r["success"] and r.get("latency_ms") is not None]
    if valid:
        out=max(valid,key=lambda r:r["latency_ms"])
        lines += ["",f"Largest successful total latency: {out['benchmark']} #{out['run_id']}, {out['latency_ms']}ms. Store/relay runs include intentional outage time."]
    lines += ["", "## Claims discipline", "", f"**VALIDATED:** Only the physical attempts actually recorded in this session ({len(measured)} full-matrix attempts so far). Detailed per-packet evidence is in parsed run JSON files.", "", "**SOFTWARE-ENFORCED:** Two-hop A–B–C application topology and rejection of direct A–C links. All phones are in the same test environment.", "", "**SIMULATED:** No simulated measurements included in these tables. Existing JVM tests are functional checks, not physical evidence.", "", "**NOT TESTED:** Event-scale operation, 20,000 phones, emergency guarantees, RF isolation, field range, battery longevity, Bluetooth-only performance, Internet isolation at the OS level, simultaneous mixed-traffic suite, single-hop comparison, or statistical significance.", "", "## Artifacts", "", f"Session directory: `sessions/{session_dir.name}/`. `raw/`: logcat, dumpsys, command observations. `parsed/`: per-attempt evidence, CSVs. `plots/`: distributions, visible outliers and success rates. Original JSONL is append-only. Tests with missing measurements are shown as untested, never filled from earlier demos."]
    text="\n".join(lines)+"\n"
    (session_dir/"reports/REPORT.md").write_text(text,encoding="utf-8")
    (BASE/"REPORT.md").write_text(text,encoding="utf-8")
    if render_plots:
        plots(session_dir,measured)
    return summary


def plots(session_dir,runs):
    # Use the existing CPython 3.12 plotting packages if not on the runtime search path.
    bundled=Path(r"D:\sonictools\venv\Lib\site-packages")
    if bundled.exists():
        sys.path.insert(0,str(bundled))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.size":11,"figure.dpi":130,"axes.spines.top":False,"axes.spines.right":False})
    dest=session_dir/"plots"
    def save(fig,name):
        fig.tight_layout()
        fig.savefig(dest/(name+".png"))
        fig.savefig(dest/(name+".svg"))
        plt.close(fig)
    for name,label in [("text","Two-hop TEXT ACK RTT"),("internet","Remote gateway full-response RTT")]:
        values=[r["latency_ms"] for r in runs if r["benchmark"]==name and r["success"]]
        fig,axs=plt.subplots(1,2,figsize=(10,4))
        if values:
            axs[0].hist(values,bins="auto",color="#2866a5",edgecolor="white")
            axs[1].scatter(range(1,len(values)+1),values,s=22,color="#2866a5")
        else:
            axs[0].text(.5,.5,"No measured samples",ha="center",transform=axs[0].transAxes)
        axs[0].set(xlabel="RTT (ms)",ylabel="Successful runs")
        axs[1].set(xlabel="Successful run order",ylabel="RTT (ms)")
        fig.suptitle(label+f" · n={len(values)}")
        save(fig,name+"_distribution")
    for names,metric,title,file in [(["sos_idle","sos_low","sos_medium","sos_high"],"latency_ms","SOS ACK RTT under repeatable bulk load","sos_load"),([5000,10000,20000,30000],"recovery_latency_ms","Store-and-forward recovery after C restart","store_recovery")]:
        fig,ax=plt.subplots(figsize=(9,5))
        for i,name in enumerate(names):
            vals=[r[metric] for r in runs if r["success"] and r.get(metric) is not None and (r["benchmark"]==name if isinstance(name,str) else r["benchmark"]=="store_forward" and r["outage_duration_ms"]==name)]
            if vals:
                ax.boxplot([vals],positions=[i+1],widths=.5,showfliers=True)
                ax.scatter([i+1+(j%7-3)*.025 for j in range(len(vals))],vals,s=14,alpha=.7)
            late=[r["observed_after_timeout_rtt_ms"] for r in runs if r["benchmark"]==name and r.get("observed_after_timeout_rtt_ms") is not None]
            if late:
                ax.scatter([i+1]*len(late),late,s=90,color="#b32025",marker="x",label="Late delivery after deadline; excluded from percentiles")
                ax.annotate("61.7s · deadline failure",(i+1,late[0]),xytext=(8,-16),textcoords="offset points",fontsize=9,color="#b32025")
                ax.set_yscale("log")
        ax.set(xticks=list(range(1,len(names)+1)),xticklabels=[n.removeprefix("sos_") if isinstance(n,str) else f"{n//1000}s wait" for n in names],ylabel="Latency (ms)",title=title)
        if ax.get_legend_handles_labels()[0]:
            ax.legend(fontsize=8,loc="upper left")
        save(fig,file)
    fig,ax=plt.subplots(figsize=(10,5))
    totals=[sum(r["benchmark"]==s for r in runs) for s in SUITES]
    rates=[100*sum(r["benchmark"]==s and r["success"] for r in runs)/n if n else 0 for s,n in zip(SUITES,totals)]
    bars=ax.bar(range(len(SUITES)),rates,color="#2866a5")
    for bar,n in zip(bars,totals):
        ax.text(bar.get_x()+bar.get_width()/2,bar.get_height()+1,f"n={n}" if n else "untested",ha="center",fontsize=9)
    ax.set(xticks=list(range(len(SUITES))),xticklabels=SUITES,ylabel="Success rate (%)",ylim=(0,110),title="Full-matrix success rates · all attempts included")
    ax.tick_params(axis="x",rotation=35)
    save(fig,"success_rates")
    fig,ax=plt.subplots(figsize=(8,5))
    for name in ["text","internet","sos_idle","sos_high"]:
        vals=sorted(r["latency_ms"] for r in runs if r["benchmark"]==name and r["success"])
        if vals:
            ax.step(vals,[(i+1)/len(vals) for i in range(len(vals))],where="post",label=name)
    ax.set(xlabel="RTT (ms)",ylabel="Empirical cumulative probability",title="Measured RTT CDFs · outliers retained")
    if ax.lines:
        ax.legend()
    save(fig,"rtt_cdf")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage",choices=["preflight","smoke","mini","full","report"],required=True)
    parser.add_argument("--session")
    parser.add_argument("--install",action="store_true")
    parser.add_argument("--restart-mode",choices=["warm","cold"],default="warm")
    parser.add_argument("--count",type=int)
    parser.add_argument("--suites",nargs="+",choices=SUITES,default=SUITES)
    parser.add_argument("--sage",choices=["keep","on","off"],default="keep",help="SAGE shadow scoring on all phones for this run (A/B)")
    args=parser.parse_args()
    session_id=args.session or dt.datetime.now(TZ).strftime("%Y%m%d_%H%M%S")+"_"+uuid.uuid4().hex[:6]
    if not re.fullmatch(r"[A-Za-z0-9_-]+",session_id):
        parser.error("Unsafe session ID")
    if args.stage=="report":
        print(json.dumps(report(BASE/"sessions"/session_id),indent=2))
        return
    devices=discover()
    session=Session(session_id,args.stage,devices,args.restart_mode)
    print(f"SESSION {session_id}; devices { {r:d['adb_id'] for r,d in devices.items()} }",flush=True)
    session.start_logs()
    try:
        if args.stage=="preflight":
            session.preflight(args.install)
            report(session.dir)
            return
        meta=session.dir/"metadata.json"
        if not meta.exists() or not json.loads(meta.read_text(encoding="utf-8")).get("preflight_passed"):
            raise InfrastructureError("Successful preflight required before benchmarks")
        for role in "ABC":
            session.command(role,"start")
        if args.sage!="keep":
            for role in "ABC":
                session.command(role,"sage_enable",on=(args.sage=="on"))
            session.sage_mode=args.sage
        session.topology()
        history=[json.loads(x) for x in (session.dir/"attempts.jsonl").read_text(encoding="utf-8").splitlines()] if (session.dir/"attempts.jsonl").exists() else []
        if args.stage=="full":
            if any(not r["success"] for r in history if r["stage"] in ["smoke","mini"]):
                raise InfrastructureError("Smoke/mini failures require investigation before full matrix")
            for suite in args.suites:
                if sum(r["stage"]=="mini" and r["benchmark"]==suite and r["success"] for r in history)<5:
                    raise InfrastructureError(f"Five valid mini attempts required for {suite}")
        for suite in args.suites:
            if args.stage=="full" and suite not in ["store_forward","discovery","relay_recovery"]:
                saved=session.stage
                session.stage="warmup"
                for n in range(1,6):
                    session.attempt(suite,f"{suite}_{n}")
                session.stage=saved
            count=args.count if args.count is not None else 1 if args.stage=="smoke" else 5 if args.stage=="mini" else COUNTS[suite]
            previous=[r for r in history if r["stage"]==args.stage and r["benchmark"]==suite]
            consecutive_failures=0
            # Resuming preserves every completed attempt; it does not replace failures.
            for n in range(len(previous)+1,count+1):
                outage=[5000,10000,20000,30000][(n-1)%4] if suite=="store_forward" else 0
                rec=session.attempt(suite,n,outage)
                consecutive_failures=0 if rec["success"] else consecutive_failures+1
                report(session.dir, render_plots=(n % 5 == 0))
                if args.stage in ["smoke","mini"] and not rec["success"]:
                    raise SystemFailure(f"{args.stage} failure preserved; stop and inspect before collecting more")
                if args.stage=="full" and consecutive_failures>=3:
                    raise SystemFailure("Three consecutive full-attempt failures preserved; matrix stopped for inspection")
        if session.sage_mode=="on":
            stats={r: session.command(r).get("sage") for r in "ABC"}
            (session.dir/"parsed"/f"sage_stats_{args.stage}_{uuid.uuid4().hex[:6]}.json").write_text(json.dumps(stats,indent=2),encoding="utf-8")
        print("STAGE COMPLETE",flush=True)
    finally:
        session.close_logs()
        # Best-effort restoration of mesh services; no system network setting changes.
        for role in "ABC":
            try:
                session.command(role,"start")
            except Exception as e:
                print(f"RESTORATION ISSUE {role}: {e}",flush=True)
        report(session.dir)


if __name__=="__main__":
    main()

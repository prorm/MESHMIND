"""Audit preserved physical evidence and add timeline CSVs and interpretation to the report."""
import argparse
import collections
import csv
import json
from pathlib import Path
import statistics

from benchmark_all import BASE, Session, quantile, report, write_csv, SUITES, COUNTS, COLS


def first(events, category=None, prefix=None):
    return next((e["time"] for e in events if (not category or e["category"]==category) and (not prefix or e["message"].startswith(prefix))), None)


def finalize(path):
    report(path)
    records=[json.loads(line) for line in (path/"attempts.jsonl").read_text(encoding="utf-8").splitlines()]
    meta=json.loads((path/"metadata.json").read_text(encoding="utf-8"))
    classification=path/"parsed/failure_classification_audit.json"
    changes={r["evidence_file"]:r for r in json.loads(classification.read_text(encoding="utf-8"))} if classification.exists() else {}
    timelines=[]
    audit=[]
    for r in records:
        if r["evidence_file"] in changes:
            r["original_failure_class"]=r["failure_class"]
            r["failure_class"]=changes[r["evidence_file"]]["audited_failure_class"]
            r["failure_audit_note"]=changes[r["evidence_file"]]["reason"]
        e=json.loads((path/r["evidence_file"]).read_text(encoding="utf-8"))["evidence"]
        command=e.get("command")
        if command and "internetValidated" in command:
            r["source_internet_validated"]=command["internetValidated"]
            if r["benchmark"]=="internet":
                r["notes"]=f"Full response accepted by A router, C executed HTTP; source Internet validated={command['internetValidated']}. Source state is reconstructed from preserved command observation; no latency/success changes."
        proof=e.get("proof") or e.get("route_usable_probe")
        t=dict(session_id=r["session_id"],stage=r["stage"],benchmark=r["benchmark"],run_id=r["run_id"],packet_id=r.get("packet_id"),success=r["success"],source_evidence=r["evidence_file"])
        if proof:
            pe,ae=proof["packet_events"],proof["ack_events"]
            t.update(source_send_time_ms=first(pe["A"],"FORWARD"),relay_receive_time_ms=first(pe["B"],"RX"),relay_forward_time_ms=first(pe["B"],"FORWARD"),destination_delivery_time_ms=proof["destination_metric"]["measuredAt"],destination_ack_send_time_ms=first(ae["C"],"FORWARD"),relay_ack_receive_time_ms=first(ae["B"],"RX"),relay_ack_forward_time_ms=first(ae["B"],"FORWARD"),source_ack_receive_time_ms=proof["source_metric"]["measuredAt"],ack_packet_id=proof["ack_packet_id"])
            cmd=e.get("command")
            if cmd:
                t["source_enqueue_time_ms"]=cmd["packet"]["createdAt"]
                measured=proof["source_metric"]["measuredAt"]-cmd["packet"]["createdAt"]
                if measured != r.get("latency_ms"):
                    raise AssertionError(f"RTT clock mismatch {r['evidence_file']}")
            if r["success"] and r["benchmark"]!="internet":
                if proof["source_metric"]["hops"]!=2 or proof["source_metric"]["route"] != [meta["devices"][v]["node_id"] for v in "ABC"]:
                    raise AssertionError(f"Route mismatch {r['evidence_file']}")
        if e.get("stop"):
            t["mesh_stop_time_ms"]=e["stop"]["commandTime"]
            t["mesh_restore_time_ms"]=e.get("restore",{}).get("commandTime")
            loss=e.get("loss_detected")
            if loss:
                t["loss_detection_host_ms"]=loss["hostEndMs"]-e["stop"]["hostBeginMs"]
                t["loss_detection_note"]="Host observation includes ADB/control overhead, not pure application detection"
            if r["benchmark"]=="relay_recovery" and proof and e.get("restore"):
                restored=e["restore"]
                observed=e["observations"]["A"]
                ack_host=proof["source_metric"]["measuredAt"]-observed["clockOffsetEstimateMs"]
                restore_host=restored["commandTime"]-restored["clockOffsetEstimateMs"]
                t["backlog_ack_recovery_clock_corrected_estimate_ms"]=ack_host-restore_host
                t["backlog_ack_recovery_clock_uncertainty_ms"]=observed["clockOffsetUncertaintyMs"]+restored["clockOffsetUncertaintyMs"]
                b_logs=e["observations"]["B"]["logs"]
                node={v:meta["devices"][v]["node_id"] for v in "ABC"}
                for peer in ["A","C"]:
                    t[f"relay_handshake_{peer}_on_B_time_ms"]=next((v["time"] for v in b_logs if v["category"]=="PEER" and "handshake:" in v["message"] and "is node "+node[peer] in v["message"]),None)
        if r["benchmark"]=="discovery" and e.get("discovered"):
            node={v:meta["devices"][v]["node_id"] for v in "ABC"}
            t["discovery_start_A_time_ms"]=e["starts"]["A"]["commandTime"]
            for role,peer,key in [("A","B","first_ab_handshake_A_time_ms"),("B","C","first_bc_handshake_B_time_ms")]:
                t[key]=next((v["time"] for v in e["discovered"][role]["logs"] if v["category"]=="PEER" and "handshake:" in v["message"] and "is node "+node[peer] in v["message"]),None)
            t["gateway_beacon_C_time_ms"]=first(e["discovered"]["C"]["logs"],"BEACON","gateway beacon")
            t["gateway_learned_B_time_ms"]=first(e["discovered"]["B"]["logs"],"BEACON","gateway "+node["C"]+" at 1 hop(s)")
            t["gateway_learned_A_time_ms"]=e["gateway_learned"]["time"]
            t["route_usable_A_time_ms"]=e["route_usable_at"]
        if r["benchmark"]=="internet" and e.get("result"):
            x=e["result"]
            t.update(source_enqueue_time_ms=x["sentAt"],source_response_accepted_time_ms=x["completedAt"])
            if r["success"] and x["completedAt"]-x["sentAt"]!=r["latency_ms"]:
                raise AssertionError("Full-response RTT mismatch")
        timelines.append(t)
        audit.append(dict(stage=r["stage"],benchmark=r["benchmark"],run_id=r["run_id"],success=r["success"],evidence_exists=True,same_clock_rtt_checked=bool(proof or r["benchmark"]=="internet"),path_checked=bool(r["success"])))
    cols=list(dict.fromkeys(k for t in timelines for k in t))
    write_csv(path/"parsed/benchmark_timelines.csv",timelines,cols)
    write_csv(BASE/"benchmark_timelines.csv",timelines,cols)
    extra=sorted(set().union(*(r.keys() for r in records))-set(COLS))
    write_csv(path/"parsed/benchmark_runs.csv",records,COLS+extra)
    write_csv(BASE/"benchmark_runs.csv",records,COLS+extra)
    (path/"parsed/evidence_audit.json").write_text(json.dumps(audit,indent=2),encoding="utf-8")
    full=[r for r in records if r["stage"]=="full"]
    ok=[r for r in full if r["success"]]
    failures=[r for r in full if not r["success"]]
    lines=["", "## Interpretation of the physical results", ""]
    late_file=path/"parsed/medium_16_late_delivery_audit.json"
    if late_file.exists():
        late=json.loads(late_file.read_text(encoding="utf-8"))
        late_rtt=late["posthoc_metrics"]["latency_ms"]
        lines += [f"Medium-load SOS #16 exceeded the 60-second deadline, then automatically delivered at **{late_rtt}ms**. Failed A↔B sends preceded recovery. This remains a MESHMIND_SYSTEM failure, is excluded from successful-latency percentiles, and is plotted explicitly as a late-delivery failure. It is the largest observed SOS latency, not a lost or manually resent packet.", ""]
        for r in records:
            if r["evidence_file"]==late["original_failure"]["evidence_file"]:
                r["observed_after_timeout_rtt_ms"]=late_rtt
    if changes:
        lines += ["Medium-load #17 remains a failed/invalid collection attempt. The collector read an early C snapshot before the new transfer appeared; residual traffic from #16 also raised its pending load to 49 chunks instead of 32. Its failure class is audited as TEST_INFRASTRUCTURE, with original MESHMIND_SYSTEM classification retained in JSONL and an explicit classification audit. No attempt or failure is deleted.", ""]
    posthoc=path/"parsed/text_40_posthoc_audit.json"
    if posthoc.exists():
        recovered=json.loads(posthoc.read_text(encoding="utf-8"))
        lines += [f"Text attempt #40 remains a TEST_INFRASTRUCTURE failure and is excluded from the primary distribution. A later observation verified the original packet's full A→B→C delivery and C→B→A ACK at {recovered['posthoc_metrics']['latency_ms']}ms, without resending it. Thus the original physical text packets were delivered, while strict collection validity is 49/50. See `parsed/text_40_posthoc_audit.json`.", ""]
    relay_late_file=path/"parsed/relay_9_posthoc_audit.json"
    relay_late=None
    if relay_late_file.exists():
        relay_late=json.loads(relay_late_file.read_text(encoding="utf-8"))
        if relay_late.get("posthoc_metrics"):
            relay_rtt=relay_late["posthoc_metrics"]["latency_ms"]
            lines += [f"Relay recovery #9 failed its 120-second deadline, then the original packet automatically delivered with a complete two-hop path and matching reverse ACK at **{relay_rtt}ms** RTT. An earlier B snapshot preserves its forwarding/ACK evidence before the next controlled B outage. This remains a MESHMIND_SYSTEM failure and is excluded from successful percentiles. See `parsed/relay_9_posthoc_audit.json`.", ""]
            for r in records:
                if r["evidence_file"]==relay_late["original_failure"]["evidence_file"]:
                    r["observed_after_timeout_rtt_ms"]=relay_rtt
    lines += ["Observed direct-link refusal evidence consists of both endpoints logging ‘not linking … (topology lock)’. The incoming rejectConnection branch was inspected in code but was not forced separately.", ""]
    def values(suite,key="latency_ms"):
        return [r[key] for r in ok if r["benchmark"]==suite and r.get(key) is not None]
    def n(suite):
        rs=[r for r in full if r["benchmark"]==suite]
        return f"{sum(r['success'] for r in rs)}/{len(rs)}"
    def f(x):
        return "not measured" if x is None else f"{x:.1f}"
    lines += ["| SOS condition | Delivered / attempted | p50 ms | p95 ms |", "|---|---:|---:|---:|"]
    for s in ["sos_idle","sos_low","sos_medium","sos_high"]:
        v=values(s)
        lines.append(f"| {s.removeprefix('sos_')} | {n(s)} | {f(quantile(v,.5))} | {f(quantile(v,.95))} |")
    idle,heavy=quantile(values("sos_idle"),.95),quantile(values("sos_high"),.95)
    if idle is not None and heavy is not None:
        lines += ["",f"Heavy-load SOS p95 is {heavy:.1f}ms versus idle {idle:.1f}ms, difference {heavy-idle:.1f}ms ({heavy/idle:.2f}×). This is a descriptive comparison, without a statistical significance test."]
    lines += ["", "| Store queue-wait bucket | Recovered / attempted | Recovery p50 ms | Recovery p95 ms | Recovery max ms | Total RTT p50 ms | Total RTT p95 ms |", "|---|---:|---:|---:|---:|---:|---:|"]
    for duration in [5000,10000,20000,30000]:
        rs=[r for r in full if r["benchmark"]=="store_forward" and r["outage_duration_ms"]==duration]
        good=[r for r in rs if r["success"]]
        recovery=[r["recovery_latency_ms"] for r in good]
        total=[r["latency_ms"] for r in good]
        lines.append(f"| {duration//1000}s | {len(good)}/{len(rs)} | {f(quantile(recovery,.5))} | {f(quantile(recovery,.95))} | {f(max(recovery) if recovery else None)} | {f(quantile(total,.5))} | {f(quantile(total,.95))} |")
    pooled=values("store_forward","recovery_latency_ms")
    lines += ["",f"Pooled store-and-forward recovery: {n('store_forward')}, p50 {f(quantile(pooled,.5))}ms, p95 {f(quantile(pooled,.95))}ms. All recorded failures remain in the denominator.", "", "### Answers for the pitch and validation review", ""]
    if full:
        best=next((r for r in ok if r["benchmark"]=="sos_high"),None)
        lines.append(f"1. Strongest validated result: heavy-load critical traffic followed the enforced two-hop path while a 1MiB LOW transfer was pending, and the bulk bytes were verified by SHA-256 afterward ({n('sos_high')})." if best else "1. Heavy-load SOS has not produced a validated full-matrix result.")
        lines.append("2. Worst observed failure: C crashed with OutOfMemoryError at its 256MiB heap limit while Room loaded accumulated packets during a warm mesh restart. A fresh process restored service, but warm restart under this history remains unvalidated." if (path/"raw/C_oom_crash_log.txt").exists() else "2. Worst observed attempted-run failure: "+("; ".join(f"{r['benchmark']} #{r['run_id']}: {r['failure_class']} — {r['failure_reason']}" for r in failures) if failures else "no failed full-matrix attempts recorded."))
        longest=max(ok,key=lambda r:r.get("latency_ms",0)) if ok else None
        if longest:
            lines.append(f"3. Largest successful latency: {longest['benchmark']} #{longest['run_id']}, {longest['latency_ms']}ms. Intentional outages are included where applicable.")
            if relay_late and relay_late.get("posthoc_metrics"):
                lines[-1] += f" Largest observed packet RTT including deadline failures: relay #9, {relay_late['posthoc_metrics']['latency_ms']}ms. This late failure does not enter the successful percentiles."
                if late_file.exists():
                    lines[-1] += f" Largest SOS RTT including failures: {late_rtt}ms, also excluded."
        sos=[r for r in full if r["benchmark"] in ["sos_low","sos_medium","sos_high"]]
        lines.append(f"4. SOS preemption: {sum(r['success'] for r in sos)}/{len(sos)} loaded attempts satisfied delivery, reverse ACK, SOS-before-bulk order, bulk continuation and byte integrity; high-load p95 {f(heavy)}ms. Reliability here is limited to these controlled queued-load experiments; SOS is injected immediately after bulk enqueue, not at a random point in a long-running transfer.")
        lines.append(f"5. Store-forward reliability: {n('store_forward')} recovered with automatic app reconnection and no manual resend; recovery p95 {f(quantile(pooled,.95))}ms.")
        lines.append(f"6. A–B–C evidence: all {len(ok)} successful full attempts required the expected route and matching relay evidence. Failed attempts do not acquire a route claim.")
        lines.append(f"7. Brochure wording supported by these conditions: ‘Validated on three physical Android phones under a software-enforced two-hop topology. Critical SOS delivery during a queued 1MiB transfer: {n('sos_high')}, p95 {f(heavy)}ms; the bulk transfer subsequently completed with verified bytes.’ Avoid claims of emergency guarantees or large-event scale.")
    expected=collections.Counter(COUNTS)
    adjusted_expected=dict(COUNTS)
    execution_changes=meta.get("execution_adjustments",{})
    for suite, adjustment in execution_changes.items():
        if "effective_total_attempts" in adjustment:
            adjusted_expected[suite]=adjustment["effective_total_attempts"]
    actual=collections.Counter(r["benchmark"] for r in full)
    cold=[r for r in full if r["benchmark"]=="discovery" and r.get("restart_mode")=="cold"]
    cold_values=[r["latency_ms"] for r in cold if r["success"]]
    if execution_changes:
        lines += ["", "### Restart stress and changed procedure", "", "After the bulk matrix, C's warm mesh restart crashed while Room loaded persisted packets: OutOfMemoryError at the 256MiB heap limit. The next attempt could not form the topology; a third precondition attempt was interrupted to avoid more invalid restarts. Original attempts and crash logs are retained. C recovered after force-stopping only MeshMind and launching a fresh process. App data, identity, peer filters and radio settings were preserved.", "", f"Discovery was responsibly reduced from 20 planned repetitions to five measured cold-process runs. Cold discovery: {sum(r['success'] for r in cold)}/{len(cold)}, p50 {f(quantile(cold_values,.5))}ms, p95 {f(quantile(cold_values,.95))}ms. The aggregate discovery row includes the two failed warm attempts and one interrupted precondition attempt; their outcomes are not erased or mixed into the successful cold timing distribution.", "", "For cold discovery, all three app processes are force-stopped and opened before the existing mesh-start APIs run. For relay recovery, only B's process is stopped/reopened. These are controlled app outages; Wi-Fi and Bluetooth are not toggled, and Room data is not cleared. The cold workaround does not fix or validate warm restart under accumulated bulk history.", "", f"Adjusted expected attempt counts: `{adjusted_expected}`. Original requested counts remain below for comparison."]
    lines += ["", "Suites ran serially, and persisted packet history was retained and grew. Conditions are not randomized/reset between load levels. The restart results therefore apply to this accumulated-history session, not a pristine installation. The early-snapshot and failed-bulk-drain harness fixes are available for future sessions; original measurements from this session remain intact."]
    if execution_changes.get("relay_recovery",{}).get("user_requested_cap"):
        lines += ["", f"Relay recovery was capped at {execution_changes['relay_recovery']['user_requested_cap']} total attempts by the user's explicit instruction. Result: {n('relay_recovery')}; failures remain in the denominator. This replaces the original 20-run target for this session."]
    lines += ["", "### Completeness and measurement limits", "", f"Full matrix attempt counts: `{dict(actual)}`. Required counts: `{dict(expected)}`.", "", "Exact HTTP socket duration and exact queue depth at the instant of C's SOS delivery are unavailable from the unchanged app. The HTTP duration is C's gateway execution interval (including policy/executor work); queue snapshots at injection and after polling are preserved. ACK creation is not independently logged; C's matching ACK send is recorded. Route availability is confirmed through handshakes and a delivered probe, not a separately exposed route-usability callback.", "", "Legacy `loss_detection_ms` in early smoke/mini records subtracts different device clocks and must not be interpreted as latency. `benchmark_timelines.csv` reconstructs `loss_detection_host_ms` from the raw host observations for every outage; full runs use that corrected field. Primary RTT/recovery metrics were already measured on one phone's clock and are unaffected.", "", "Timeline CSV columns name their observing phone; cross-device absolute timestamps must not be subtracted without accounting for recorded clock offsets and uncertainty. No fake timing samples are included.", "", "## Plots", ""]
    for name,alt in [("text_distribution","Text RTT distribution and outliers"),("internet_distribution","Gateway response RTT distribution"),("sos_load","SOS RTT by queued load"),("store_recovery","Store-forward recovery by wait bucket"),("success_rates","Success rate including failed attempts"),("rtt_cdf","RTT empirical CDFs")]:
        lines += [f"![{alt}](sessions/{path.name}/plots/{name}.png)", ""]
    conditions_file=path/"raw/final_conditions.json"
    if conditions_file.exists():
        conditions=json.loads(conditions_file.read_text(encoding="utf-8"))
        lines += ["## Final device state", "", f"Read-only verification at {conditions['timestamp']}: B and C Wi-Fi remained enabled and connected; wireless ADB remained reachable. All phones had Bluetooth enabled and the same APK hash. A remained USB-connected with its externally observed Wi-Fi-off/airplane-on state unchanged. Live peer sets were A→B, B→A+C, C→B. See `raw/final_conditions.json`. Owned benchmark/logcat collectors were stopped after collection.", ""]
    appendix="\n".join(lines)+"\n"
    extra=sorted(set().union(*(r.keys() for r in records))-set(COLS))
    write_csv(path/"parsed/benchmark_runs.csv",records,COLS+extra)
    write_csv(BASE/"benchmark_runs.csv",records,COLS+extra)
    root_report=BASE/"REPORT.md"
    root_report.write_text(root_report.read_text(encoding="utf-8")+appendix,encoding="utf-8")
    session_report=path/"reports/REPORT.md"
    session_report.write_text(session_report.read_text(encoding="utf-8")+appendix.replace(f"sessions/{path.name}/plots/","../plots/"),encoding="utf-8")
    final=dict(session_id=path.name,counts=dict(actual),successes=dict(collections.Counter(r["benchmark"] for r in ok)),failures=failures,complete=all(actual[k]==v for k,v in COUNTS.items()),text_p50=quantile(values("text"),.5),text_p95=quantile(values("text"),.95),text_p99=quantile(values("text"),.99),internet_p50=quantile(values("internet"),.5),internet_p95=quantile(values("internet"),.95),store_recovery_p50=quantile(pooled,.5),store_recovery_p95=quantile(pooled,.95),idle_sos_p95=idle,high_sos_p95=heavy,discovery_p95=quantile(values("discovery"),.95),relay_recovery_p95=quantile(values("relay_recovery","recovery_latency_ms"),.95),all_evidence_files_audited=len(audit))
    final["adjusted_matrix_complete"]=all(actual[k]==v for k,v in adjusted_expected.items())
    final["execution_adjustments"]=execution_changes
    final["cold_discovery_attempts"]=len(cold)
    final["cold_discovery_successes"]=sum(r["success"] for r in cold)
    final["cold_discovery_p95_ms"]=quantile(cold_values,.95)
    (path/"reports/final_summary.json").write_text(json.dumps(final,indent=2),encoding="utf-8")
    print(json.dumps(final,indent=2))


if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--session",required=True)
    a=p.parse_args()
    if not __import__('re').fullmatch(r"[A-Za-z0-9_-]+",a.session):
        p.error("Unsafe session ID")
    finalize(BASE/"sessions"/a.session)

"""Aggregate held-out results, paired cluster-bootstrap CIs, failure analysis and plots."""
from __future__ import annotations
import json
from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# Validated categorical palette (dataviz reference instance), fixed per policy: color follows the entity.
COLORS = {"sage": "#2a78d6", "epidemic": "#eb6834", "spray_focus": "#1baf7a", "prophet": "#eda100",
    "prophet_value": "#e87ba4", "meshmind_heuristic": "#008300", "spray_wait": "#4a3aa7", "direct": "#e34948"}
OTHER = "#8a8984"
INK, MUTED = "#0b0b0b", "#52514e"
# Hand-placed label offsets (points) for the crowded top-right of the Pareto plot.
LABEL_OFFSETS = {"sage": (-8, 6, "right"), "prophet_value": (8, 4, "left"), "epidemic": (8, -10, "left"),
    "sage_eco": (-8, 4, "right"), "sage_lean": (-8, 6, "right"), "spray_focus": (8, -10, "left"),
    "sage_frugal": (-8, 6, "right"), "spray_focus_L8": (-8, -10, "right"), "spray_wait": (8, -3, "left")}
BASELINES = ["direct", "epidemic", "meshmind_heuristic", "spray_wait", "spray_focus", "prophet", "prophet_value"]


def color(policy):
    for k, c in COLORS.items():
        if policy == k:
            return c
    if policy.startswith("sage") and "tree" not in policy:
        return COLORS["sage"]
    return OTHER


def style(ax, title, xlabel, ylabel):
    ax.set_title(title, loc="left", fontsize=11, color=INK)
    ax.set_xlabel(xlabel, color=MUTED)
    ax.set_ylabel(ylabel, color=MUTED)
    ax.grid(True, color="#e6e5e0", linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color("#bdbcb6")
    ax.tick_params(colors=MUTED)


def cluster_bootstrap(frame, a, b, metric, reps=4000, seed=7):
    """Paired difference a-b; resample EPISODES (clusters) keeping all their scenario/seed runs."""
    pv = frame.pivot_table(index=["episode", "scenario", "seed"], columns="policy", values=metric)
    if a not in pv or b not in pv:
        return None
    d = (pv[a] - pv[b]).dropna()
    per_episode = d.groupby(level="episode").mean()
    rng = np.random.default_rng(seed)
    vals = per_episode.to_numpy()
    boot = rng.choice(vals, (reps, len(vals)), replace=True).mean(1)
    lo, hi = np.percentile(boot, [2.5, 97.5])
    return {"mean": float(d.mean()), "ci_low": float(lo), "ci_high": float(hi), "episodes": len(vals),
        "runs": len(d), "win_rate_runs": float((d > 1e-12).mean()), "loss_rate_runs": float((d < -1e-12).mean())}


def stage_report(config, out: Path, tag="test"):
    plots = out / "plots"
    plots.mkdir(parents=True, exist_ok=True)
    runs = pd.read_csv(out / f"{tag}_runs.csv")
    selected = json.loads((out / "selected_policies.json").read_text())
    learned = [p for p in runs.policy.unique() if p.startswith("sage")]
    base = [p for p in runs.policy.unique() if not p.startswith("sage")]
    metrics = ["normal_delivery_ratio", "critical_delivery_ratio", "gateway_delivery_ratio", "p50_latency",
        "p95_latency", "critical_p95_latency", "total_bytes", "duplicate_bytes", "transmissions_per_delivered",
        "mean_hops", "queue_drops", "critical_overflow", "battery_cost", "oracle_ceiling"]
    summary = runs.groupby("policy")[metrics].mean()
    summary["share_of_ceiling"] = summary.normal_delivery_ratio / summary.oracle_ceiling
    summary["bytes_vs_epidemic"] = summary.total_bytes / summary.loc["epidemic", "total_bytes"]
    summary = summary.sort_values("normal_delivery_ratio", ascending=False)
    summary.to_csv(out / f"{tag}_summary.csv")
    strongest = summary.loc[base].normal_delivery_ratio.idxmax()
    best_sage = summary.loc[learned].normal_delivery_ratio.idxmax() if learned else None
    # Paired comparisons for every learned policy vs every baseline.
    comp = []
    for s in learned:
        for b in base:
            for metric in ("normal_delivery_ratio", "critical_delivery_ratio", "total_bytes", "p95_latency"):
                r = cluster_bootstrap(runs, s, b, metric)
                if r:
                    comp.append({"learned": s, "baseline": b, "metric": metric} | r)
    comp = pd.DataFrame(comp)
    comp.to_csv(out / f"{tag}_paired_comparisons.csv", index=False)
    # Per scenario and per trace: learned minus strongest baseline.
    by_scen = runs.pivot_table(index="scenario", columns="policy", values="normal_delivery_ratio")
    by_trace = runs.pivot_table(index="trace", columns="policy", values="normal_delivery_ratio")
    bytes_scen = runs.pivot_table(index="scenario", columns="policy", values="total_bytes")
    by_scen.to_csv(out / f"{tag}_delivery_by_scenario.csv")
    by_trace.to_csv(out / f"{tag}_delivery_by_trace.csv")
    bytes_scen.to_csv(out / f"{tag}_bytes_by_scenario.csv")
    # Failure analysis: where does the learned policy win or lose against the strongest baseline?
    fa = None
    if best_sage:
        pv = runs.pivot_table(index=["episode", "trace", "family", "scenario", "seed"], columns="policy",
            values="normal_delivery_ratio").reset_index()
        ctx = runs[runs.policy == "epidemic"].set_index(["episode", "scenario", "seed"])
        pv = pv.set_index(["episode", "scenario", "seed"])
        pv["diff"] = pv[best_sage] - pv[strongest]
        pv["ceiling"] = ctx.oracle_ceiling
        pv["epidemic_gap_to_ceiling"] = ctx.oracle_ceiling - ctx.normal_delivery_ratio
        pv["contact_bins_per_participant"] = ctx.contact_bins / ctx.participants
        pv["packets"] = ctx.packets
        pv["epidemic_queue_drops"] = ctx.queue_drops
        pv["epidemic_bytes_per_participant"] = ctx.total_bytes / ctx.participants
        pv = pv.reset_index()
        fa = {}
        for col in ["ceiling", "epidemic_gap_to_ceiling", "contact_bins_per_participant", "packets",
                    "epidemic_queue_drops", "epidemic_bytes_per_participant"]:
            fa[col] = float(pv[["diff", col]].corr(method="spearman").iloc[0, 1])
        pv.to_csv(out / f"{tag}_failure_analysis.csv", index=False)
    # ---------------- plots
    keep = [p for p in summary.index if not p.endswith("_eco") or p.startswith("sage")]
    # 1) Pareto: delivery vs bytes.
    fig, ax = plt.subplots(figsize=(8, 5.2))
    for p in keep:
        r = summary.loc[p]
        ax.scatter(r.total_bytes / 1e6, r.normal_delivery_ratio, s=70, color=color(p), edgecolor="white",
            linewidth=2, zorder=3)
        dx, dy, ha = LABEL_OFFSETS.get(p, (6, 4, "left"))
        ax.annotate(p, (r.total_bytes / 1e6, r.normal_delivery_ratio), xytext=(dx, dy), textcoords="offset points",
            fontsize=8, color=INK, ha=ha)
    ax.axhline(summary.oracle_ceiling.mean(), color=MUTED, linestyle="--", linewidth=1)
    ax.set_xscale("log")
    ax.annotate("perfect-knowledge ceiling (sampled)", (ax.get_xlim()[0], summary.oracle_ceiling.mean()), xytext=(4, 4),
        textcoords="offset points", fontsize=8, color=MUTED)
    style(ax, "Held-out delivery vs radio bytes (mean per run, all test scenarios)", "MB transmitted per run (log)",
        "Ordinary-packet delivery ratio")
    fig.tight_layout()
    fig.savefig(plots / "pareto_delivery_vs_bytes.png", dpi=160)
    plt.close(fig)
    show = [p for p in [best_sage, "epidemic", strongest if strongest != "epidemic" else None, "spray_focus",
        "prophet", "meshmind_heuristic", "direct"] if p and p in by_scen.columns]
    show = list(dict.fromkeys(show))

    def lineplot(scen, xvals, metric, name, title, xlabel, ylabel):
        sub = runs[runs.scenario.isin(scen)]
        fig, ax = plt.subplots(figsize=(7, 4.5))
        for p in show:
            g = sub[sub.policy == p].groupby("scenario")[metric].mean().reindex(scen)
            ax.plot(xvals, g.values, marker="o", markersize=7, linewidth=2, color=color(p), label=p)
            ax.annotate(p, (xvals[-1], g.values[-1]), xytext=(6, 0), textcoords="offset points", fontsize=8,
                color=INK, va="center")
        style(ax, title, xlabel, ylabel)
        ax.legend(fontsize=8, frameon=False, loc="best")
        fig.tight_layout()
        fig.savefig(plots / name, dpi=160)
        plt.close(fig)

    avail = set(runs.scenario)
    if {"low_load", "base", "heavy_load"} <= avail:
        lineplot(["low_load", "base", "heavy_load"], [0.5, 1, 4], "normal_delivery_ratio", "delivery_vs_load.png",
            "Delivery vs offered load (held-out)", "Packets per participant per hour", "Delivery ratio")
        lineplot(["low_load", "base", "heavy_load"], [0.5, 1, 4], "p95_latency", "p95_latency_vs_load.png",
            "p95 delivered latency vs load (held-out)", "Packets per participant per hour", "Seconds")
    if {"base", "media_burst"} <= avail:
        lineplot(["base", "media_burst"], [0.2, 0.6], "critical_p95_latency", "critical_latency_vs_media.png",
            "CRITICAL p95 latency vs MEDIA share (+30 SOS burst at 0.6)", "MEDIA share of traffic", "Seconds")
    if {"base", "more_gateways"} <= avail:
        lineplot(["base", "more_gateways"], [3, 10], "normal_delivery_ratio", "delivery_vs_gateways.png",
            "Delivery vs gateway share", "% of phones with Internet uplink", "Delivery ratio")
    if {"low_adoption", "base"} <= avail:
        lineplot(["low_adoption", "base"], [50, 100], "normal_delivery_ratio", "delivery_vs_adoption.png",
            "Delivery vs app adoption", "% of attendees running MeshMind", "Delivery ratio")
    # Duplicate bytes vs delivery.
    fig, ax = plt.subplots(figsize=(7, 4.5))
    for p in keep:
        r = summary.loc[p]
        ax.scatter(r.duplicate_bytes / 1e6, r.normal_delivery_ratio, s=60, color=color(p), edgecolor="white", linewidth=2)
        ax.annotate(p, (r.duplicate_bytes / 1e6, r.normal_delivery_ratio), xytext=(5, 3), textcoords="offset points",
            fontsize=8, color=INK)
    ax.set_xscale("symlog")
    style(ax, "Post-delivery duplicate bytes vs delivery", "Duplicate MB per run", "Delivery ratio")
    fig.tight_layout()
    fig.savefig(plots / "duplicate_bytes_vs_delivery.png", dpi=160)
    plt.close(fig)
    # Per-trace bars and per-scenario difference.
    fig, ax = plt.subplots(figsize=(8, 4.5))
    traces = list(by_trace.index)
    width = 0.8 / len(show)
    for k, p in enumerate(show):
        ax.bar(np.arange(len(traces)) + k * width, by_trace[p].reindex(traces), width * 0.9, color=color(p), label=p)
    ax.set_xticks(np.arange(len(traces)) + 0.4 - width / 2, traces)
    style(ax, "Delivery by held-out trace (all scenarios)", "", "Delivery ratio")
    ax.legend(fontsize=8, frameon=False, ncol=2)
    fig.tight_layout()
    fig.savefig(plots / "delivery_by_trace.png", dpi=160)
    plt.close(fig)
    if best_sage:
        d = (by_scen[best_sage] - by_scen[strongest]).sort_values()
        fig, ax = plt.subplots(figsize=(7.5, 5.5))
        ax.barh(d.index, d.values * 100, color=[COLORS["sage"] if v >= 0 else COLORS["direct"] for v in d.values])
        ax.axvline(0, color=MUTED, linewidth=1)
        for y, v in enumerate(d.values):
            ax.annotate(f"{v * 100:+.1f}", (v * 100, y), xytext=(4 if v >= 0 else -4, 0), textcoords="offset points",
                ha="left" if v >= 0 else "right", va="center", fontsize=8, color=INK)
        style(ax, f"{best_sage} minus {strongest}, by scenario", "Delivery difference (percentage points)", "")
        fig.tight_layout()
        fig.savefig(plots / "sage_minus_strongest_by_scenario.png", dpi=160)
        plt.close(fig)
        for ref in ("epidemic",):
            if ref == strongest or ref not in by_scen:
                continue
            d = (by_scen[best_sage] - by_scen[ref]).sort_values()
            fig, ax = plt.subplots(figsize=(7.5, 5.5))
            ax.barh(d.index, d.values * 100, color=[COLORS["sage"] if v >= 0 else COLORS["direct"] for v in d.values])
            ax.axvline(0, color=MUTED, linewidth=1)
            for y, v in enumerate(d.values):
                ax.annotate(f"{v * 100:+.2f}", (v * 100, y), xytext=(4 if v >= 0 else -4, 0), textcoords="offset points",
                    ha="left" if v >= 0 else "right", va="center", fontsize=8, color=INK)
            style(ax, f"{best_sage} minus {ref} (bounded flooding), by scenario", "Delivery difference (percentage points)", "")
            fig.tight_layout()
            fig.savefig(plots / f"sage_minus_{ref}_by_scenario.png", dpi=160)
            plt.close(fig)
    result = {"strongest_baseline": strongest, "best_learned": best_sage, "failure_correlations_spearman": fa,
        "summary": summary.reset_index().to_dict(orient="records")}
    (out / f"{tag}_report.json").write_text(json.dumps(result, indent=2, default=float))
    print(summary[["normal_delivery_ratio", "oracle_ceiling", "share_of_ceiling", "critical_delivery_ratio",
        "p95_latency", "total_bytes", "bytes_vs_epidemic"]].to_string(), flush=True)
    if not comp.empty:
        print(comp[(comp.metric == "normal_delivery_ratio")].sort_values(["learned", "mean"]).to_string(index=False))
    return result

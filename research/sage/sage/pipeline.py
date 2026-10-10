from __future__ import annotations
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import platform
import sys
import time
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from .datasets import download, load, validate, pilot
from .features import FEATURES
from .simulation import Scenario, Replay
from .routing import Policy, BASELINES
from .oracle import TemporalOracle

ROOT = Path(__file__).resolve().parents[1]

def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False), encoding="utf-8")

def run(config_path):
    config = json.loads(config_path.read_text())
    out = ROOT/"outputs"
    for d in ("checkpoints", "metrics", "plots"):
        (out/d).mkdir(parents=True, exist_ok=True)
    write_json(out/"config_used.json", config)
    frames = {}
    domains = config["train_domains"]+[config["validation_domain"], config["test_domain"]]
    if len(set(domains)) != len(domains):
        raise ValueError("Train, validation and test domains must be disjoint")
    stats = {}
    for name in domains:
        raw = load(name, download(name, ROOT/"data/raw"))
        frames[name] = pilot(raw, config["max_nodes"], config["trace_seconds"])
        stats[name] = {"full_trace": validate(raw), "pilot": validate(frames[name])}
        target = ROOT/f"data/processed/{name}.parquet"
        target.parent.mkdir(parents=True, exist_ok=True)
        frames[name].to_parquet(target, index=False)
        print(f"Loaded {name}: {stats[name]['pilot']['nodes']} nodes, {len(frames[name])} bins", flush=True)
    write_json(out/"metrics/datasets.json", stats)
    tables = {}
    for name in config["train_domains"]+[config["validation_domain"]]:
        rows = []
        for seed in config["label_seeds"]:
            scenario = Scenario(**config["base"], label_limit=config["label_limit_per_run"])
            replay = Replay(frames[name], scenario, seed)
            oracle = TemporalOracle(replay.contacts, scenario.bytes_per_second, replay.gateway)
            _, labels, _ = replay.run(Policy("epidemic"), oracle)
            for row in labels:
                row["domain"] = name
                row["group"] = name+":"+row["group"]
            rows.extend(labels)
            print(f"Labeled {name}, seed {seed}: {len(labels)} rows", flush=True)
        tables[name] = pd.DataFrame(rows)
        if tables[name].empty:
            raise ValueError(f"No examples for {name}; increase horizon or packet count")
        tables[name].to_parquet(ROOT/f"data/processed/{name}_examples.parquet", index=False)
    train_frame = pd.concat([tables[n] for n in config["train_domains"]], ignore_index=True)
    validation = tables[config["validation_domain"]]
    from .models import train, export
    predictors, training = train(train_frame, validation, out/"checkpoints",
        epochs=config["epochs"], batch_size=config["batch_size"])
    print("CUDA training completed: "+json.dumps(training), flush=True)
    # Choose HOLD threshold using validation-only routing outcomes, never SFHH.
    selected = {}
    tuning = []
    for name, predictor in predictors.items():
        for threshold in (0.0, .02, .1):
            replay = Replay(frames[config["validation_domain"]], Scenario(**config["base"]), 21)
            metrics, _, _ = replay.run(Policy(name, predictor, threshold))
            tuning.append(dict(metrics, threshold=threshold))
        choices = [r for r in tuning if r["policy"] == name]
        best = max(choices, key=lambda r: (r["delivery_ratio"], -r["total_transmitted_bytes"]))
        selected[name] = best["threshold"]
    write_json(out/"metrics/validation_tuning.json", tuning)
    neural_name = max((n for n in selected if n != "sage_tree"),
        key=lambda n: max(r["delivery_ratio"] for r in tuning if r["policy"] == n))
    export_report = export(out/f"checkpoints/{neural_name.removeprefix('sage_')}.pt",
        out/"checkpoints/sage_lite.onnx", validation[FEATURES].to_numpy(np.float32)[:128])
    results = []
    # Complete held-out domain plus separate same-domain/new-traffic-seed check.
    for domain in [config["test_domain"], config["train_domains"][0]]:
        scenarios = config["scenarios"] if domain == config["test_domain"] else {"low_load": config["scenarios"]["low_load"]}
        for scenario_name, overrides in scenarios.items():
            scenario = Scenario(**(config["base"] | overrides))
            for seed in config["evaluation_seeds"]:
                replay = Replay(frames[domain], scenario, seed)
                for name in list(BASELINES)+list(predictors):
                    policy = Policy(name, predictors.get(name), selected.get(name,.02))
                    metrics, _, _ = replay.run(policy)
                    results.append(dict(metrics, domain=domain, scenario=scenario_name,
                        packets=scenario.packets, gateway_count=scenario.gateway_count,
                        adoption=scenario.adoption, media_fraction=scenario.media_fraction))
            pd.DataFrame(results).to_csv(out/"metrics/routing.csv", index=False)
            print(f"Evaluated {domain}/{scenario_name}", flush=True)
    table = pd.DataFrame(results)
    report(table, training, export_report, selected, config, out)
    write_json(out/"manifest.json", {"python": sys.version, "platform": platform.platform(),
        "config": str(config_path), "selected_thresholds": selected, "exported_model": neural_name})

def report(table, training, exported, selected, config, out):
    test = table[table.domain == config["test_domain"]]
    means = test.groupby("policy").mean(numeric_only=True)
    baseline = means.loc[list(BASELINES)].delivery_ratio.idxmax()
    sage = means.loc[list(selected)].delivery_ratio.idxmax()
    # Bootstrap paired scenario-seed delivery differences; descriptive pilot CI.
    paired = test.pivot(index=["scenario","seed"], columns="policy", values="delivery_ratio")
    delta = (paired[sage]-paired[baseline]).to_numpy()
    rng = np.random.default_rng(7)
    # Resample seed blocks to keep scenario outcomes for the same seed together.
    seed_delta = (paired[sage]-paired[baseline]).groupby(level="seed").mean().to_numpy()
    boot = rng.choice(seed_delta, (2000,len(seed_delta)), replace=True).mean(axis=1)
    interval = np.percentile(boot,[2.5,97.5])
    summary = means.reset_index()
    summary.to_csv(out/"metrics/summary.csv", index=False)
    comparison = test.groupby(["scenario","policy"]).delivery_ratio.mean().unstack()
    comparison["sage_minus_baseline"] = comparison[sage]-comparison[baseline]
    comparison.to_csv(out/"metrics/scenario_comparison.csv")
    lines = ["# SAGE-Lite v0 measured pilot results", "",
        "Did SAGE-Lite beat the strongest non-learning baseline?", "",
        f"{'YES in this limited pilot' if means.loc[sage,'delivery_ratio'] > means.loc[baseline,'delivery_ratio'] else 'NO in this pilot'}: "
        f"{sage} delivery {means.loc[sage,'delivery_ratio']:.3%}; {baseline} {means.loc[baseline,'delivery_ratio']:.3%}.",
        f"Paired difference {delta.mean():+.3%}; exploratory seed-block bootstrap 95% interval "
        f"[{interval[0]:+.3%}, {interval[1]:+.3%}]. Only {len(seed_delta)} seeds; this is not evidence of general superiority.", "",
        "The best SAGE policy here is identified after evaluation for descriptive comparison. "
        "The ONNX model was selected only on Hypertext validation routing. No SFHH labels were used for training or tuning.", "",
        "| Policy | Delivery | Critical delivery | p95 delivered latency (s) | Bytes |", "|---|---:|---:|---:|---:|"]
    for name, row in means.iterrows():
        lines.append(f"| {name} | {row.delivery_ratio:.3f} | {row.critical_delivery_ratio:.3f} | {row.p95_latency:.1f} | {row.total_transmitted_bytes:.0f} |")
    lines += ["", "Means weight scenario-seed runs equally. Latency excludes undelivered packets; "
        "compare it together with delivery ratio. Critical differences can arise from normal-traffic buffer occupancy, "
        "but never from model scoring of critical packets.", "", "## Failure analysis", "",
        "Scenario comparison uses the same aggregate-best baseline and learned policy throughout:", ""]
    for scenario, row in comparison.sort_values("sage_minus_baseline").iterrows():
        lines.append(f"- {scenario}: delivery difference {row.sage_minus_baseline:+.3%}.")
    lines += ["", "Against the other key DTN baselines (same learned policy, scenario means):", "",
        "| Scenario | SAGE minus PRoPHET-like | SAGE minus Spray-and-Wait |",
        "|---|---:|---:|"]
    for scenario, row in comparison.iterrows():
        lines.append(f"| {scenario} | {row[sage]-row['prophet_like']:+.3%} | {row[sage]-row['spray']:+.3%} |")
    lines += ["", "The label oracle ignores competing packets and loss; its optimistic reachability target can "
        "favor relays that lack buffer space under load. Training on epidemic-generated decisions creates "
        "policy-distribution shift. Sparse/churning contacts reduce destination-history information. "
        "These are hypotheses, not causal findings: ablations and more complete events are required. "
        "A heuristic/ML hybrid remains a follow-up experiment, not a demonstrated improvement.", "",
        "## Training and export", "", f"GPU: {training['hardware']['gpu']}; CUDA {training['hardware']['cuda']}.",
        f"Training rows: {training['train_rows']}; validation rows: {training['validation_rows']}; ranking pairs: {training['ranking_pairs']}.",
        f"ONNX: {exported['features']} features, {exported['parameters']} parameters, {exported['file_bytes']} bytes.",
        f"PyTorch/ONNX max absolute error {exported['max_abs_error']:.3g}; warm laptop CPU p50 "
        f"{exported['cpu_p50_ms']:.4f} ms, p95 {exported['cpu_p95_ms']:.4f} ms. Not Android latency.", "",
        "See outputs/checkpoints/training.json for validation losses, timings and actual CUDA memory usage. "
        "See outputs/metrics/routing.csv for every measured run and same-domain/new-seed results. "
        "Read LIMITATIONS.md before interpreting this pilot."]
    (ROOT/"RESULTS.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    plots = [("packets","delivery_ratio","delivery_vs_load"), ("packets","p95_latency","latency_vs_load"),
        ("duplicate_bytes","delivery_ratio","duplicate_vs_delivery"),
        ("media_fraction","critical_p95_latency","critical_vs_media"),
        ("gateway_count","delivery_ratio","gateway_count"), ("adoption","delivery_ratio","adoption")]
    for x,y,name in plots:
        fig, ax = plt.subplots(figsize=(8,5))
        plot_data = test[test.scenario.isin(["low_load", "heavy_load"])] if x == "packets" else test
        for policy, group in plot_data.groupby("policy"):
            g = group.groupby(x)[y].mean()
            ax.plot(g.index,g.values,"o",label=policy)
        title = "Paired load comparison (3 seeds)" if x == "packets" else "Pilot scenario summaries (other conditions vary)"
        ax.set(xlabel=x,ylabel=y,title=title)
        ax.legend(fontsize=7)
        fig.tight_layout()
        fig.savefig(out/f"plots/{name}.png",dpi=150)
        plt.close(fig)
    fig, ax = plt.subplots(figsize=(9,5))
    table[table.scenario == "low_load"].groupby(["domain","policy"]).delivery_ratio.mean().unstack().plot.bar(ax=ax)
    ax.set_ylabel("Delivery ratio (low-load scenario, 3 seeds)")
    fig.tight_layout()
    fig.savefig(out/"plots/domain.png",dpi=150)
    plt.close(fig)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=ROOT/"configs/pilot.json")
    args = parser.parse_args()
    run(args.config)

#!/usr/bin/env python3
"""Run a bounded public-read k6 check only against the configured staging origin."""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cd"))
from common import ROOT, SHA, now, read_json, sha, write_json
from render import validate_config

K6 = "grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34"


def validate_policy(config, policy):
    validate_config(config)
    p = policy["performance"]
    if policy.get("schema_version") != 1 or p.get("approved") is not True or p.get("environment") != "staging":
        raise ValueError("Approve a staging-only performance policy first")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,119}", p.get("fixture_slug") or ""):
        raise ValueError("Configure an owned public catalog fixture")
    for key, low, high in (("requests_per_second", 1, 20), ("duration_seconds", 60, 900), ("max_vus", 1, 20), ("maximum_p95_ms", 1, 2000)):
        if type(p.get(key)) is not int or not low <= p[key] <= high:
            raise ValueError("Load/threshold exceeds the reviewed staging envelope")
    if not 0 <= p.get("maximum_error_rate", -1) <= 0.01 or not 0 <= p.get("maximum_p95_regression_fraction", -1) <= 0.3:
        raise ValueError("Invalid regression/error threshold")
    b = p["baseline"]
    if not SHA.fullmatch(b.get("source_revision") or "") or not isinstance(b.get("p95_ms"), (int, float)) or not 0 < b["p95_ms"] <= 2000:
        raise ValueError("A measured, reviewed baseline is required")
    age = (datetime.now(timezone.utc) - datetime.fromisoformat(b["measured_at"])).total_seconds()
    if not -60 <= age <= 30 * 86400 or b["requests_per_second"] != p["requests_per_second"]:
        raise ValueError("Baseline is stale or was measured at a different request rate")
    return p


def evaluate(policy, observations):
    p95_limit = min(policy["maximum_p95_ms"], policy["baseline"]["p95_ms"] * (1 + policy["maximum_p95_regression_fraction"]))
    required = math.ceil(policy["requests_per_second"] * policy["duration_seconds"] * 0.95)
    limits = {"p95_limit_ms": p95_limit, "minimum_requests": required}
    if any(not isinstance(observations.get(k), (float, int)) or not math.isfinite(observations[k]) for k in ("requests", "iterations", "p95_ms", "error_rate", "check_rate", "dropped_iterations")):
        return "failed", limits
    passed = (observations["requests"] >= required and observations["iterations"] >= required and
              0 <= observations["p95_ms"] <= p95_limit and 0 <= observations["error_rate"] <= policy["maximum_error_rate"] and
              observations["check_rate"] == 1 and observations["dropped_iterations"] == 0)
    return "passed" if passed else "failed", limits


def run_k6(base, policy, network=None, fixture=False):
    name = "operations-k6-" + uuid.uuid4().hex[:10]
    limit = min(policy["maximum_p95_ms"], policy["baseline"]["p95_ms"] * (1 + policy["maximum_p95_regression_fraction"]))
    values = {"BASE_URL": base, "FIXTURE_SLUG": policy["fixture_slug"], "REQUEST_RATE": policy["requests_per_second"],
              "DURATION_SECONDS": policy["duration_seconds"], "MAX_VUS": policy["max_vus"], "MAX_ERROR_RATE": policy["maximum_error_rate"],
              "MAX_P95_MS": limit, "K6_NO_USAGE_REPORT": "true", "ISOLATED_FIXTURE": str(fixture).lower(), "HOME": "/tmp"}
    args = ["docker", "run", "--rm", "--name", name, "--read-only", "--tmpfs", "/tmp:rw,size=64m,mode=1777", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--memory", "512m", "--cpus", "1", "-v", str(ROOT / "loadtest/k6/operations-read.js") + ":/test.js:ro"]
    if network:
        args += ["--network", network]
    env = {**os.environ, **{k: str(v) for k, v in values.items()}}
    for key in values:
        args += ["-e", key]
    try:
        result = subprocess.run(args + [K6, "run", "--quiet", "/test.js"], env=env, capture_output=True, text=True, encoding="utf-8", timeout=policy["duration_seconds"] + 180)
        lines = [line.split("OPERATIONS_PERFORMANCE=", 1)[1] for line in result.stdout.splitlines() if "OPERATIONS_PERFORMANCE=" in line]
        if len(lines) != 1:
            raise RuntimeError("k6 did not produce a valid summary; output redacted")
        return json.loads(lines[0]), result.returncode
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    config, policy = read_json(args.config), read_json(args.policy)
    p = validate_policy(config, policy)
    observations, code = run_k6("https://" + config["environments"]["staging"]["domain"], p)
    status, limits = evaluate(p, observations)
    report = {"schema_version": 1, "phase": "performance", "environment": "staging", "result": status if code == 0 else "failed",
              "observations": observations, "limits": limits, "policy_sha256": sha(p), "runner_image": K6, "evaluated_at": now()}
    write_json(args.output, report)
    print("Staging performance: " + report["result"])
    return 0 if report["result"] == "passed" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("Performance check failed: " + type(error).__name__ + "; output redacted", file=sys.stderr)
        sys.exit(1)

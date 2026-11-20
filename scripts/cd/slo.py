#!/usr/bin/env python3
"""Read-only SLO assessment. Missing, stale, sparse and non-finite telemetry cannot pass."""
import argparse
import json
import math
import os
from pathlib import Path
import sys
import time
from urllib.parse import urlencode
import urllib.request

from common import now, read_json, sha, write_json
import progressive


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Metrics redirects are forbidden")


def scalar(response, evaluated_at):
    if response.get("status") != "success" or response.get("warnings") or response.get("infos"):
        raise ValueError("Prometheus returned an error, warning or partial result")
    data = response.get("data", {})
    result = data.get("result", [])
    if data.get("resultType") != "vector" or len(result) != 1:
        raise ValueError("Expected exactly one Prometheus observation")
    stamp, raw = result[0]["value"]
    value = float(raw)
    stamp = float(stamp)
    if not math.isfinite(value) or not math.isfinite(stamp) or abs(stamp - evaluated_at) > 90:
        raise ValueError("Observation is non-finite or stale")
    return value


def assess(profile, responses, evaluated_at):
    slo = progressive.settings(profile)["slo"]
    values, issues = {}, []
    for name in ("requests", "availability", "latency_success", "histogram_requests", "target_up", "scrape_age_seconds", "window_coverage"):
        try:
            values[name] = scalar(responses.get(name, {}), evaluated_at)
        except (ValueError, KeyError, TypeError, OverflowError):
            issues.append(name + ": missing, stale or invalid telemetry")
    if values.get("requests", 0) < slo["minimum_requests"]:
        issues.append("Insufficient request sample")
    if values.get("target_up") != 1 or not 0 <= values.get("scrape_age_seconds", -1) <= slo["max_scrape_age_seconds"]:
        issues.append("Telemetry targets are down or stale")
    if values.get("window_coverage", 0) < slo.get("minimum_window_coverage", 0.99):
        issues.append("Insufficient coverage of the complete SLO window")
    if values.get("requests", 0) > 0 and not 0.99 <= values.get("histogram_requests", 0) / values["requests"] <= 1.01:
        issues.append("Histogram and request counter coverage differs")
    for name in ("availability", "latency_success"):
        if not 0 <= values.get(name, -1) <= 1:
            issues.append(name + ": invalid ratio")
    report = {"schema_version": 1, "phase": "slo", "service": progressive.SERVICE, "namespace": profile["namespace"],
              "window_days": 30, "evaluated_at": now(), "policy_sha256": sha(slo), "observations": values,
              "result": "insufficient_data" if issues else "passed", "issues": sorted(set(issues)), "budgets": {}}
    if not issues:
        for name in ("availability", "latency_success"):
            consumed = (1 - values[name]) / (1 - slo[name])
            report["budgets"][name] = {"objective": slo[name], "remaining_fraction": 1 - consumed, "consumed_fraction": consumed}
            if values[name] < slo[name]:
                report["result"] = "breached"
    return report


def fetch(address, query, token, evaluated_at):
    url = address.rstrip("/") + "/api/v1/query?" + urlencode({"query": query, "time": evaluated_at, "timeout": "15s"})
    req = urllib.request.Request(url, headers={"Accept": "application/json", "Authorization": token})
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=20) as response:
            return json.load(response)
    except Exception:
        raise RuntimeError("Metrics query failed; credentials and response body redacted") from None


def assessment_queries(profile):
    queries = progressive.queries(profile, "30d")
    # One stable recording-rule sample every 30 seconds. Divide by the complete expected window,
    # not by the available samples: a fresh cluster must not claim a 30-day SLO.
    series = "bff:slo_telemetry_available" + progressive.selectors(profile["namespace"])
    queries["window_coverage"] = "clamp_max((sum(sum_over_time(" + series + "[30d])) or vector(0)) / 86400, 1)"
    return queries


def evaluate_profile(profile, state=None):
    progressive.validate(profile)
    if not progressive.enabled(profile):
        raise ValueError("SLO monitoring is disabled")
    evaluated_at = time.time()
    queries = assessment_queries(profile)
    token = os.environ["PROMETHEUS_AUTHORIZATION"]
    if not token or "\n" in token or "\r" in token:
        raise ValueError("Configure a read-only metrics credential")
    responses = {name: fetch(progressive.settings(profile)["prometheus_url"], query, token, evaluated_at) for name, query in queries.items()}
    report = assess(profile, responses, evaluated_at)
    if state:
        report.update(state_sha256=state["state_sha256"], release_id=state["release_id"], source_revision=state["source_revision"])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--environment", choices=("staging", "production"), required=True)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    config = read_json(args.config)
    state = None
    profile = config["environments"][args.environment]
    if args.bundle:
        state = read_json(args.bundle / "state.json")
        profile = read_json(args.bundle / "profile.json")
        if (state["state_sha256"] != sha({k: v for k, v in state.items() if k != "state_sha256"}) or
                state["environment"] != args.environment or state["profile_sha256"] != sha(profile)):
            raise ValueError("Invalid frozen deployment state")
    report = evaluate_profile(profile, state)
    write_json(args.output, report)
    print("SLO assessment: " + report["result"])
    return 0 if report["result"] == "passed" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("SLO assessment failed: " + type(error).__name__ + "; details redacted", file=sys.stderr)
        sys.exit(1)

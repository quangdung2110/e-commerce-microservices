#!/usr/bin/env python3
"""Render synthetic, non-deployable test bundles for independent Kubernetes schema validation."""
import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_delivery import configuration, manifest
from delivery import bootstrap, prepare_stage
from validate_gitops import validate

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--operations", action="store_true")
    args = parser.parse_args()
    config = configuration()
    if args.operations:
        for env, profile in config["environments"].items():
            profile["operations"]["enabled"] = True
            profile["operations"]["prometheus_url"] = "https://metrics." + env + ".shop.test"
            profile["operations"]["slo"]["approved"] = True
            profile["operations"]["canary"]["enabled"] = True
            profile["secret_versions"]["operations-metrics"] = "immutable-v1"
    bootstrap(config, args.output)
    prepare_stage(config, manifest(), args.output, "synthetic-test-only", "b" * 40, [])
    validate(args.output)
    if args.operations:
        import yaml
        from progressive import monitoring
        from render import resource
        profile = config["environments"]["staging"]
        profile["operations"]["source_repository"] = config["source_repository"]
        rule = next(o for o in monitoring(profile, resource) if o["kind"] == "PrometheusRule")
        (args.output / "alerts.rules.yaml").write_text(yaml.safe_dump(rule["spec"]), encoding="utf-8")
        from common import ROOT
        from slo import assessment_queries
        tests = yaml.safe_load((ROOT / "scripts/ops/tests/alert-tests.yaml").read_text(encoding="utf-8"))
        empty = tests["tests"][-1]
        empty["promql_expr_test"] = [{"expr": query, "eval_time": "5m", "exp_samples": [{"labels": "{}", "value": 0}] if name == "window_coverage" else []}
                                    for name, query in assessment_queries(profile).items()]
        (args.output / "alerts.test.yaml").write_text(yaml.safe_dump(tests), encoding="utf-8")
    (args.output / "TEST-ONLY.txt").write_text("Synthetic endpoints, CA and digests. Never apply these fixtures to a cluster.\n", encoding="utf-8")

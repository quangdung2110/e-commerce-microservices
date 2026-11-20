import copy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

CD = Path(__file__).resolve().parents[2] / "cd"
sys.path[:0] = [str(CD), str(CD / "tests"), str(Path(__file__).resolve().parents[1])]
from test_delivery import configuration, manifest
from common import ROOT, now, read_json, validate_manifest
from delivery import bootstrap, prepare_stage
from render import render_environment, validate_config
from validate_gitops import validate as validate_gitops
from observe import evaluate as observe
import progressive
import slo
import performance
from recovery import verify_backup


def operations_config():
    config = configuration()
    for name, profile in config["environments"].items():
        ops = profile["operations"]
        ops.update(enabled=True, prometheus_url="https://metrics." + name + ".shop.test")
        ops["slo"]["approved"] = True
        ops["canary"]["enabled"] = True
        profile["secret_versions"]["operations-metrics"] = "immutable-v1"
    return config


def rendered(config=None, revision="a" * 40):
    config = config or operations_config()
    images = validate_manifest(manifest(revision), config["source_repository"])
    return render_environment(config, "staging", images, "test-release", revision, "b" * 40, "123", [])


class CanaryTests(unittest.TestCase):
    def test_defaults_keep_the_phase_two_deployment_strategy(self):
        config = configuration()
        validate_config(config)
        _, resources = rendered(config)
        self.assertEqual(sum(o["kind"] == "Deployment" for o in resources), 16)
        self.assertFalse(any(o["kind"] == "Rollout" for o in resources))

    def test_only_bff_is_canary_and_steps_analyze_the_latest_pod_revision(self):
        config = operations_config()
        validate_config(config)
        state, resources = rendered(config)
        rollouts = [o for o in resources if o["kind"] == "Rollout"]
        self.assertEqual([o["metadata"]["name"] for o in rollouts], [progressive.SERVICE])
        self.assertEqual(sum(o["kind"] == "Deployment" for o in resources), 15)
        self.assertEqual(state["workload_kinds"], {progressive.SERVICE: "Rollout"})
        steps = rollouts[0]["spec"]["strategy"]["canary"]["steps"]
        self.assertEqual([s["setWeight"] for s in steps if "setWeight" in s], [20, 50, 100])
        self.assertEqual(len([s for s in steps if "analysis" in s]), 3)
        template = next(o for o in resources if o["kind"] == "AnalysisTemplate")
        for metric in template["spec"]["metrics"]:
            self.assertIn('rollout_revision="{{args.rollout-hash}}"', metric["provider"]["prometheus"]["query"])
            self.assertIn("len(result)", metric["successCondition"])
            self.assertIn("isNaN", metric["failureCondition"])
            self.assertEqual(metric["failureLimit"], 0)
        self.assertNotIn("Bearer", json.dumps(template))

    def test_release_labels_never_change_immutable_service_or_workload_selectors(self):
        _, first = rendered()
        _, second = rendered(revision="c" * 40)
        for kind in ("Service", "Deployment", "Rollout", "PodDisruptionBudget"):
            a = {o["metadata"]["name"]: o["spec"]["selector"] for o in first if o["kind"] == kind}
            b = {o["metadata"]["name"]: o["spec"]["selector"] for o in second if o["kind"] == kind}
            self.assertEqual(a, b)
            self.assertNotIn("delivery.ecommerce/revision", json.dumps(a))

    def test_workers_missing_secret_and_unrehearsed_production_canary_are_rejected(self):
        mutations = [lambda c: c["environments"]["production"]["operations"]["canary"].update(service="order-service"),
                     lambda c: c["environments"]["staging"]["operations"]["canary"].update(enabled=False),
                     lambda c: c["environments"]["staging"]["secret_versions"].update({"operations-metrics": "latest"}),
                     lambda c: c["environments"]["production"]["operations"]["slo"].update(approved=False)]
        for mutation in mutations:
            config = operations_config()
            mutation(config)
            with self.assertRaises(ValueError):
                validate_config(config)

    def test_exported_validator_accepts_canary_and_rejects_analysis_bypass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "gitops"
            config = operations_config()
            bootstrap(config, root)
            prepare_stage(config, manifest(), root, "canary-one", "b" * 40, [])
            validate_gitops(root)
            import yaml
            path = root / "environments/staging/resources.yaml"
            resources = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
            template = next(o for o in resources if o["kind"] == "AnalysisTemplate")
            template["spec"]["metrics"][0]["successCondition"] = "true"
            path.write_text(yaml.safe_dump_all(resources), encoding="utf-8")
            with self.assertRaises(ValueError):
                validate_gitops(root)

    def test_observer_refuses_paused_aborted_or_old_stable_rollout(self):
        state, resources = rendered()
        managed = {"items": []}
        for obj in resources:
            if obj["kind"] not in ("Deployment", "Rollout"):
                continue
            replicas = obj["spec"]["replicas"]
            obj["metadata"]["generation"] = 2
            obj["status"] = {"readyReplicas": replicas, "updatedReplicas": replicas, "observedGeneration": "2"}
            if obj["kind"] == "Rollout":
                obj["status"].update(phase="Healthy", currentPodHash="new", stableRS="new")
            managed["items"].append({"kind": obj["kind"], "name": obj["metadata"]["name"], "liveState": obj})
        app = {"status": {"sync": {"status": "Synced", "revision": "d" * 40}, "health": {"status": "Healthy"},
                          "operationState": {"phase": "Succeeded", "syncResult": {"revision": "d" * 40, "resources": [
                              {"kind": "Job", "namespace": state["namespace"], "name": "verify-" + state["state_sha256"][:16], "hookPhase": "Succeeded"}]}}}}
        self.assertEqual(observe(app, managed, state), [])
        for change in ({"phase": "Paused"}, {"abort": True}, {"stableRS": "old"}, {"pauseConditions": [{"reason": "AnalysisRunInconclusive"}]}):
            bad = copy.deepcopy(managed)
            obj = next(o["liveState"] for o in bad["items"] if o["kind"] == "Rollout")
            obj["status"].update(change)
            self.assertTrue(observe(app, bad, state))


class SloTests(unittest.TestCase):
    def setUp(self):
        self.profile = operations_config()["environments"]["staging"]
        values = {"requests": 50000, "availability": 0.9995, "latency_success": 0.995,
                  "histogram_requests": 50000, "target_up": 1, "scrape_age_seconds": 15, "window_coverage": 1}
        self.responses = {k: {"status": "success", "data": {"resultType": "vector", "result": [{"metric": {}, "value": [1000, str(v)]}]}} for k, v in values.items()}

    def test_healthy_window_passes_with_remaining_error_budget(self):
        report = slo.assess(self.profile, self.responses, 1000)
        self.assertEqual(report["result"], "passed")
        self.assertAlmostEqual(report["budgets"]["availability"]["remaining_fraction"], 0.5)

    def test_missing_nan_infinite_stale_sparse_and_partial_data_cannot_pass(self):
        for name, value in (("requests", "0"), ("availability", "NaN"), ("latency_success", "Inf"),
                            ("target_up", "0"), ("scrape_age_seconds", "120"), ("window_coverage", "0.98"), ("histogram_requests", "1000")):
            bad = copy.deepcopy(self.responses)
            bad[name]["data"]["result"][0]["value"][1] = value
            self.assertEqual(slo.assess(self.profile, bad, 1000)["result"], "insufficient_data")
        for mutation in (lambda r: r.pop("requests"), lambda r: r["requests"].update(warnings=["partial response"]),
                         lambda r: r["requests"]["data"]["result"][0].update(value=[1, "50000"]),
                         lambda r: r["requests"]["data"]["result"][0].update(value=["NaN", "50000"]),
                         lambda r: r["requests"]["data"]["result"][0].update(value=["Inf", "50000"])):
            bad = copy.deepcopy(self.responses)
            mutation(bad)
            self.assertEqual(slo.assess(self.profile, bad, 1000)["result"], "insufficient_data")

    def test_real_budget_breach_is_not_reported_as_missing_data(self):
        self.responses["availability"]["data"]["result"][0]["value"][1] = "0.98"
        report = slo.assess(self.profile, self.responses, 1000)
        self.assertEqual(report["result"], "breached")
        self.assertLess(report["budgets"]["availability"]["remaining_fraction"], 0)


class PerformanceRecoveryTests(unittest.TestCase):
    def policy(self):
        p = read_json(ROOT / "deploy/operations/policy.example.json")
        p["performance"].update(approved=True, fixture_slug="fixture", requests_per_second=2, duration_seconds=60, max_vus=5)
        p["performance"]["baseline"].update(source_revision="a" * 40, measured_at=now(), p95_ms=500, requests_per_second=2)
        return p

    def test_production_unapproved_unbounded_and_stale_baselines_are_rejected(self):
        performance.validate_policy(configuration(), self.policy())
        for mutation in (lambda p: p["performance"].update(environment="production"), lambda p: p["performance"].update(approved=False),
                         lambda p: p["performance"].update(requests_per_second=1000), lambda p: p["performance"]["baseline"].update(measured_at=(datetime.now(timezone.utc)-timedelta(days=31)).isoformat())):
            policy = self.policy()
            mutation(policy)
            with self.assertRaises(ValueError):
                performance.validate_policy(configuration(), policy)

    def test_low_delivered_load_drops_checks_and_p95_regressions_fail(self):
        p = self.policy()["performance"]
        good = {"requests": 120, "iterations": 120, "p95_ms": 500, "error_rate": 0, "check_rate": 1, "dropped_iterations": 0}
        self.assertEqual(performance.evaluate(p, good)[0], "passed")
        for key, value in (("requests", 0), ("iterations", 0), ("dropped_iterations", 1), ("p95_ms", 601), ("error_rate", 0.02), ("check_rate", 0.9)):
            bad = {**good, key: value}
            self.assertEqual(performance.evaluate(p, bad)[0], "failed")

    def test_backup_corruption_is_rejected_before_restore(self):
        import hashlib
        archive = b"PGDMPfixture"
        checksum = hashlib.sha256(archive).hexdigest()
        verify_backup(archive, checksum)
        with self.assertRaises(ValueError):
            verify_backup(archive + b"corrupt", checksum)

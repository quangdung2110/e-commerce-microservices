import copy
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import yaml

CD = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CD))
from common import ROOT, catalog, expected_images, now, read_json, sha, tree_sha, validate_manifest, write_json
from delivery import bootstrap, candidate_path, prepare_stage, prepare_promotion, prepare_rollback
from e2e import Scenario
from observe import evaluate, require_platform
from render import external_secret, render_environment, required_versions, validate_config
from validate_gitops import bundle, validate
from transport import dependency_clients, platform_resources
from transport_evidence import expected_checks

sys.path.insert(0, str(ROOT / "contracts/tools"))
import verify_providers


def configuration():
    config = read_json(ROOT / "deploy/gitops/environments.example.json")
    config.update(gitops_repository="delivery/platform", gitops_bot_login="delivery[bot]", production_owners=["@release-owner"], argocd_url="https://argocd.shop.test")
    config["readiness"] = {k: True for k in config["readiness"]}
    for env, profile in config["environments"].items():
        profile.update(cluster_server="https://" + env + ".cluster.test", domain=env + ".shop.test", media_origin="https://media." + env + ".shop.test",
                       database_ca_pem="-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n", ingress_class="nginx", ingress_namespace="ingress-system",
                       ingress_selector={"app.kubernetes.io/name": "ingress-nginx"}, trusted_proxy_cidrs=["10.1.0.0/24"],
                       observability_namespace="observability", otlp_endpoint="http://otel-collector.observability.svc.cluster.local:4318", secret_store="platform-secrets")
        endpoints = []
        public = {"stripe": "api.stripe.com", "media": "media." + profile["domain"], "storefront": profile["domain"],
                  "ses": f"ses.{env}.shop.test", "sqs": f"sqs.{env}.shop.test", "object-storage": f"s3.{env}.shop.test"}
        private_ports = {"postgres": 5432, "redis": 6379, "kafka": 9093, "temporal": 7233, "elasticsearch": 9200}
        for kind, clients in dependency_clients(catalog(), env).items():
            if kind in public:
                endpoints.append({"name": kind, "kind": kind, "host": public[kind], "port": 443, "clients": sorted(clients), "cidrs": []})
            elif kind in private_ports:
                endpoints.append({"name": kind, "kind": kind, "host": f"{kind}.{env}.data.test", "port": private_ports[kind],
                                  "clients": sorted(clients), "cidrs": [f"10.2.0.{len(endpoints) + 1}/32"]})
        profile["transport"].update(platform_revision="d" * 40, ingress_service_account="ingress-nginx", collector_service="otel-collector",
                       collector_service_account="otel-collector", collector_selector={"app.kubernetes.io/name": "otel-collector"},
                       prometheus_service_account="prometheus", prometheus_selector={"app.kubernetes.io/name": "prometheus"},
                       temporal={"mode": "mtls" if env == "production" else "tls", "namespace": "order-" + env}, endpoints=endpoints)
        profile["secret_versions"] = {k: "immutable-v1" for k in required_versions(catalog(), env, profile)}
    return config


def manifest(revision="a" * 40, run="123"):
    repository = configuration()["source_repository"]
    records = []
    for name in sorted(expected_images()):
        image = "ghcr.io/" + repository + "/" + name
        digest = "sha256:" + hashlib.sha256((name + revision).encode()).hexdigest()
        records.append({"name": name, "image": image, "digest": digest, "reference": image + "@" + digest, "revision": revision, "run_id": run})
    return {"schema_version": 1, "repository": repository, "revision": revision, "run_id": run, "images": records}


class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "gitops"
        self.config = configuration()
        bootstrap(self.config, self.root)

    def stage(self, release="release-one", revision="a" * 40, compatible=None):
        return prepare_stage(self.config, manifest(revision), self.root, release, "b" * 40, compatible or [])

    def proof(self, release="release-one", environment="staging", run="456", with_transport=True):
        path = self.root / "candidates" / release
        state = read_json(path / environment / "state.json")
        profile = read_json(path / environment / "profile.json")
        proof = {"environment": environment, "release_id": release, "verification_run_id": run, "verified_at": now(),
                 "bundle_sha256": tree_sha(path / environment), "production_bundle_sha256": tree_sha(path / "production"),
                 "state_sha256": state["state_sha256"], "source_repository": self.config["source_repository"],
                 "source_revision": state["source_revision"], "transport_sha256": state["transport_sha256"], "gitops_revision": "a" * 40,
                 "platform_revision": profile["transport"]["platform_revision"]}
        if with_transport and environment == "staging":
            report = {k: proof[k] for k in ("environment", "release_id", "verified_at", "state_sha256", "source_revision",
                                           "transport_sha256", "platform_revision", "production_bundle_sha256",
                                           "gitops_revision", "verification_run_id")}
            report.update(schema_version=1, staging_bundle_sha256=proof["bundle_sha256"], checks={
                check: {"result": "passed", "evidence": "https://evidence.shop.test/fixture"} for check in expected_checks(profile)})
            write_json(self.root / "transport-evidence/staging" / release / (sha(report) + ".json"), report)
        return proof

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args]).decode().strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def test_empty_bootstrap_is_valid_and_exported_tools_work(self):
        validate(self.root)
        result = subprocess.run([sys.executable, str(self.root / "tools/cd/validate_gitops.py")], cwd=self.root, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / ".github/CODEOWNERS").is_file())
        with self.assertRaises(ValueError):
            bootstrap(self.config, self.root)

    def test_missing_infrastructure_and_readiness_fail_closed(self):
        with self.assertRaises(ValueError):
            validate_config(read_json(ROOT / "deploy/gitops/environments.example.json"))
        self.config["readiness"]["payment_production_cipher_implemented"] = False
        validate_config(self.config)
        with self.assertRaises(ValueError):
            self.stage()

    def test_bad_secret_versions_and_shared_clusters_are_rejected(self):
        for mutation in (lambda c: c["environments"]["production"].update(cluster_server=c["environments"]["staging"]["cluster_server"]),
                         lambda c: c["environments"]["staging"]["secret_versions"].update(registry="latest"),
                         lambda c: c["environments"]["staging"].update(replicas=2.5),
                         lambda c: c["environments"]["staging"].update(trusted_proxy_cidrs=["0.0.0.0/0"])):
            config = copy.deepcopy(self.config)
            mutation(config)
            with self.assertRaises(ValueError):
                validate_config(config)

    def test_complete_digest_snapshot_required(self):
        value = manifest()
        for change in (lambda m: m["images"].pop(), lambda m: m["images"][0].update(reference="image:latest"),
                       lambda m: m["images"][0].update(revision="c" * 40)):
            bad = copy.deepcopy(value)
            change(bad)
            with self.assertRaises(ValueError):
                validate_manifest(bad, self.config["source_repository"])

    def test_secret_versions_create_fresh_resource_and_target(self):
        profile = copy.deepcopy(self.config["environments"]["staging"])
        old = external_secret("auth-service", {"DB_PASSWORD": "DB_PASSWORD"}, profile)
        profile["secret_versions"]["auth-service"] = "immutable-v2"
        new = external_secret("auth-service", {"DB_PASSWORD": "DB_PASSWORD"}, profile)
        self.assertNotEqual(old["metadata"]["name"], new["metadata"]["name"])
        self.assertNotEqual(old["spec"]["target"]["name"], new["spec"]["target"]["name"])
        profile["secret_prefix"] += "-rotated"
        moved = external_secret("auth-service", {"DB_PASSWORD": "DB_PASSWORD"}, profile)
        self.assertNotEqual(new["metadata"]["name"], moved["metadata"]["name"])

    def test_transport_evidence_is_required_and_covers_each_dependency(self):
        self.stage()
        proof = self.proof(with_transport=False)
        with self.assertRaisesRegex(ValueError, "transport evidence"):
            prepare_promotion(self.config, self.root, proof, "456")
        proof = self.proof()
        report_path = next((self.root / "transport-evidence/staging/release-one").glob("*.json"))
        report = read_json(report_path)
        del report["checks"]["temporal/order-service"]
        report_path.unlink()
        write_json(report_path.with_name(sha(report) + ".json"), report)
        with self.assertRaisesRegex(ValueError, "every required check"):
            prepare_promotion(self.config, self.root, proof, "456")

    def test_rendered_transport_carries_workload_scope_and_order_tls(self):
        self.stage()
        path = self.root / "environments/staging"
        docs = list(yaml.safe_load_all((path / "resources.yaml").read_text(encoding="utf-8")))
        resources = {(d["kind"], d["metadata"]["name"]): d for d in docs}
        self.assertNotIn(("NetworkPolicy", "application-egress"), resources)
        self.assertEqual({"networking.k8s.io/v1"}, {d["apiVersion"] for d in docs if d["kind"] == "NetworkPolicy"})
        self.assertEqual({"security.istio.io/v1"}, {d["apiVersion"] for d in docs if d["kind"] == "AuthorizationPolicy"})
        product = resources["Service", "product-service"]
        self.assertEqual("grpc", next(p["appProtocol"] for p in product["spec"]["ports"] if p["name"] == "grpc"))
        order = resources["Deployment", "order-service"]["spec"]["template"]["spec"]
        env = {e["name"]: e.get("value") for e in order["containers"][0]["env"]}
        self.assertEqual("tls", env["TEMPORAL_TLS_MODE"])
        self.assertEqual("temporal.staging.data.test:7233", env["TEMPORAL_TARGET"])
        self.assertTrue(env["TEMPORAL_API_KEY_FILE"].startswith("/etc/temporal-client/"))
        migration = resources["Job", "migration-order-service"]["spec"]["template"]["spec"]
        self.assertEqual("deployment-migrations", migration["serviceAccountName"])
        self.assertFalse(any(p.get("port") == 7233 for r in resources["NetworkPolicy", "egress-frontend"]["spec"]["egress"] for p in r["ports"]))
        self.assertTrue(any(p.get("port") == 7233 for r in resources["NetworkPolicy", "egress-order-service"]["spec"]["egress"] for p in r["ports"]))
        removed = [d for d in docs if d["metadata"]["name"] != "egress-order-service"]
        (path / "resources.yaml").write_text(yaml.safe_dump_all(removed), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Transport policy digest"):
            bundle(path, self.config, "staging")

    def test_stage_and_promote_preserve_both_frozen_bundles(self):
        metadata = self.stage()
        validate(self.root)
        prepare_promotion(self.config, self.root, self.proof(), "456")
        validate(self.root)
        self.assertEqual(tree_sha(self.root / "environments/production"), metadata["production_bundle_sha256"])
        resources = list(yaml.safe_load_all((self.root / "environments/production/resources.yaml").read_text(encoding="utf-8")))
        self.assertEqual(sum(r["kind"] == "Deployment" for r in resources), 16)
        self.assertEqual(sum(r["kind"] == "Job" and r["metadata"].get("labels", {}).get("delivery.ecommerce/role") == "migration" for r in resources), 9)
        self.assertFalse(any(r["kind"] == "Secret" for r in resources))
        self.assertNotIn("/app/e2e.py", (self.root / "environments/production/resources.yaml").read_text(encoding="utf-8"))

    def test_proof_expiry_wrong_run_and_changed_production_bundle_block_promotion(self):
        self.stage()
        good = self.proof()
        for field, value in (("verified_at", (datetime.now(timezone.utc) - timedelta(days=2)).isoformat()),
                             ("verification_run_id", "999"), ("production_bundle_sha256", "f" * 64)):
            bad = copy.deepcopy(good)
            bad[field] = value
            with self.assertRaises(ValueError):
                prepare_promotion(self.config, self.root, bad, "456")

    def test_staging_moved_or_candidate_tampered_blocks_promotion(self):
        self.stage()
        proof = self.proof()
        self.stage("release-two", "c" * 40)
        with self.assertRaises(ValueError):
            prepare_promotion(self.config, self.root, proof, "456")
        target = self.root / "candidates/release-two/production/resources.yaml"
        target.write_text(target.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            prepare_promotion(self.config, self.root, self.proof("release-two"), "456")

    def test_production_changed_after_candidate_creation_blocks_promotion(self):
        self.stage()
        self.stage("release-two", "c" * 40)
        # Simulate another approved production PR winning the race.
        write_json(self.root / "environments/production/state.json", {"state_sha256": "different"})
        with self.assertRaises(ValueError):
            prepare_promotion(self.config, self.root, self.proof("release-two"), "456")

    def test_configuration_change_after_staging_requires_a_new_candidate(self):
        self.stage()
        self.config["environments"]["production"]["sampling"] = 0.2
        with self.assertRaises(ValueError):
            prepare_promotion(self.config, self.root, self.proof(), "456")

    def test_rollback_requires_compatibility_and_never_runs_migrations(self):
        self.stage()
        prepare_promotion(self.config, self.root, self.proof(), "456")
        old = self.proof(environment="production", run="789")
        self.stage("release-two", "c" * 40, ["a" * 40])
        prepare_promotion(self.config, self.root, self.proof("release-two"), "456")
        prepare_rollback(self.config, self.root, old, "789")
        validate(self.root)
        resources = list(yaml.safe_load_all((self.root / "environments/production/resources.yaml").read_text(encoding="utf-8")))
        self.assertEqual([r["metadata"]["name"] for r in resources if r["kind"] == "Job"], ["verify-" + old["state_sha256"][:16]])
        with self.assertRaises(ValueError):
            prepare_rollback(self.config, self.root, old, "789")

    def test_rollback_incompatible_target_is_blocked(self):
        self.stage()
        prepare_promotion(self.config, self.root, self.proof(), "456")
        old = self.proof(environment="production", run="789")
        self.stage("release-two", "c" * 40)
        prepare_promotion(self.config, self.root, self.proof("release-two"), "456")
        with self.assertRaises(ValueError):
            prepare_rollback(self.config, self.root, old, "789")

    def test_paths_cannot_escape_candidate_root(self):
        for release in ("../../outside", "x/../outside", "A", ""):
            with self.assertRaises(ValueError):
                candidate_path(self.root, release)

    def test_validator_rejects_changed_candidate_and_non_app_production_pr(self):
        self.git("init", "-q")
        self.git("config", "user.name", "test")
        self.git("config", "user.email", "test@example.com")
        self.stage()
        base = self.commit()
        prepare_promotion(self.config, self.root, self.proof(), "456")
        self.commit()
        with self.assertRaises(ValueError):
            validate(self.root, base, author="someone")
        validate(self.root, base, author="delivery[bot]")
        base = self.git("rev-parse", "HEAD")
        target = self.root / "candidates/release-one/metadata.json"
        target.write_text(target.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        self.commit()
        with self.assertRaises(ValueError):
            validate(self.root, base)

    def test_validator_rejects_stale_compare_and_swap(self):
        self.git("init", "-q")
        self.git("config", "user.name", "test")
        self.git("config", "user.email", "test@example.com")
        base = self.commit()
        self.stage()
        operation = read_json(self.root / "operations/staging.json")
        operation["expected_previous_state"] = "unexpected"
        write_json(self.root / "operations/staging.json", operation)
        self.commit()
        with self.assertRaises(ValueError):
            validate(self.root, base)

    def test_mesh_platform_must_be_programmed_with_the_current_gateway_configuration(self):
        profile = self.config["environments"]["staging"]
        objects = platform_resources(profile)
        waypoints = [obj for obj in objects if obj["kind"] == "Gateway"]
        self.assertEqual(2, len(waypoints))
        options = next(obj for obj in objects if obj["kind"] == "ConfigMap")
        container_defaults = yaml.safe_load(options["data"]["deployment"])["spec"]["template"]["spec"]
        self.assertEqual("RuntimeDefault", container_defaults["securityContext"]["seccompProfile"]["type"])
        for obj in objects:
            obj["metadata"]["generation"] = 2
            if obj["kind"] == "Gateway":
                self.assertEqual(options["metadata"]["name"], obj["spec"]["infrastructure"]["parametersRef"]["name"])
                obj["status"] = {"conditions": [{"type": "Programmed", "status": "True", "observedGeneration": 2}]}
            if obj["kind"] == "HorizontalPodAutoscaler":
                obj["status"] = {"currentReplicas": 2}
        application = {"status": {"sync": {"status": "Synced"}}}
        managed = {"items": [{"kind": obj["kind"], "name": obj["metadata"]["name"], "liveState": obj} for obj in objects]}
        with patch("observe.os.environ", {"ARGOCD_AUTH_TOKEN": "test-token"}):
            with patch("observe.argo_get", side_effect=[application, managed]):
                require_platform(self.config, "staging")
            waypoints[0]["status"]["conditions"][0]["observedGeneration"] = 1
            with patch("observe.argo_get", side_effect=[application, managed]):
                with self.assertRaisesRegex(RuntimeError, "Waypoint is not programmed"):
                    require_platform(self.config, "staging")

    def test_observer_requires_exact_live_images_replicas_and_hook(self):
        self.stage()
        state = read_json(self.root / "environments/staging/state.json")
        resources = list(yaml.safe_load_all((self.root / "environments/staging/resources.yaml").read_text(encoding="utf-8")))
        items = []
        for obj in resources:
            if obj["kind"] == "Deployment":
                obj["metadata"]["generation"] = 2
                obj["status"] = {"updatedReplicas": 2, "readyReplicas": 2, "observedGeneration": 2}
                items.append({"kind": "Deployment", "name": obj["metadata"]["name"], "liveState": obj})
        managed = {"items": items}
        app = {"status": {"sync": {"status": "Synced", "revision": "d" * 40}, "health": {"status": "Healthy"}, "operationState": {"phase": "Succeeded", "syncResult": {"revision": "d" * 40, "resources": [{"kind": "Job", "namespace": state["namespace"], "name": "verify-" + state["state_sha256"][:16], "hookPhase": "Succeeded"}]}}}}
        self.assertEqual(evaluate(app, managed, state), [])
        for mutation in (lambda a, m: m["items"][0]["liveState"]["status"].update(readyReplicas=1),
                         lambda a, m: m["items"][0]["liveState"]["spec"]["template"]["spec"]["containers"][0].update(image="wrong:latest"),
                         lambda a, m: a["status"]["operationState"]["syncResult"].update(resources=[]),
                         lambda a, m: a["status"]["operationState"]["syncResult"].update(revision="e" * 40),
                         lambda a, m: m["items"].pop()):
            a, m = copy.deepcopy(app), copy.deepcopy(managed)
            mutation(a, m)
            self.assertTrue(evaluate(a, m, state))


class RuntimeCheckTests(unittest.TestCase):
    def environment(self):
        return {"DEPLOYMENT_ENVIRONMENT": "staging", "POD_NAMESPACE": "test-staging", "E2E_ALLOWED_NAMESPACE": "test-staging",
                "E2E_STRIPE_SECRET_KEY": "sk_test_fixture", "API_BASE_URL": "https://staging.shop.test", "STOREFRONT_URL": "https://staging.shop.test", "E2E_WEBHOOK_SECRET": "fixture"}

    def test_e2e_refuses_production_live_keys_or_wrong_namespace(self):
        Scenario(self.environment())
        for key, value in (("DEPLOYMENT_ENVIRONMENT", "production"), ("E2E_STRIPE_SECRET_KEY", "sk_live_fixture"), ("POD_NAMESPACE", "wrong"), ("API_BASE_URL", "http://staging.shop.test")):
            env = self.environment()
            env[key] = value
            with self.assertRaises(ValueError):
                Scenario(env)

    def test_duplicate_webhook_replays_identical_signed_bytes(self):
        scenario = Scenario(self.environment())
        scenario.stripe = lambda *args: {"data": [{"livemode": False, "data": {"object": {"id": "pi_fixture"}}}]}
        with patch("e2e.request") as send:
            scenario.replay_webhook("pi_fixture")
        self.assertEqual(send.call_count, 2)
        calls = send.call_args_list
        self.assertEqual(calls[0], calls[1])
        payload = calls[0].args[2]
        self.assertIsInstance(payload, bytes)
        stamp, signature = calls[0].kwargs["headers"]["Stripe-Signature"].split(",")
        expected = hmac.new(b"fixture", stamp[2:].encode() + b"." + payload, hashlib.sha256).hexdigest()
        self.assertEqual(signature, "v1=" + expected)

    def test_sandbox_scenario_checks_cancellation_duplicate_delivery_and_refund(self):
        scenario = Scenario(self.environment())
        scenario.login = lambda: None
        scenario.create_order = unittest.mock.Mock(side_effect=[("cancel-order", {}), ("paid-order", {"payment": {"client_secret": "pi_fixture_secret_redacted"}})])
        scenario.api = unittest.mock.Mock(return_value={})
        scenario.stripe = unittest.mock.Mock(return_value={"livemode": False})
        scenario.replay_webhook = unittest.mock.Mock()
        observed = [
            {"order_status": "CANCELLED"},
            {"payment_status": "PAID", "payment": {"active_attempt_number": 1}},
            {"payment_status": "REFUNDED", "refund_case": {"status": "SUCCEEDED"}},
        ]
        def poll(order, predicate):
            value = observed.pop(0)
            self.assertTrue(predicate(value))
            return value
        scenario.poll = poll
        with patch("sys.stdout", new_callable=io.StringIO):
            scenario.run()
        refunds = [call for call in scenario.api.call_args_list if "pre-dispatch-refund" in call.args[1]]
        self.assertEqual(len(refunds), 2)
        self.assertEqual(refunds[0], refunds[1])
        scenario.replay_webhook.assert_called_once_with("pi_fixture")

    def test_strict_providers_reject_skip_capture_failure_and_quiet_failure(self):
        template = {"targets": {"product-service": "http://unused"}, "probes": [{"id": "prerequisite", "interaction": "search-reindexes-product-catalog",
                    "capture": {"product_id": "products[0].product_id"}, "request": {"method": "GET", "path": "/internal/v1/products/export"}, "expect": [200]},
                    {"id": "child", "interaction": "search-reindexes-product-catalog", "request": {"method": "GET", "path": "/internal/v1/products/${product_id}/snapshot"}}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probes.yaml"
            path.write_text(yaml.safe_dump(template), encoding="utf-8")
            fake = SimpleNamespace(operation=lambda *args: ("/fake", None), validate=lambda *args: SimpleNamespace(ok=True, problems=[]))
            with patch.object(verify_providers, "Contract", return_value=fake), patch.object(verify_providers, "_send", return_value=(200, {"content-type": "application/json"}, b'{"products": []}')), patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(verify_providers.run(path, set(), True, 1, strict=True), 1)
            fake.validate = lambda *args: SimpleNamespace(ok=False, problems=["fixture"])
            with patch.object(verify_providers, "Contract", return_value=fake), patch.object(verify_providers, "_send", return_value=(200, {}, b'{}')), patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(verify_providers.run(path, {"child"}, True, 1, strict=True), 1)


if __name__ == "__main__":
    unittest.main()

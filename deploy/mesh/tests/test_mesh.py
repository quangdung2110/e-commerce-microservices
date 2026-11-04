"""Checks that the generated mesh manifests say what docs/mesh/runtime-contract.md says (mesh ADR-001).

These tests need no cluster: they read the YAML under deploy/mesh/generated and evaluate AuthorizationPolicy rules the way
Istio documents them (ALLOW: a request passes if some rule matches; exact, prefix and suffix path matches). They prove the
manifests agree with the contract. They do NOT prove enforcement; that needs the on-cluster tests of the implementation plan.
"""
from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

import yaml

MESH = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MESH))

import generate  # noqa: E402

GENERATED = MESH / "generated"
CONTRACT_TEXT = generate.CONTRACT.read_text(encoding="utf-8")


def load_all(name: str) -> list[dict]:
    return [d for d in yaml.safe_load_all((GENERATED / name).read_text(encoding="utf-8")) if d]


def principal(token: str) -> str:
    return generate.principal(token)


class Policies:
    """Evaluates the generated L7 policies of `ecommerce`."""

    def __init__(self):
        self.by_service = {}
        for doc in load_all("41-authorizationpolicies-ecommerce.yaml"):
            for ref in doc["spec"].get("targetRefs", []):
                if ref["kind"] == "Service":
                    self.by_service[ref["name"]] = doc["spec"].get("rules", [])

    @staticmethod
    def _path_matches(patterns: list[str], path: str) -> bool:
        return any(generate.Pattern(value, value, False).matches(path) for value in patterns)

    def allows(self, service: str, who: str, method: str, path: str) -> bool:
        for rule in self.by_service.get(service, []):
            sources = [p for f in rule.get("from", []) for p in f["source"].get("principals", [])]
            if who not in sources:
                continue
            for to in rule.get("to", []):
                operation = to["operation"]
                if operation.get("methods") and method not in operation["methods"]:
                    continue
                if not self._path_matches(operation.get("paths", []), path):
                    continue
                if self._path_matches(operation.get("notPaths", []), path):
                    continue
                return True
        return False

    def principals_for(self, service: str) -> set[str]:
        return {p for rule in self.by_service.get(service, []) for f in rule.get("from", []) for p in f["source"].get("principals", [])}


STRANGER = principal("ecommerce/stranger-service")


class GeneratedFilesTest(unittest.TestCase):
    def test_the_files_on_disk_are_what_the_generator_produces(self):
        for name, content in generate.build().items():
            on_disk = (GENERATED / name).read_text(encoding="utf-8").replace("\r\n", "\n")
            self.assertEqual(content, on_disk, f"{name} is stale: run python deploy/mesh/generate.py")

    def test_no_stray_files(self):
        self.assertEqual(sorted(generate.build()), sorted(p.name for p in GENERATED.iterdir()))


class AuthorizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policies = Policies()
        cls.rules = generate.parse_ecommerce_rules(CONTRACT_TEXT)

    def test_every_row_of_section_5_2_became_a_rule(self):
        rows = [cells for cells in generate.table_rows(generate.section(CONTRACT_TEXT, "### 5.2", "### 5.3")) if generate.ticks(cells[0])]
        self.assertEqual(len(rows), len(self.rules))
        self.assertGreaterEqual(len(self.rules), 40)
        for rule in self.rules:
            self.assertTrue(rule.principals, f"{rule.destination}: no principal parsed from {rule.source!r}")
            self.assertTrue(rule.patterns, f"{rule.destination}: no path parsed")
            self.assertIn(rule.destination, self.policies.by_service)

    def test_each_listed_caller_may_send_and_a_stranger_may_not(self):
        """The positive and negative test of every row (implementation plan 4.2), evaluated on the generated policies."""
        for rule in self.rules:
            for contract_path in {p.contract for p in rule.patterns}:
                for path in generate.samples(contract_path):
                    for method in (rule.methods or ["GET"]):
                        for who in rule.principals:
                            self.assertTrue(self.policies.allows(rule.destination, who, method, path),
                                            f"{who} should reach {rule.destination} {method} {path}")
                        self.assertFalse(self.policies.allows(rule.destination, STRANGER, method, path),
                                         f"a stranger must not reach {rule.destination} {method} {path}")

    def test_a_method_the_row_does_not_list_is_refused(self):
        for rule in self.rules:
            if not rule.methods:
                continue
            outsider = "DELETE" if "DELETE" not in rule.methods else "PATCH"
            for contract_path in {p.contract for p in rule.patterns}:
                path = generate.samples(contract_path)[-1]
                for who in rule.principals:
                    # another row may grant the same caller that method on the same path; only flag grants nobody wrote
                    granted = any(other.destination == rule.destination and who in other.principals
                                  and (other.methods is None or outsider in other.methods)
                                  and any(p.matches(path) for p in other.patterns) for other in self.rules)
                    if not granted:
                        self.assertFalse(self.policies.allows(rule.destination, who, outsider, path),
                                         f"{who} must not {outsider} {rule.destination} {path}")

    def test_order_cannot_reach_the_public_stripe_webhook(self):
        order = principal("ecommerce/order-service")
        self.assertTrue(self.policies.allows("payment-service", order, "POST", "/v1/payments/initiate"))
        self.assertFalse(self.policies.allows("payment-service", order, "POST", "/v1/payments/webhook/stripe"))
        self.assertFalse(self.policies.allows("payment-service", order, "GET", "/v1/payments/webhook/stripe"))
        self.assertTrue(self.policies.allows("payment-service", principal("edge/traefik"), "POST", "/v1/payments/webhook/stripe"))

    def test_only_traefik_reaches_public_routes_and_only_named_services_reach_internal_ones(self):
        traefik = principal("edge/traefik")
        for service in ("cart-service", "order-service", "checkout-service"):
            self.assertFalse(self.policies.allows(service, principal("ecommerce/search-service"), "GET", "/api/v1/carts/mine"))
        self.assertTrue(self.policies.allows("cart-service", traefik, "GET", "/api/v1/carts/mine"))
        # Traefik is not a caller of any internal route
        self.assertFalse(self.policies.allows("cart-service", traefik, "GET", "/internal/v1/carts/u1/checkout-snapshot"))
        self.assertFalse(self.policies.allows("inventory-service", traefik, "POST", "/internal/inventory/v1/allocation-previews"))

    def test_cart_and_checkout_only_may_call_product_grpc(self):
        grpc = "/ecommerce.product.v1.ProductService/GetSku"
        for allowed in ("cart-service", "checkout-service"):
            self.assertTrue(self.policies.allows("product-service", principal(f"ecommerce/{allowed}"), "POST", grpc))
        for denied in ("search-service", "order-service", "promotion-service"):
            self.assertFalse(self.policies.allows("product-service", principal(f"ecommerce/{denied}"), "POST", grpc))

    def test_default_deny_exists_in_every_namespace(self):
        waypoint = [d for d in load_all("41-authorizationpolicies-ecommerce.yaml") if d["metadata"]["name"] == "deny-all"][0]
        self.assertEqual("Gateway", waypoint["spec"]["targetRefs"][0]["kind"])
        self.assertNotIn("rules", waypoint["spec"])
        baseline = [d for d in load_all("41-authorizationpolicies-ecommerce.yaml") if d["metadata"]["name"] == "l4-baseline"][0]
        self.assertEqual("ALLOW", baseline["spec"]["action"])
        l4 = {(d["metadata"]["namespace"], d["metadata"]["name"]): d for d in load_all("40-authorizationpolicies-l4.yaml")}
        for namespace in ("edge", "observability"):
            self.assertIn((namespace, "deny-all"), l4)
            self.assertEqual({"action": "ALLOW"}, l4[(namespace, "deny-all")]["spec"])

    def test_edge_and_observability_policies_follow_5_1_and_5_3(self):
        l4 = {d["metadata"]["name"]: d for d in load_all("40-authorizationpolicies-l4.yaml") if d["metadata"]["namespace"] == "edge"}
        gateway_auth = l4["allow-gateway-auth"]["spec"]["rules"]
        sources = {p for r in gateway_auth for f in r.get("from", []) for p in f["source"]["principals"]}
        self.assertIn(principal("edge/traefik"), sources)
        self.assertIn(principal("observability/prometheus"), sources)
        self.assertNotIn(principal("ecommerce/search-service"), sources)
        traefik_rules = l4["allow-traefik"]["spec"]["rules"]
        public = [r for r in traefik_rules if "from" not in r][0]
        self.assertEqual(["80", "443"], public["to"][0]["operation"]["ports"])
        otel = [d for d in load_all("40-authorizationpolicies-l4.yaml") if d["metadata"]["name"] == "allow-otel-collector"][0]
        self.assertEqual({"edge", "ecommerce", "istio-system"}, set(otel["spec"]["rules"][0]["from"][0]["source"]["namespaces"]))


class IdentityAndTransportTest(unittest.TestCase):
    def test_local_provider_exceptions_are_bound_to_destination_and_port(self):
        docs = {d["metadata"]["name"]: d for d in load_all("41-authorizationpolicies-ecommerce.yaml")}
        for name, port in (("mailpit", "1025"), ("localstack", "4566")):
            spec = docs[f"allow-local-provider-{name}"]["spec"]
            self.assertEqual({"matchLabels": {"app.kubernetes.io/name": name}}, spec["selector"])
            self.assertEqual([{"from": [{"source": {"principals": [principal("ecommerce/notification-service")]}}],
                               "to": [{"operation": {"ports": [port]}}]}], spec["rules"])

    def test_health_checks_do_not_open_other_actuator_paths_or_methods(self):
        policy = Policies()
        checkout = principal("ecommerce/checkout-service")
        self.assertTrue(policy.allows("promotion-service", checkout, "GET", "/actuator/health/readiness"))
        self.assertFalse(policy.allows("promotion-service", checkout, "POST", "/actuator/health/readiness"))
        self.assertFalse(policy.allows("promotion-service", checkout, "GET", "/actuator/env"))
        self.assertFalse(policy.allows("promotion-service", principal("ecommerce/order-service"), "GET", "/actuator/health/readiness"))

    def test_only_the_waypoint_can_connect_directly_to_business_pods(self):
        baseline = next(d for d in load_all("41-authorizationpolicies-ecommerce.yaml")
                        if d["metadata"]["name"] == "l4-baseline")
        self.assertEqual([{"from": [{"source": {"principals": [principal("ecommerce/ecommerce-waypoint")]}}]}],
                         baseline["spec"]["rules"])

    def test_prometheus_only_gets_exact_metrics_endpoints(self):
        topology = yaml.safe_load((MESH / "topology.yaml").read_text(encoding="utf-8"))
        for doc in load_all("41-authorizationpolicies-ecommerce.yaml"):
            refs = [r for r in doc["spec"].get("targetRefs", []) if r["kind"] == "Service"]
            if not refs:
                continue
            service = topology["services"][refs[0]["name"]]
            grants = [r for r in doc["spec"]["rules"] if any(principal("observability/prometheus") in
                      f["source"].get("principals", []) for f in r["from"])]
            self.assertEqual(1 if service.get("metrics_path") else 0, len(grants))
            for grant in grants:
                self.assertEqual([{"operation": {"paths": [service["metrics_path"]], "methods": ["GET"],
                                  "ports": [str(service["ports"]["http"])]}}], grant["to"])

    def test_every_grant_is_bound_to_its_listener_port(self):
        for doc in load_all("41-authorizationpolicies-ecommerce.yaml"):
            if not any(r["kind"] == "Service" for r in doc["spec"].get("targetRefs", [])):
                continue
            for rule in doc["spec"]["rules"]:
                self.assertTrue(rule["to"][0]["operation"]["ports"], doc["metadata"]["name"])

    def test_similarly_named_routes_cannot_inherit_a_suffix_grant(self):
        policies = Policies()
        who = principal("ecommerce/checkout-service")
        self.assertFalse(policies.allows("user-service", who, "GET", "/api/v1/admin/checkout-snapshot"))
        self.assertFalse(policies.allows("cart-service", who, "GET", "/internal/other/checkout-snapshot"))
        self.assertFalse(policies.allows("inventory-service", who, "POST", "/internal/inventory/v1/reservations"))

    def test_every_service_has_its_own_service_account_and_none_mounts_a_token(self):
        accounts = {(d["metadata"]["namespace"], d["metadata"]["name"]): d for d in load_all("10-serviceaccounts.yaml")}
        for service in yaml.safe_load(generate.SERVICES.read_text(encoding="utf-8"))["services"]:
            if service["id"] == "api-gateway":
                continue
            self.assertIn(("ecommerce", service["id"]), accounts, f"no ServiceAccount for {service['id']}")
        for (_, name), account in accounts.items():
            self.assertNotEqual("default", name)
            self.assertIs(False, account["automountServiceAccountToken"])

    def test_every_principal_in_a_policy_has_a_service_account(self):
        accounts = {f"cluster.local/ns/{d['metadata']['namespace']}/sa/{d['metadata']['name']}" for d in load_all("10-serviceaccounts.yaml")}
        accounts.add("cluster.local/ns/ecommerce/sa/ecommerce-waypoint")  # created by istiod for the waypoint Gateway
        referenced = set()
        for name in ("40-authorizationpolicies-l4.yaml", "41-authorizationpolicies-ecommerce.yaml"):
            for doc in load_all(name):
                for rule in doc["spec"].get("rules", []):
                    for source in rule.get("from", []):
                        referenced.update(source["source"].get("principals", []))
        self.assertLessEqual(referenced, accounts, f"principals without a ServiceAccount: {sorted(referenced - accounts)}")

    def test_mtls_is_strict_everywhere_except_explicit_public_edge_ports(self):
        docs = load_all("20-peerauthentication.yaml")
        mesh_wide = [d for d in docs if d["metadata"]["namespace"] == "istio-system"][0]
        self.assertEqual("STRICT", mesh_wide["spec"]["mtls"]["mode"])
        for doc in docs:
            self.assertEqual("STRICT", doc["spec"]["mtls"]["mode"])
            permissive = {port for port, v in doc["spec"].get("portLevelMtls", {}).items() if v["mode"] == "PERMISSIVE"}
            if permissive:
                self.assertEqual("edge", doc["metadata"]["namespace"])
                workload = doc["spec"]["selector"]["matchLabels"]["app.kubernetes.io/name"]
                self.assertEqual({"traefik": {"80", "443"}, "frontend": {"3000"}}[workload], permissive)

    def test_namespaces_carry_the_ambient_and_waypoint_labels(self):
        namespaces = {d["metadata"]["name"]: d["metadata"]["labels"] for d in load_all("00-namespaces.yaml")}
        for name in ("edge", "ecommerce", "observability"):
            self.assertEqual("ambient", namespaces[name]["istio.io/dataplane-mode"])
        self.assertEqual("ecommerce-waypoint", namespaces["ecommerce"]["istio.io/use-waypoint"])
        self.assertNotIn("istio.io/dataplane-mode", namespaces["data"])
        self.assertTrue(all("istio-injection" not in labels for labels in namespaces.values()))

    def test_the_waypoint_keeps_two_replicas(self):
        docs = {d["kind"]: d for d in load_all("30-waypoint.yaml")}
        self.assertEqual("istio-waypoint", docs["Gateway"]["spec"]["gatewayClassName"])
        self.assertGreaterEqual(docs["HorizontalPodAutoscaler"]["spec"]["minReplicas"], 2)
        self.assertEqual(1, docs["PodDisruptionBudget"]["spec"]["minAvailable"])

    def test_egress_is_declared_for_stripe_and_the_email_provider(self):
        hosts = {h for d in load_all("60-serviceentries.yaml") for h in d["spec"]["hosts"]}
        self.assertIn("api.stripe.com", hosts)
        self.assertTrue(any(h.startswith("email.") for h in hosts))
        values = yaml.safe_load((MESH / "helm" / "istiod.values.yaml").read_text(encoding="utf-8"))
        self.assertEqual("REGISTRY_ONLY", values["meshConfig"]["outboundTrafficPolicy"]["mode"])
        self.assertEqual(0, values["meshConfig"]["defaultHttpRetryPolicy"]["attempts"])


def section_7_rows() -> list[list[str]]:
    return generate.table_rows(generate.section(CONTRACT_TEXT, "## 7.", "## 8."))


def parse_row(cells: list[str]) -> tuple[list[int], int | None]:
    attempts = [int(n) for n in re.findall(r"\b(\d+)\b(?:\s*lần)?", re.sub(r"`[^`]*`", "", cells[1]))]
    timeout = re.search(r"(\d+)\s*giây", cells[2])
    return attempts, int(timeout.group(1)) if timeout else None


class TrafficPolicyTest(unittest.TestCase):
    def test_multiport_fallback_targets_the_public_http_listener(self):
        topology = yaml.safe_load((MESH / "topology.yaml").read_text(encoding="utf-8"))
        for doc in load_all("50-virtualservices.yaml"):
            self.assertEqual(topology["services"][doc["metadata"]["name"]]["ports"]["http"],
                             doc["spec"]["http"][-1]["route"][0]["destination"]["port"]["number"])

    @classmethod
    def setUpClass(cls):
        cls.policy = yaml.safe_load((MESH / "traffic-policy.yaml").read_text(encoding="utf-8"))
        cls.rows = section_7_rows()
        cls.virtual_services = {d["metadata"]["name"]: d for d in load_all("50-virtualservices.yaml")}
        cls.policies = Policies()

    def row_for(self, needle: str) -> list[str]:
        hits = [row for row in self.rows if needle in row[0]]
        self.assertEqual(1, len(hits), f"{needle!r} should identify exactly one row of section 7, found {len(hits)}")
        return hits[0]

    def test_every_route_agrees_with_its_row_of_section_7(self):
        for route in self.policy["routes"]:
            needles = route["doc_row"] if isinstance(route["doc_row"], list) else [route["doc_row"]]
            for needle in needles:
                attempts, timeout = parse_row(self.row_for(needle))
                index = route.get("doc_value", 0)
                self.assertEqual(attempts[index], route["attempts"], f"{route['id']}: attempts differ from the row {needle!r}")
                self.assertEqual(timeout, route["timeout_s"], f"{route['id']}: timeout differs from the row {needle!r}")

    def test_no_row_of_section_7_is_ignored_except_the_public_one(self):
        used = set()
        for route in self.policy["routes"]:
            for needle in route["doc_row"] if isinstance(route["doc_row"], list) else [route["doc_row"]]:
                used.add(self.row_for(needle)[0])
        unused = [row[0] for row in self.rows if row[0] not in used]
        self.assertEqual(1, len(unused), f"rows of section 7 without a route: {unused}")
        self.assertIn("Route public", unused[0])

    def test_every_route_is_matched_by_the_virtual_service_and_has_a_caller_allowed_by_section_5_2(self):
        for route in self.policy["routes"]:
            vs = self.virtual_services[route["service"]]
            generated = next(r for r in vs["spec"]["http"] if r["name"] == route["id"])
            for example in route["examples"]:
                self.assertTrue(self._vs_matches(generated, example["method"], example["path"]),
                                f"{route['id']} does not match {example}")
                callers = [p for p in self.policies.principals_for(route["service"])
                           if self.policies.allows(route["service"], p, example["method"], example["path"])]
                self.assertTrue(callers, f"{route['id']}: nobody may send {example}; section 5.2 has no grant for it")

    @staticmethod
    def _vs_matches(http_route: dict, method: str, path: str) -> bool:
        for match in http_route["match"]:
            if "method" in match:
                spec = match["method"]
                if "exact" in spec and spec["exact"] != method:
                    continue
                if "regex" in spec and not re.fullmatch(spec["regex"], method):
                    continue
            uri = match["uri"]
            ok = (("exact" in uri and uri["exact"] == path) or ("prefix" in uri and path.startswith(uri["prefix"]))
                  or ("regex" in uri and re.fullmatch(uri["regex"], path)))
            if ok:
                return True
        return False

    def test_every_internal_grant_of_section_5_2_has_a_route(self):
        """A caller may only use a route that section 7 gives a timeout and a retry decision (contract rule after the table)."""
        routes_by_service: dict[str, list[dict]] = {}
        for route in self.policy["routes"]:
            routes_by_service.setdefault(route["service"], []).append(route)
        missing = []
        for rule in generate.parse_ecommerce_rules(CONTRACT_TEXT):
            if any(p.endswith("/sa/traefik") for p in rule.principals):
                continue  # public routes: Traefik owns timeouts
            for contract_path in {p.contract for p in rule.patterns}:
                # The base path of a subtree grant (`/x/**` also allows `/x`) is not necessarily a route; the deepest sample is.
                for path, method in ((generate.samples(contract_path)[-1], m) for m in (rule.methods or ["GET"])):
                    covered = any(self._vs_matches({"match": [self._route_match(r)]}, method, path)
                                  for r in routes_by_service.get(rule.destination, []))
                    if not covered:
                        missing.append(f"{rule.destination} {method} {path}")
        self.assertEqual([], missing)

    @staticmethod
    def _route_match(route: dict) -> dict:
        match: dict = {"uri": {"exact": route["exact"]} if "exact" in route else {"prefix": route["prefix"]} if "prefix" in route else {"regex": route["regex"]}}
        if route.get("methods"):
            match["method"] = {"regex": "|".join(route["methods"])}
        return match

    def test_a_route_that_is_not_marked_retriable_never_retries_and_never_leaves_attempts_implicit(self):
        for vs in self.virtual_services.values():
            for http_route in vs["spec"]["http"]:
                if http_route["name"] == "default":
                    continue
                self.assertIn("attempts", http_route["retries"], f"{vs['metadata']['name']}/{http_route['name']} leaves attempts implicit")
                if http_route["retries"]["attempts"] == 0:
                    self.assertNotIn("retryOn", http_route["retries"])

    def test_post_routes_retry_only_where_the_contract_says_it_is_safe(self):
        allowed = {"product-grpc", "auth-delivery-content"}  # gRPC read RPCs; idempotent by delivery_id (runtime-contract section 7)
        for route in self.policy["routes"]:
            if route["attempts"] > 0 and "POST" in route.get("methods", ["POST"]):
                self.assertIn(route["id"], allowed, f"{route['id']} retries a POST")

    def test_retries_only_cover_connection_level_failures(self):
        safe = {"connect-failure", "refused-stream", "unavailable"}
        for route in self.policy["routes"]:
            if route["attempts"] > 0:
                self.assertLessEqual(set(route["retry_on"].split(",")), safe, route["id"])

    def test_every_service_with_a_policy_has_outlier_detection(self):
        rules = {d["metadata"]["name"]: d for d in load_all("51-destinationrules.yaml")}
        for service in self.virtual_services:
            outlier = rules[service]["spec"]["trafficPolicy"]["outlierDetection"]
            self.assertEqual(5, outlier["consecutive5xxErrors"])
            self.assertEqual("30s", outlier["baseEjectionTime"])
            self.assertEqual(50, outlier["maxEjectionPercent"])

    def test_every_virtual_service_ends_with_a_catch_all_so_public_routes_are_not_blocked(self):
        for name, vs in self.virtual_services.items():
            last = vs["spec"]["http"][-1]
            self.assertEqual("default", last["name"], name)
            self.assertNotIn("match", last)


class PatternTest(unittest.TestCase):
    def test_translation_rules(self):
        cases = {
            "/api/v1/auth/**": [("/api/v1/auth", False), ("/api/v1/auth/*", False)],
            "/internal/v1/reviews/rating-aggregate-exports**": [("/internal/v1/reviews/rating-aggregate-exports*", False)],
            "/v1/payments/initiate": [("/v1/payments/initiate", False)],
            "/internal/v1/carts/*/checkout-snapshot": [("/internal/v1/carts/{*}/checkout-snapshot", False)],
            "/v1/payments/{transactionId}/cancel": [("/v1/payments/{*}/cancel", False)],
            "/v1/payments/{transactionId}": [("/v1/payments/{*}", False)],
            "/internal/v1/users/*/addresses/*/checkout-snapshot": [("/internal/v1/users/{*}/addresses/{*}/checkout-snapshot", False)],
        }
        for path, expected in cases.items():
            actual = [(p.value, p.broadened) for p in generate.to_patterns(path)]
            self.assertEqual(expected, actual, path)

    def test_a_subtree_pattern_covers_its_base_path(self):
        """`/api/v1/products/**` is the collection and everything below it; the Istio prefix alone would drop the collection."""
        patterns = generate.to_patterns("/internal/inventory/v1/reservations/**")
        for path in ("/internal/inventory/v1/reservations", "/internal/inventory/v1/reservations/o1/commit"):
            self.assertTrue(any(p.matches(path) for p in patterns), path)
        self.assertFalse(any(p.matches("/internal/inventory/v1/reservationsX") for p in patterns))

    def test_alternatives_expand_but_parameters_do_not(self):
        self.assertEqual(
            ["/p/reservations", "/p/cancellations", "/p/confirmations"],
            generate.expand_alternatives("/p/{reservations,cancellations,confirmations}"))
        self.assertEqual(["/v1/payments/{transactionId}"], generate.expand_alternatives("/v1/payments/{transactionId}"))


class HelmValuesTest(unittest.TestCase):
    def test_all_four_mesh_images_are_digest_pinned(self):
        digests = yaml.safe_load((MESH / "versions.yaml").read_text(encoding="utf-8"))["istio"]["image_digests"]
        istiod = yaml.safe_load((MESH / "helm/istiod.values.yaml").read_text(encoding="utf-8"))
        cni = yaml.safe_load((MESH / "helm/cni.values.yaml").read_text(encoding="utf-8"))
        ztunnel = yaml.safe_load((MESH / "helm/ztunnel.values.yaml").read_text(encoding="utf-8"))
        for image, value in (("pilot", istiod["image"]), ("proxyv2", istiod["global"]["proxy"]["image"]),
                             ("install-cni", cni["image"]), ("ztunnel", ztunnel["image"])):
            self.assertRegex(digests[image], r"^sha256:[0-9a-f]{64}$")
            self.assertEqual(f"docker.io/istio/{image}@{digests[image]}", value)

    def test_application_services_declare_their_protocols(self):
        topology = yaml.safe_load((MESH / "topology.yaml").read_text(encoding="utf-8"))
        for name, service in topology["services"].items():
            path = MESH.parents[1] / "deploy/k8s/services" / f"{name}.yaml"
            if not path.exists():  # Planned workloads do not have a runtime yet.
                continue
            resource = next(d for d in yaml.safe_load_all(path.read_text(encoding="utf-8"))
                            if d["kind"] == "Service" and d["metadata"]["name"] == name)
            for port in resource["spec"]["ports"]:
                self.assertEqual("grpc" if port["name"] == "grpc" else "http", port["appProtocol"], name)

    def test_every_values_file_pins_the_version_of_versions_yaml(self):
        version = yaml.safe_load((MESH / "versions.yaml").read_text(encoding="utf-8"))["istio"]["version"]
        self.assertNotIn("latest", version)
        for name, key in (("istiod", ("global", "tag")), ("cni", ("global", "tag")), ("ztunnel", ("tag",))):
            values = yaml.safe_load((MESH / "helm" / f"{name}.values.yaml").read_text(encoding="utf-8"))
            node = values
            for part in key:
                node = node[part]
            self.assertEqual(version, str(node), f"{name} values pin a different Istio version")


if __name__ == "__main__":
    unittest.main()

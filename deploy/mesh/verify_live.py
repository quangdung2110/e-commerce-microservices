#!/usr/bin/env python3
"""Exercise real application images through Istio: spoofing, Pod IP, plaintext, gRPC, metrics and RBAC.

Run after local.py stack. Test pods impersonate caller workloads only by using their real ServiceAccounts;
the forged values in requests are independent. No payment, refund or reservation is created.
"""
from __future__ import annotations

import datetime
import json
import subprocess
import sys
import time
from pathlib import Path

import yaml

from local import CONFIG, CONTEXT, MESH, ROOT, apply, guard, kube, run, tool

IMAGE = "ecommerce-mesh-probe:local"
CALLERS = {"checkout": ("ecommerce", "checkout-service"), "order": ("ecommerce", "order-service"),
           "finance": ("ecommerce", "finance-ops"), "scraper": ("observability", "prometheus"),
           "plain": ("ecommerce", "checkout-service"), "gateway": ("edge", "traefik"),
           "inbound-plain": ("edge", "traefik")}


def request(caller, spec):
    namespace, _ = CALLERS[caller]
    output = kube("-n", namespace, "exec", f"mesh-probe-{caller}", "--", "python", "/probe.py",
                  json.dumps(spec), capture=True)
    return json.loads(output)


def prepare():
    run(["docker", "build", "-f", MESH / "tests/probe.Dockerfile", "-t", IMAGE, MESH / "tests"])
    run([tool("kind"), "load", "docker-image", "--name", "ecommerce-mesh", IMAGE])
    pods = []
    for name, (namespace, account) in CALLERS.items():
        labels = {"app.kubernetes.io/name": f"mesh-probe-{name}", "mesh.ecommerce/test": "p1"}
        if name in ("plain", "inbound-plain"):
            labels["istio.io/dataplane-mode"] = "none"
        pods.append({"apiVersion": "v1", "kind": "Pod", "metadata": {
            "name": f"mesh-probe-{name}", "namespace": namespace, "labels": labels}, "spec": {
            "serviceAccountName": account, "automountServiceAccountToken": False,
            "containers": [{"name": "probe", "image": IMAGE, "imagePullPolicy": "Never", "resources": {
                "requests": {"cpu": "10m", "memory": "32Mi"}, "limits": {"memory": "128Mi"}},
                "securityContext": {"allowPrivilegeEscalation": False, "capabilities": {"drop": ["ALL"]}}}]}})
        if name == "checkout":
            # Exercise HBONE across nodes as well as same-node traffic.
            pods[-1]["spec"].update({"nodeSelector": {"kubernetes.io/hostname": "ecommerce-mesh-control-plane"},
                                    "tolerations": [{"key": "node-role.kubernetes.io/control-plane", "effect": "NoSchedule"}]})
    apply(pods)
    for namespace in ("ecommerce", "observability", "edge"):
        kube("-n", namespace, "wait", "--for=condition=Ready", "pod", "-l", "mesh.ecommerce/test=p1", "--timeout=180s")


def main():
    guard()
    if "--reuse-probes" not in sys.argv:
        prepare()
    for service in ("inventory-service", "payment-service", "product-service"):
        kube("-n", "ecommerce", "rollout", "status", f"deployment/{service}", "--timeout=300s")
    results = []

    def check(name, caller, spec, allowed, contains=None):
        result = request(caller, spec)
        passed = result["status"] in allowed and (contains is None or contains in result.get("body", ""))
        results.append({"name": name, "caller": caller, "request": spec, "response": result, "passed": passed})
        print(f"{'PASS' if passed else 'FAIL'} {name}: {result['status']}", flush=True)

    def http(service, path, identity, **kwargs):
        return {"target": f"http://{service}.ecommerce.svc.cluster.local:8080{path}", "identity": identity, **kwargs}

    inventory_path = "/internal/inventory/v1/reservations/mesh-no-such-order"
    payment_path = "/v1/payments/TXN_00000000000000000000000000"
    preview = {"lines": [{"line_reference": "mesh-test", "sku_id": "mesh-no-such-sku", "quantity": 1}]}
    check("checkout legitimate preview", "checkout", http("inventory-service",
          "/internal/inventory/v1/allocation-previews", "checkout-service", method="POST", body=preview), [200])
    check("checkout legitimate promotion readiness", "checkout", {
          "target": "http://promotion-service.ecommerce:8085/actuator/health/readiness", "identity": "checkout-service"}, [200])
    check("order cannot borrow checkout health permission", "order", {
          "target": "http://promotion-service.ecommerce:8085/actuator/health/readiness", "identity": "checkout-service"}, [403], "RBAC")
    check("gateway legitimate inventory health", "gateway", http("inventory-service", "/actuator/health", "traefik"), [200])
    check("gateway cannot borrow order payment permission", "gateway", http("payment-service", payment_path, "order-service"), [403], "RBAC")
    check("order legitimate inventory query", "order", http("inventory-service", inventory_path, "order-service"), [404], "NOT_FOUND")
    check("checkout spoofing order denied", "checkout", http("inventory-service", inventory_path, "order-service"), [403], "RBAC")
    check("order spoofing checkout denied", "order", http("inventory-service",
          "/internal/inventory/v1/allocation-previews", "checkout-service", method="POST", body=preview), [403], "RBAC")
    check("order legitimate payment query", "order", http("payment-service", payment_path, "order-service"), [404], "PAYMENT_NOT_FOUND")
    check("checkout spoofing payment caller denied", "checkout", http("payment-service", payment_path, "order-service"), [403], "RBAC")
    check("order missing attribution header denied by application", "order", {
          "target": http("payment-service", payment_path, "order-service")["target"]}, [401], "UNAUTHENTICATED")
    check("finance legitimate journal query", "finance", http("payment-service",
          "/internal/v1/payment/ledger/journals/FJN_00000000000000000000000000", "finance-ops",
          headers={"X-Actor": "mesh-finance-operator"}), [404], "FINANCIAL_JOURNAL_NOT_FOUND")
    check("order spoofing finance denied", "order", http("payment-service", "/internal/v1/payment/review-cases", "finance-ops",
          headers={"X-Actor": "mesh-finance-operator"}), [403], "RBAC")
    absent = "00000000-0000-0000-0000-000000000000"
    cart = f"http://cart-service.ecommerce:8080/internal/v1/carts/{absent}/checkout-snapshot"
    user = f"http://user-service.ecommerce:8083/internal/v1/users/{absent}/addresses/{absent}/checkout-snapshot"
    check("Go Cart legitimate checkout read", "checkout", {"target": cart, "identity": "checkout-service"}, [200], '"items"')
    check("Go Cart spoofed checkout denied", "order", {"target": cart, "identity": "checkout-service"}, [403], "RBAC")
    check("Go User legitimate address lookup", "checkout", {"target": user, "identity": "checkout-service"}, [404])
    check("Go User spoofed checkout denied", "order", {"target": user, "identity": "checkout-service"}, [403], "RBAC")
    check("Go User internal route on public listener denied", "checkout", {
          "target": user.replace(":8083/", ":8082/"), "identity": "checkout-service"}, [403], "RBAC")
    check("forged user ADMIN from checkout denied", "checkout", http("inventory-service", "/api/v1/admin/inventory/warehouses",
          "order-service", headers={"X-User-Id": absent, "X-User-Roles": "ADMIN"}), [403], "RBAC")
    check("public Frontend remains reachable", "plain", {"target": "http://frontend.edge:3000/"}, [200], "<!DOCTYPE html>")
    check("gateway still requires user authentication", "plain", {"target": "http://api-gateway.edge:80/api/v1/products"}, [401])
    for service, path in (("inventory-service", inventory_path), ("payment-service", payment_path)):
        pod = json.loads(kube("-n", "ecommerce", "get", "pods", "-l", f"app.kubernetes.io/name={service}", "-o", "json", capture=True))["items"][0]
        ip = pod["status"]["podIP"]
        # Service-scoped grants intentionally do not authorize direct workload addressing.
        check(f"{service} Pod IP closed even to order", "order", {"target": f"http://{ip}:8080{path}", "identity": "order-service"}, [403], "RBAC")
        check(f"{service} Pod IP forged order denied", "checkout", {"target": f"http://{ip}:8080{path}", "identity": "order-service"}, [403], "RBAC")
        # Use a source outside ecommerce's egress policy to test the destination's mTLS rejection.
        check(f"{service} plaintext forged order denied", "inbound-plain", {"target": f"http://{ip}:8080{path}", "identity": "order-service"}, ["CONNECTION_REJECTED"])
        check(f"{service} scraper legitimate metrics", "scraper", http(service, "/actuator/prometheus", "prometheus"), [200])
        check(f"{service} scraper forged order denied", "scraper", http(service, path, "order-service"), [403], "RBAC")
        check(f"{service} scraper Pod IP forged order denied", "scraper", {"target": f"http://{ip}:8080{path}", "identity": "order-service"}, [403], "RBAC")
        check(f"{service} metrics POST denied", "scraper", http(service, "/actuator/prometheus", "prometheus", method="POST"), [403], "RBAC")
    product = json.loads(kube("-n", "ecommerce", "get", "pods", "-l", "app.kubernetes.io/name=product-service", "-o", "json", capture=True))["items"][0]
    for target in ("product-service.ecommerce.svc.cluster.local:9091", f"{product['status']['podIP']}:9091"):
        direct = target.startswith(product["status"]["podIP"] + ":")
        check(f"gRPC checkout {'Pod IP closed' if direct else 'legitimate Service DNS'}", "checkout",
              {"protocol": "grpc", "target": target, "identity": "checkout-service"}, ["PERMISSION_DENIED"] if direct else ["OK"])
        check(f"gRPC order spoofing checkout {target}", "order", {"protocol": "grpc", "target": target, "identity": "checkout-service"}, ["PERMISSION_DENIED"])

    # Kubernetes authorization: the caller cannot change workload identity or mint Order's token.
    for namespace, account in set(CALLERS.values()):
        for verb, resource in (("patch", "deployments"), ("create", "pods"), ("create", "serviceaccounts/token")):
            command = [tool("kubectl"), "--kubeconfig", CONFIG, "--context", CONTEXT, "auth", "can-i", verb, resource,
                       "-n", "ecommerce", f"--as=system:serviceaccount:{namespace}:{account}"]
            result = subprocess.run(list(map(str, command)), capture_output=True, text=True)
            passed = result.returncode == 1 and result.stdout.strip() == "no"
            results.append({"name": f"RBAC {namespace}/{account} cannot {verb} {resource}", "passed": passed,
                            "response": result.stdout.strip()})
            print(f"{'PASS' if passed else 'FAIL'} {results[-1]['name']}", flush=True)
    evidence = ROOT / "docs/security/mesh-p1-evidence-2026-11-04"
    evidence.mkdir(parents=True, exist_ok=True)
    # Keep this independent application issue visible; it is not counted as a successful authorized read.
    diagnostic = request("finance", http("payment-service", "/internal/v1/payment/review-cases", "finance-ops",
                                        headers={"X-Actor": "mesh-finance-operator"}))
    report = {"timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(), "context": CONTEXT,
              "application_diagnostics": {"finance_review_cases": diagnostic},
              "passed": sum(r["passed"] for r in results), "total": len(results), "results": results}
    (evidence / "results.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    pods = json.loads(kube("get", "pods", "-A", "-o", "json", capture=True))["items"]
    summary = [{"namespace": p["metadata"]["namespace"], "name": p["metadata"]["name"],
                "account": p["spec"].get("serviceAccountName"), "node": p["spec"].get("nodeName"),
                "ip": p["status"].get("podIP"), "phase": p["status"]["phase"],
                "containers": [{"image": c["image"], "imageID": c.get("imageID"), "ready": c["ready"]}
                               for c in p["status"].get("containerStatuses", [])]} for p in pods]
    (evidence / "pods.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (evidence / "policies.yaml").write_text(kube("get", "authorizationpolicies,peerauthentications", "-A", "-o", "yaml",
                                               capture=True), encoding="utf-8")
    # Public certificate metadata only; never dump private keys or Kubernetes Secrets.
    for resource in ("workloads", "certificates"):
        output = run([tool("istioctl"), "--kubeconfig", CONFIG, "--context", CONTEXT,
                      "ztunnel-config", resource, "--node", "ecommerce-mesh-worker"], capture=True)
        (evidence / f"ztunnel-{resource}.txt").write_text(output, encoding="utf-8")
    print(f"{report['passed']}/{report['total']} checks passed. Evidence: {evidence}")
    if report["passed"] != report["total"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

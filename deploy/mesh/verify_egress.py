#!/usr/bin/env python3
"""Check local Stripe egress using TLS handshakes only. Requires the existing mesh probes."""
import argparse
import datetime as dt
import json
import socket
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from local import CONTEXT, ROOT, guard, kube


STRIPE = "api.stripe.com"
WAYPOINT = "ecommerce-egress"
WAYPOINT_LABEL = f"gateway.networking.k8s.io/gateway-name={WAYPOINT}"
PAYMENT = "deployment/payment-service"
CHECKOUT = "deployment/checkout-service"


def exec_pod(pod, *command, **kwargs):
    return kube("-n", "ecommerce", "exec", pod, *command, capture=True, **kwargs)


def tls(pod, host, address):
    output = exec_pod(pod, "--", "sh", "-c", '''
        timeout 6s openssl s_client -4 -brief -connect "$1:443" -servername "$2" \
            -verify_hostname "$2" -verify_return_error </dev/null 2>&1
        printf '\nexit=%s\n' "$?"
    ''', "sh", address, host)
    diagnostics, exit_status = output.strip().rsplit("\nexit=", 1)
    status = int(exit_status)
    if status not in (0, 1, 124):
        raise RuntimeError(f"Unexpected TLS probe exit status {status} for {pod}")
    return {"verified": status == 0 and "Verification: OK" in diagnostics,
            "exit": status, "destination_resolved": "Connecting to " in diagnostics}


def waypoint_pods():
    return kube("-n", "ecommerce", "get", "pods", "-l", WAYPOINT_LABEL, "-o", "name", capture=True).split()


def waypoint_metrics():
    stripe = denied = 0
    for pod in waypoint_pods():
        stats = exec_pod(pod, "-c", "istio-proxy", "--", "pilot-agent", "request", "GET", "stats")
        for line in stats.splitlines():
            if line.startswith("tcp.rbac.denied:"):
                denied += int(line.rsplit(":", 1)[1])
            if (line.startswith("istiocustom.istio_tcp_connections_opened_total.reporter.waypoint.")
                    and "source_principal.spiffe://cluster.local/ns/ecommerce/sa/payment-service." in line
                    and f"destination_service.{STRIPE}." in line):
                stripe += int(line.rsplit(":", 1)[1])
    return stripe, denied


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".tools/mesh/p2-egress-verification")
    parser.add_argument("--test-waypoint-outage", action="store_true", help="briefly scale the local egress waypoint to zero")
    args = parser.parse_args()
    guard()
    results = []

    def record(name, passed, **facts):
        results.append({"name": name, "passed": passed, **facts})
        print(f"{'PASS' if passed else 'FAIL'} {name}", flush=True)

    try:
        stripe_ip = socket.getaddrinfo(STRIPE, 443, socket.AF_INET, socket.SOCK_STREAM)[0][4][0]
        entries = json.loads(kube("-n", "ecommerce", "get", "serviceentry", "-o", "json", capture=True))["items"]
        hosts = {host for entry in entries for host in entry["spec"]["hosts"]}
        record("AWS SES and SQS are disabled in the local registry",
               not hosts.intersection({"email.ap-southeast-1.amazonaws.com", "sqs.ap-southeast-1.amazonaws.com"}))

        stripe_before, _ = waypoint_metrics()
        payment = tls(PAYMENT, STRIPE, STRIPE)
        stripe_after, denied_before = waypoint_metrics()
        record("Payment reaches Stripe through the waypoint with its mTLS principal",
               payment["verified"] and stripe_after > stripe_before, waypoint_connections=stripe_after - stripe_before, **payment)
        checkout = tls(CHECKOUT, STRIPE, STRIPE)
        _, denied_after = waypoint_metrics()
        record("Waypoint RBAC denies Checkout on the Stripe route",
               not checkout["verified"] and checkout["destination_resolved"] and denied_after > denied_before,
               waypoint_denials=denied_after - denied_before, **checkout)

        with ThreadPoolExecutor() as pool:
            cases = [
                ("Checkout direct Stripe IP blocked", CHECKOUT, STRIPE, stripe_ip),
                ("Payment direct Stripe IP blocked", PAYMENT, STRIPE, stripe_ip),
                ("Checkout unregistered HTTPS blocked", CHECKOUT, "example.com", "example.com"),
                ("Checkout AWS SES blocked", CHECKOUT, "email.ap-southeast-1.amazonaws.com", "email.ap-southeast-1.amazonaws.com"),
                ("Checkout AWS SQS blocked", CHECKOUT, "sqs.ap-southeast-1.amazonaws.com", "sqs.ap-southeast-1.amazonaws.com"),
            ]
            for (name, *_), result in zip(cases, pool.map(lambda case: tls(*case[1:]), cases)):
                # Kindnet drops packets. A fast provider rejection or a DNS error does not prove a drop.
                record(name, result["exit"] == 124 and result["destination_resolved"], **result)

        plain = exec_pod("mesh-probe-plain", "--", "python", "-c", '''
import socket, sys
try:
    with socket.create_connection((sys.argv[1], 443), timeout=3):
        print("open")
except OSError:
    print("blocked")
''', stripe_ip).strip()
        record("Pod outside ambient capture cannot reach Stripe IP", plain == "blocked")

        if args.test_waypoint_outage:
            replicas = int(kube("-n", "ecommerce", "get", "deployment", WAYPOINT,
                                "-o", "jsonpath={.spec.replicas}", capture=True))
            if replicas < 1 or not payment["verified"]:
                raise RuntimeError("Outage probe requires a working Stripe route first")
            try:
                kube("-n", "ecommerce", "scale", f"deployment/{WAYPOINT}", "--replicas=0")
                deadline = time.monotonic() + 90
                while waypoint_pods():
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Egress waypoint did not scale down")
                    time.sleep(1)
                routed = tls(PAYMENT, STRIPE, STRIPE)
                record("Payment Stripe hostname fails during waypoint outage",
                       routed["exit"] != 0 and routed["destination_resolved"], **routed)
                direct = tls(PAYMENT, STRIPE, stripe_ip)
                record("Payment direct Stripe IP stays blocked during outage",
                       direct["exit"] == 124 and direct["destination_resolved"], **direct)
            finally:
                kube("-n", "ecommerce", "scale", f"deployment/{WAYPOINT}", f"--replicas={replicas}")
                kube("-n", "ecommerce", "rollout", "status", f"deployment/{WAYPOINT}", "--timeout=120s")
            recovered = tls(PAYMENT, STRIPE, STRIPE)
            record("Payment Stripe route recovers after the waypoint returns", recovered["verified"], **recovered)
    finally:
        args.output.mkdir(parents=True, exist_ok=True)
        report = {"timestamp": dt.datetime.now(dt.timezone.utc).isoformat(), "context": CONTEXT,
                  "passed": sum(result["passed"] for result in results), "total": len(results), "results": results}
        (args.output / "egress-results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0 if all(result["passed"] for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())

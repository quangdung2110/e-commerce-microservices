"""Read-only observability probes. Run inside the Compose network with Python 3.12.

docker run --rm --network ecommerce-network -v "${PWD}:/repo" python:3.12-alpine \
  python /repo/scripts/observability-audit.py --output /repo/docs/observability/evidence-2026-10-18

No business writes, dependency shutdowns or external provider calls are performed.
Exit 1 means an observability gap was found; unavailable services are reported separately.
"""

import argparse
import concurrent.futures
import datetime
import json
import pathlib
import re
import urllib.error
import urllib.request
import uuid


SERVICES = [
    ("search-service", 8080, "/actuator/health", "/actuator/prometheus", True),
    ("review-service", 8080, "/actuator/health", "/actuator/prometheus", True),
    ("product-service", 8081, "/actuator/health", "/actuator/prometheus", True),
    ("promotion-service", 8085, "/actuator/health", "/actuator/prometheus", True),
    ("inventory-service", 8080, "/actuator/health", "/actuator/prometheus", True),
    ("order-service", 8080, "/actuator/health", "/actuator/prometheus", True),
    ("payment-service", 8080, "/actuator/health", "/actuator/prometheus", True),
    ("checkout-service", 8080, "/actuator/health", "/actuator/prometheus", True),
    ("product-detail-bff", 8080, "/actuator/health", "/actuator/prometheus", True),
    ("cart-service", 8080, "/health/", "/metrics", False),
    ("auth-service", 8080, "/health/", "/metrics", False),
    ("user-service", 8082, "/health/", "/metrics", False),
    ("notification-service", 8081, "/", "/metrics", False),
    ("gateway-auth", 8080, "/health/", "/metrics", False),
]
ID = re.compile(r"^[A-Za-z0-9._:-]{8,128}$")
TRACE_ID = uuid.uuid4().hex
TRACEPARENT = f"00-{TRACE_ID}-00f067aa0ba902b7-01"
RUN_ID = "obs-audit-" + uuid.uuid4().hex[:12]


def request(base, path, headers=None):
    req = urllib.request.Request(base + path, headers=headers or {})
    try:
        response = urllib.request.urlopen(req, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    except (OSError, urllib.error.URLError) as error:
        return {"status": None, "error": str(error), "headers": {}, "body": ""}
    with response:
        return {"status": response.status,
                "headers": {k.lower(): v for k, v in response.headers.items()},
                "body": response.read().decode("utf-8", errors="replace")}


def metric_summary(response):
    body = response["body"] if response["status"] == 200 else ""
    names = sorted(set(re.findall(r"^(\w+)(?:\{|\s)", body, re.M)))
    labels = set()
    sensitive = set()
    http_counts = 0
    for line in body.splitlines():
        if line.startswith("#"):
            continue
        keys = re.findall(r'(\w+)="', line)
        labels.update(keys)
        sensitive.update(set(keys) & {"request_id", "trace_id", "span_id", "user_id", "email", "token"})
        if line.startswith("http_server_requests_seconds_count{") and 'uri="/actuator/prometheus"' not in line:
            http_counts += float(line.rsplit(" ", 1)[1])
        elif line.startswith("http_server_requests_total{"):
            http_counts += float(line.rsplit(" ", 1)[1])
    return {"status": response["status"], "error": response.get("error"),
            "content_type": response["headers"].get("content-type"),
            "metric_names": names, "label_keys": sorted(labels),
            "sensitive_label_keys": sorted(sensitive), "http_count": http_counts,
            "http_histogram": "http_server_requests_seconds_bucket" in names or "http_server_request_duration_seconds_bucket" in names,
            "runtime_metrics": any(n.startswith(("jvm_", "go_", "process_")) for n in names)}


def probe_service(spec):
    name, port, prefix, metrics, java = spec
    base = f"http://{name}:{port}"
    if java:
        health_paths = [prefix + "/liveness", prefix + "/readiness"]
    elif name == "notification-service":
        health_paths = ["/livez", "/readyz"]
    else:
        health_paths = [prefix + "live", prefix + "ready"]
    health = {}
    for path in health_paths:
        value = request(base, path)
        health[path] = {k: value[k] for k in ("status", "body")}
        if value.get("error"):
            health[path]["error"] = value["error"]
    before = metric_summary(request(base, metrics))
    # An unmatched API route exercises correlation/error middleware without business side effects.
    path = "/api/__observability_probe__/" + RUN_ID
    tests = []
    for case, headers, expected in [
        ("valid_request_id", {"X-Request-Id": RUN_ID + "-" + name, "traceparent": TRACEPARENT}, RUN_ID + "-" + name),
        ("missing_request_id", {}, None),
        ("short_request_id", {"X-Request-Id": "bad"}, None),
        ("invalid_characters", {"X-Request-Id": "bad id with spaces"}, None),
        ("overlong_request_id", {"X-Request-Id": "x" * 129}, None),
        ("invalid_id_with_valid_trace", {"X-Request-Id": "bad", "traceparent": TRACEPARENT}, None),
        ("malformed_trace", {"traceparent": "invalid-trace-parent"}, None),
    ]:
        response = request(base, path, headers)
        returned = response["headers"].get("x-request-id", "")
        ok = (returned == expected) if expected else bool(ID.fullmatch(returned))
        tests.append({"case": case, "status": response["status"], "request_id": returned,
                      "pass": ok and response["status"] is not None,
                      "error_response_pass": response["status"] is not None and response["status"] < 500})
    after = metric_summary(request(base, metrics))
    known_paths = {
        "product-detail-bff": "/api/v1/storefront/products/obs-audit-missing-product",
        "checkout-service": "/api/v1/checkout/submissions/invalid-audit-token",
        "gateway-auth": "/verify",
    }
    known = None
    if name in known_paths:
        response = request(base, known_paths[name], {"X-Request-Id": RUN_ID + "-known-" + name,
                                                     "traceparent": TRACEPARENT})
        known = {"path": known_paths[name], "status": response["status"],
                 "request_id": response["headers"].get("x-request-id"), "body": response["body"]}
    return {"service": name, "base": base, "health": health, "metrics": after,
            "http_count_delta": after["http_count"] - before["http_count"], "correlation": tests,
            "known_route": known}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        services = list(pool.map(probe_service, SERVICES))
    edge = {}
    for port, path in [(8090, "/metrics"), (8080, "/metrics"), (80, "/actuator/prometheus"), (80, "/metrics")]:
        response = request(f"http://api-gateway:{port}", path)
        edge[f"{port}{path}"] = {"status": response["status"], "error": response.get("error"),
                                    "has_prometheus_data": bool(re.search(r"^# (HELP|TYPE) ", response["body"], re.M))}
    reads = []
    for name, base, path in [
        ("search", "http://search-service:8080", "/api/v2/search/products?limit=1"),
        ("gateway_search", "http://api-gateway:80", "/api/v2/search/products?limit=1"),
        ("pdp", "http://product-detail-bff:8080", "/api/v1/storefront/products/camera-luma-home"),
        ("gateway_pdp", "http://api-gateway:80", "/api/v1/storefront/products/camera-luma-home"),
        ("review", "http://review-service:8080", "/api/v1/reviews/products/01a0f1ee-dea3-7b10-a024-d3ef1991df02/aggregate"),
    ]:
        response = request(base, path, {"X-Request-Id": RUN_ID + "-read-" + name, "traceparent": TRACEPARENT})
        reads.append({"name": name, "path": path, "status": response["status"],
                      "request_id": response["headers"].get("x-request-id"),
                      "error": response.get("error"),
                      "body_keys": list(json.loads(response["body"]).keys()) if response["body"].startswith("{") else []})
    result = {"run_id": RUN_ID, "trace_id": TRACE_ID, "at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "services": services, "edge": edge, "read_smoke": reads}
    (output / "runtime-probes.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    failed = False
    for service in services:
        m = service["metrics"]
        passed = sum(t["pass"] for t in service["correlation"])
        print(f'{service["service"]:22} health={[v["status"] for v in service["health"].values()]} '
              f'metrics={m["status"]} names={len(m["metric_names"])} '
              f'histogram={m["http_histogram"]} runtime={m["runtime_metrics"]} '
              f'http_delta={service["http_count_delta"]} correlation={passed}/7')
        failed |= (m["status"] != 200 or passed != 7 or bool(m["sensitive_label_keys"])
                   or not m["http_histogram"] or not m["runtime_metrics"]
                   or any(v["status"] != 200 for v in service["health"].values())
                   or any(not t["error_response_pass"] for t in service["correlation"]))
    failed |= any(read["status"] != 200 or not read["request_id"] for read in reads)
    failed |= not edge["8090/metrics"]["has_prometheus_data"]
    failed |= any(edge[key]["status"] != 404 for key in ("80/metrics", "80/actuator/prometheus"))
    print("edge:", json.dumps(edge))
    print("read_smoke:", json.dumps(reads))
    print("evidence:", output / "runtime-probes.json", "run_id:", RUN_ID)
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())

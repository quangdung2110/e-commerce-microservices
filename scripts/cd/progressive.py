"""Opt-in HTTP canary and SLO resources; no worker traffic is split by this module."""
import copy
import json
import math
from urllib.parse import urlparse

SERVICE = "product-detail-bff"


def settings(profile):
    return profile.get("operations", {})


def enabled(profile):
    return settings(profile).get("enabled") is True


def canary_enabled(profile):
    return enabled(profile) and settings(profile).get("canary", {}).get("enabled") is True


def validate(profile):
    ops = settings(profile)
    if not enabled(profile):
        if ops.get("canary", {}).get("enabled") or ops.get("slo", {}).get("enforce"):
            raise ValueError("Canary requires operations monitoring")
        return
    address = urlparse(ops.get("prometheus_url") or "")
    if address.scheme != "https" or not address.hostname or address.username or address.query or address.fragment:
        raise ValueError("Operations requires a trusted HTTPS Prometheus endpoint")
    slo = ops.get("slo", {})
    if slo.get("approved") is not True:
        raise ValueError("Approve measured SLO targets before enabling operations")
    for key in ("availability", "latency_success"):
        value = slo.get(key, 0)
        if not isinstance(value, (float, int)) or not math.isfinite(value) or not 0.9 <= value < 1:
            raise ValueError("Invalid SLO objective")
    if slo.get("latency_seconds") not in (0.5, 1, 2) or slo.get("window_days") != 30:
        raise ValueError("Use a verified histogram boundary and the 30-day SLO window")
    if type(slo.get("minimum_requests")) is not int or slo["minimum_requests"] < 100:
        raise ValueError("SLO evaluation requires a meaningful minimum request count")
    if not 30 <= slo.get("max_scrape_age_seconds", 0) <= 120:
        raise ValueError("Set a bounded telemetry freshness requirement")
    if not 0.95 <= slo.get("minimum_window_coverage", 0) <= 1:
        raise ValueError("Set the minimum full-window telemetry coverage")
    if not canary_enabled(profile):
        return
    canary = ops["canary"]
    if canary.get("service") != SERVICE:
        raise ValueError("Only the stateless HTTP Product Detail BFF is approved for canary")
    if type(canary.get("replicas")) is not int or not 5 <= canary["replicas"] <= 20:
        raise ValueError("Replica-based canary requires 5 to 20 replicas")
    if canary.get("steps") != [20, 50, 100]:
        raise ValueError("Use the reviewed 20/50/100 replica canary sequence")
    if (not 30 <= canary.get("pause_seconds", 0) <= 120 or
            canary.get("analysis_interval_seconds") != 60 or canary.get("analysis_count") != 3 or
            not 30 <= canary.get("minimum_requests", 0) <= 1000):
        raise ValueError("Invalid bounded canary observation policy")
    if "operations-metrics" not in profile.get("secret_versions", {}):
        raise ValueError("Pin the Prometheus read credential version")


def workload_kinds(profile):
    return {SERVICE: "Rollout"} if canary_enabled(profile) else {}


def selectors(namespace, revision=None, suffix=""):
    values = ["namespace=" + json.dumps(namespace), "service=" + json.dumps(SERVICE)]
    if revision:
        values.append("rollout_revision=" + json.dumps(revision))
    if suffix:
        values.append(suffix)
    return "{" + ",".join(values) + "}"


def queries(profile, window="5m", revision=None):
    namespace = profile["namespace"]
    business = 'uri!~"/actuator.*|/health.*"'
    base = selectors(namespace, revision, business)
    errors = selectors(namespace, revision, business + ',status=~"5.."')
    boundary = format(settings(profile)["slo"]["latency_seconds"], "g")
    # Prometheus/Micrometer may expose the same numeric boundary as 1 or 1.0.
    pattern = boundary.replace(".", "\\.") + ("0*" if "." in boundary else "(\\.0+)?")
    fast = selectors(namespace, revision, business + ",le=~" + json.dumps(pattern))
    count = "sum(increase(http_server_requests_seconds_count" + base + "[" + window + "]))"
    failed = "sum(increase(http_server_requests_seconds_count" + errors + "[" + window + "]))"
    good_latency = "sum(increase(http_server_requests_seconds_bucket" + fast + "[" + window + "]))"
    total_buckets = "sum(increase(http_server_requests_seconds_bucket" + selectors(namespace, revision, business + ',le="+Inf"') + "[" + window + "]))"
    return {"requests": count, "availability": "1 - ((" + failed + " or vector(0)) / " + count + ")",
            "latency_success": good_latency + " / " + count,
            "histogram_requests": total_buckets,
            "histogram_coverage": total_buckets + " / " + count,
            "target_up": "min(up" + selectors(namespace, revision) + ")",
            "scrape_age_seconds": "time() - min(timestamp(up" + selectors(namespace, revision) + "))"}


def analysis_template(profile, secret_name, resource):
    ops, canary = settings(profile), settings(profile)["canary"]
    metrics = []
    for name, query in queries(profile, revision="{{args.rollout-hash}}").items():
        if name in ("requests", "histogram_requests"):
            condition = "result[0] >= " + str(canary["minimum_requests"])
        elif name == "target_up":
            condition = "result[0] == 1"
        elif name == "scrape_age_seconds":
            condition = "result[0] <= " + str(ops["slo"]["max_scrape_age_seconds"])
        elif name == "histogram_coverage":
            condition = "result[0] >= 0.99 && result[0] <= 1.01"
        else:
            condition = "result[0] >= " + str(ops["slo"][name])
        metrics.append({"name": name.replace("_", "-"), "interval": "60s", "count": 3, "failureLimit": 0,
                        "consecutiveErrorLimit": 1,
                        "successCondition": "len(result) == 1 && !isNaN(result[0]) && !isInf(result[0]) && " + condition,
                        "failureCondition": "len(result) != 1 || isNaN(result[0]) || isInf(result[0]) || !(" + condition + ")",
                        "provider": {"prometheus": {"address": ops["prometheus_url"], "timeout": 15,
                                     "headers": [{"key": "Authorization", "value": "{{args.authorization}}"}], "query": query}}})
    return resource("AnalysisTemplate", "bff-canary", profile["namespace"], {
        "args": [{"name": "rollout-hash"}, {"name": "authorization", "valueFrom": {"secretKeyRef": {"name": secret_name, "key": "authorization"}}}],
        "metrics": metrics}, api="argoproj.io/v1alpha1", wave=-20)


def convert(deployment, profile):
    obj = copy.deepcopy(deployment)
    canary = settings(profile)["canary"]
    obj.update(apiVersion="argoproj.io/v1alpha1", kind="Rollout")
    obj["spec"]["replicas"] = canary["replicas"]
    steps = []
    for weight in canary["steps"]:
        steps += [{"setWeight": weight}, {"pause": {"duration": str(canary["pause_seconds"]) + "s"}},
                  {"analysis": {"templates": [{"templateName": "bff-canary"}],
                   "args": [{"name": "rollout-hash", "valueFrom": {"podTemplateHashValue": "Latest"}}]}}]
    obj["spec"]["strategy"] = {"canary": {"maxUnavailable": 0, "maxSurge": 1, "steps": steps}}
    obj["spec"]["progressDeadlineSeconds"] = 1800
    obj["spec"]["progressDeadlineAbort"] = True
    return obj


def monitoring(profile, resource):
    ns = profile["namespace"]
    monitor = resource("PodMonitor", "bff-slo", ns, {
        "selector": {"matchLabels": {"app.kubernetes.io/name": SERVICE}},
        "podMetricsEndpoints": [{"port": "http", "path": "/actuator/prometheus", "interval": "15s", "scrapeTimeout": "5s",
            "relabelings": [{"targetLabel": "service", "replacement": SERVICE},
                {"sourceLabels": ["__meta_kubernetes_namespace"], "targetLabel": "namespace"},
                {"sourceLabels": ["__meta_kubernetes_pod_label_rollouts_pod_template_hash"], "targetLabel": "rollout_revision"},
                {"sourceLabels": ["__meta_kubernetes_pod_label_delivery_ecommerce_revision"], "targetLabel": "deployment_revision"}]}]}, api="monitoring.coreos.com/v1", wave=-20)
    monitor["metadata"]["labels"] = {"delivery.ecommerce/monitoring": "operations"}
    slo = settings(profile)["slo"]
    rules = []
    for signal in ("availability", "latency_success"):
        budget = 1 - slo[signal]
        for short, long, burn, severity in (("5m", "1h", 14.4, "critical"), ("30m", "6h", 6, "warning")):
            q1, q2 = queries(profile, short), queries(profile, long)
            expr = f"((1 - ({q1[signal]})) / {budget} > {burn}) and ((1 - ({q2[signal]})) / {budget} > {burn}) and ({q1['requests']} >= 30)"
            rules.append({"alert": "BffSloFastBurn" if severity == "critical" else "BffSloSlowBurn", "expr": expr, "for": "2m",
                "labels": {"severity": severity, "owner": "platform", "slo": signal, "namespace": ns, "service": SERVICE},
                "annotations": {"summary": "Product Detail BFF consumes its " + signal + " error budget",
                                "runbook_url": "https://github.com/" + profile["operations"]["source_repository"] + "/blob/main/docs/cicd/phase-3.md#slo"}})
    q = queries(profile)
    rules.append({"alert": "BffSloTelemetryMissing", "expr": "absent(up" + selectors(ns) + ") or (" + q["target_up"] + " < 1) or (" + q["scrape_age_seconds"] + " > " + str(slo["max_scrape_age_seconds"]) + ")", "for": "2m",
                  "labels": {"severity": "critical", "owner": "platform", "namespace": ns, "service": SERVICE}, "annotations": {"summary": "BFF SLO telemetry is absent, down or stale"}})
    coverage = {"record": "bff:slo_telemetry_available", "expr": "(sum(up" + selectors(ns) + ") > bool 0) or vector(0)",
                "labels": {"namespace": ns, "service": SERVICE}}
    return [monitor, resource("PrometheusRule", "bff-slo", ns, {"groups": [{"name": "bff-slo", "interval": "30s", "rules": [coverage, *rules]}]}, api="monitoring.coreos.com/v1", wave=-20)]

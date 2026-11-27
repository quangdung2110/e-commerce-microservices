"""Render the transport policy of a reviewed delivery profile."""
from collections import defaultdict
import ipaddress
import re
from urllib.parse import urlsplit

import yaml
import progressive

from common import ROOT, SHA, sha

WAYPOINT = "ecommerce-waypoint"
EGRESS = "ecommerce-egress"
CHECKS = "deployment-checks"
MIGRATIONS = "deployment-migrations"
GATEWAY_LABEL = "gateway.networking.k8s.io/gateway-name"
WORKLOAD_LABEL = "delivery.ecommerce/waypoint"
APP_LABEL = "app.kubernetes.io/name"
MIGRATION_LABEL = "delivery.ecommerce/migration-service"
SECURITY_API = "security.istio.io/v1"
NETWORK_API = "networking.k8s.io/v1"
MESH_NETWORK_API = "networking.istio.io/v1"
MESH_KINDS = {"AuthorizationPolicy", "DestinationRule", "VirtualService", "ServiceEntry"}
DNS_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
DNS_NAME = re.compile(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
DIRECT = {"api-gateway", "frontend"}
PRIVATE_RANGES = tuple(ipaddress.ip_network(cidr) for cidr in (
    "10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"))
PROBE_SOURCES = [{"ipBlock": {"cidr": cidr}} for cidr in (
    "169.254.7.127/32", "fd16:9254:7127:1337:ffff:ffff:ffff:ffff/128")]
PUBLIC_KINDS = {"stripe", "ses", "sqs", "cloud-identity", "object-storage", "media", "storefront", "temporal"}
DEPENDENCY_CLIENTS = {
    "redis": {"auth-service", "cart-service", "checkout-service", "gateway-auth", "notification-service",
              "order-service", "promotion-service", "user-service"},
    "kafka": {"auth-service", "cart-service", "notification-service", "order-service", "payment-service",
              "product-service", "promotion-service", "review-service", "search-service", "user-service"},
    "temporal": {"order-service"},
    "elasticsearch": {"search-service"},
    "stripe": {"payment-service"},
    "ses": {"notification-service"},
    "sqs": {"notification-service"},
    "cloud-identity": {"notification-service"},
    "object-storage": {"review-service"},
    "media": {"api-gateway"},
    "storefront": {CHECKS},
}


def dns_name(value):
    return isinstance(value, str) and DNS_NAME.fullmatch(value) and all(DNS_LABEL.fullmatch(label) for label in value.split("."))


def dependency_clients(components, environment):
    required = {kind: set(clients) for kind, clients in DEPENDENCY_CLIENTS.items()}
    required["postgres"] = {name for name, spec in components.items() if spec.get("migration")}
    required["postgres"].update("migration-" + name for name in required["postgres"].copy())
    if environment == "staging":
        required["stripe"].add(CHECKS)
    else:
        required.pop("storefront")
    return required


def validate_profile(profile, environment, components):
    settings = profile.get("transport")
    if not isinstance(settings, dict):
        raise ValueError(f"Configure the transport profile for {environment}")
    if not SHA.fullmatch(settings.get("platform_revision") or ""):
        raise ValueError("Pin the mesh platform revision")
    for key in ("trust_domain", "cluster_dns_suffix"):
        if not dns_name(settings.get(key)):
            raise ValueError(f"Configure {key}")
    for key in ("ingress_service_account", "prometheus_service_account", "collector_service_account"):
        if not DNS_LABEL.fullmatch(settings.get(key) or "") or settings[key] == "default":
            raise ValueError(f"Configure a dedicated {key}")
    for key in ("prometheus_selector", "collector_selector"):
        if not isinstance(settings.get(key), dict) or not settings[key] or any(
                not isinstance(k, str) or not k or not isinstance(v, str) or not v for k, v in settings[key].items()):
            raise ValueError(f"Configure {key}")
    if profile["namespace"] in {profile["observability_namespace"], profile["ingress_namespace"]}:
        raise ValueError("The ambient ingress controller and collectors must have their own namespaces")
    collector = settings.get("collector_service")
    host = f"{collector}.{profile['observability_namespace']}.svc.{settings['cluster_dns_suffix']}"
    if not DNS_LABEL.fullmatch(collector or "") or profile["otlp_endpoint"].rstrip("/") != f"http://{host}:4318":
        raise ValueError("Configure OTLP HTTP on the enrolled collector Service (port 4318)")
    for cidr in settings.get("additional_dns_cidrs", []):
        network = ipaddress.ip_network(cidr)
        if network.prefixlen != network.max_prefixlen or network.is_global:
            raise ValueError("Additional DNS CIDRs must identify a single resolver address")
    temporal = settings.get("temporal", {})
    if temporal.get("mode") not in {"tls", "mtls"} or not DNS_NAME.fullmatch(temporal.get("namespace") or ""):
        raise ValueError("Configure the Temporal namespace and tls or mtls")
    if type(temporal.get("use_data_ca", False)) is not bool or temporal.get("use_data_ca") and not settings.get("data_ca_pem"):
        raise ValueError("Temporal use_data_ca requires a data CA bundle")
    if profile.get("external_data_cidrs"):
        raise ValueError("Declare data CIDRs per endpoint instead of external_data_cidrs")

    endpoints = settings.get("endpoints")
    if not isinstance(endpoints, list) or not endpoints:
        raise ValueError("Declare the endpoints authorized by the transport policy")
    allowed = dependency_clients(components, environment)
    observed = defaultdict(set)
    ids, destinations, public_hosts = set(), set(), set()
    for endpoint in endpoints:
        name, kind, host, port = (endpoint.get(key) for key in ("name", "kind", "host", "port"))
        if not DNS_LABEL.fullmatch(name or "") or len(name) > 46 or name in ids or kind not in allowed:
            raise ValueError("Use unique endpoint names and known endpoint kinds")
        ids.add(name)
        if not dns_name(host) or "." not in host or type(port) is not int or not 1 <= port <= 65535 or (host, port) in destinations:
            raise ValueError(f"Invalid or duplicate host/port for {name}")
        destinations.add((host, port))
        clients = endpoint.get("clients")
        if (not isinstance(clients, list) or not clients or len(clients) != len(set(clients))
                or not set(clients) <= allowed[kind]):
            raise ValueError(f"Invalid client set for {name}")
        observed[kind].update(clients)
        cidrs = endpoint.get("cidrs", [])
        if not isinstance(cidrs, list):
            raise ValueError(f"Endpoint {name} requires a CIDR list")
        for cidr in cidrs:
            network = ipaddress.ip_network(cidr)
            if not any(network.version == private.version and network.subnet_of(private) for private in PRIVATE_RANGES):
                raise ValueError(f"Endpoint {name} requires a private address range or the public waypoint")
        if not cidrs:
            if kind not in PUBLIC_KINDS or host in public_hosts:
                raise ValueError(f"Endpoint {name} requires private CIDRs or a unique public host")
            if port != 443 and kind != "temporal":
                raise ValueError(f"Public HTTP provider {name} requires HTTPS port 443")
            public_hosts.add(host)
        if kind == "stripe" and host != "api.stripe.com":
            raise ValueError("Stripe endpoint must match the SDK and staging E2E endpoint")
    for kind, clients in allowed.items():
        if kind != "cloud-identity" and clients - observed[kind]:
            raise ValueError(f"Missing {kind} endpoint for {', '.join(sorted(clients - observed[kind]))}")
    if sum(endpoint["kind"] == "temporal" for endpoint in endpoints) != 1:
        raise ValueError("Configure one Temporal target per environment")
    required_hosts = {"media": urlsplit(profile["media_origin"]).hostname, "storefront": profile["domain"]}
    for kind, host in required_hosts.items():
        if kind in allowed and not any(endpoint["kind"] == kind and endpoint["host"] == host and endpoint["port"] == 443
                                       for endpoint in endpoints):
            raise ValueError(f"{kind} endpoint must match its configured HTTPS origin")
    return settings


def kube_resource(profile, kind, name, spec, api=SECURITY_API, wave=-20):
    return {"apiVersion": api, "kind": kind, "metadata": {"name": name, "namespace": profile["namespace"],
            "annotations": {"argocd.argoproj.io/sync-wave": str(wave)}}, "spec": spec}


def platform_resources(profile):
    if "transport" not in profile:
        return []
    revision = profile["transport"]["platform_revision"]
    strict = kube_resource(profile, "PeerAuthentication", "application-strict", {"mtls": {"mode": "STRICT"}})
    options = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {
        "name": "waypoint-pod-security", "namespace": profile["namespace"],
        "annotations": {"argocd.argoproj.io/sync-wave": "-30"}}, "data": {
        "deployment": yaml.safe_dump({"spec": {"template": {"spec": {
            "securityContext": {"seccompProfile": {"type": "RuntimeDefault"}}}}}})}}
    resources = [options, strict]
    for name in (WAYPOINT, EGRESS):
        gateway = kube_resource(profile, "Gateway", name, {"gatewayClassName": "istio-waypoint",
            "infrastructure": {"parametersRef": {"group": "", "kind": "ConfigMap", "name": options["metadata"]["name"]}},
            "listeners": [{"name": "mesh", "port": 15008, "protocol": "HBONE"}]}, "gateway.networking.k8s.io/v1")
        gateway["metadata"]["labels"] = {"istio.io/waypoint-for":
            "all" if name == WAYPOINT and progressive.enabled(profile) else "service"}
        resources.append(gateway)
        resources.append(kube_resource(profile, "PodDisruptionBudget", name, {"minAvailable": 1,
            "selector": {"matchLabels": {GATEWAY_LABEL: name}}}, "policy/v1"))
        resources.append(kube_resource(profile, "HorizontalPodAutoscaler", name, {
            "scaleTargetRef": {"apiVersion": "apps/v1", "kind": "Deployment", "name": name}, "minReplicas": 2,
            "maxReplicas": 6, "metrics": [{"type": "Resource", "resource": {"name": "cpu",
                "target": {"type": "Utilization", "averageUtilization": 70}}}]}, "autoscaling/v2"))
    for obj in resources:
        obj["metadata"]["annotations"]["delivery.ecommerce/platform-revision"] = revision
    return resources


def selector(labels):
    return {"matchLabels": labels}


def peer(labels, namespace=None):
    result = {"podSelector": selector(labels)}
    if namespace is not None:
        result["namespaceSelector"] = selector({"kubernetes.io/metadata.name": namespace})
    return result


def ports(*numbers, protocol="TCP"):
    return [{"protocol": protocol, "port": number} for number in numbers]


def workload_labels(name):
    if name.startswith("migration-"):
        return {APP_LABEL: MIGRATIONS, MIGRATION_LABEL: name.removeprefix("migration-")}
    return {APP_LABEL: name}


def mesh_network_policies(profile, components):
    settings = profile["transport"]
    obs = profile["observability_namespace"]
    waypoints = [peer({GATEWAY_LABEL: name}) for name in (WAYPOINT, EGRESS)]
    collector = peer(settings["collector_selector"], obs)
    prometheus = peer(settings["prometheus_selector"], obs)
    ingress = peer(profile["ingress_selector"], profile["ingress_namespace"])
    dns = {"to": [peer({"k8s-app": "kube-dns"}, "kube-system"),
                  *[{"ipBlock": {"cidr": cidr}} for cidr in settings.get("additional_dns_cidrs", [])]],
           "ports": ports(53) + ports(53, protocol="UDP")}
    network = [kube_resource(profile, "NetworkPolicy", "default-deny", {
        "podSelector": {}, "policyTypes": ["Ingress", "Egress"]}, NETWORK_API)]

    def policy(name, labels, direction, rules):
        network.append(kube_resource(profile, "NetworkPolicy", name, {
            "podSelector": selector(labels), "policyTypes": [direction], direction.lower(): rules}, NETWORK_API))

    policy("waypoint-to-business", {WORKLOAD_LABEL: "true"}, "Ingress", [
        {"from": [waypoints[0]], "ports": ports(15008)}])
    for name in sorted(DIRECT):
        sources = [ingress, peer(workload_labels(CHECKS))]
        if name == "api-gateway":
            sources.extend((peer(workload_labels("frontend")), prometheus))
        policy("ingress-" + name, workload_labels(name), "Ingress", [{"from": sources, "ports": ports(15008)}])
    # Kubelet probes still address workload ports, using Istio's fixed link-local sources.
    for name, component in components.items():
        policy("probe-" + name, workload_labels(name), "Ingress", [
            {"from": PROBE_SOURCES, "ports": ports(component["ports"][component["probe_port"]])}])
    app_and_checks = [peer(workload_labels(name)) for name in sorted({*components, CHECKS})]
    for gateway in (WAYPOINT, EGRESS):
        policy("ingress-" + gateway, {GATEWAY_LABEL: gateway}, "Ingress",
               [{"from": app_and_checks + ([prometheus] if gateway == WAYPOINT else []), "ports": ports(15008)},
                {"from": PROBE_SOURCES, "ports": ports(15021)}])
    waypoint_egress = [dns, {"to": [peer({"app": "istiod"}, "istio-system")], "ports": ports(15012)},
                       {"to": [collector], "ports": ports(15008)}]
    policy("egress-" + WAYPOINT, {GATEWAY_LABEL: WAYPOINT}, "Egress",
           waypoint_egress + [{"to": [peer({WORKLOAD_LABEL: "true"})], "ports": ports(15008)}])
    public = [endpoint for endpoint in settings["endpoints"] if not endpoint.get("cidrs")]
    public_ports = sorted({endpoint["port"] for endpoint in public})
    internet = {"to": [{"ipBlock": {"cidr": "0.0.0.0/0", "except": [
                            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
                            "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4"]}},
                       {"ipBlock": {"cidr": "2000::/3"}}], "ports": ports(*public_ports)}
    policy("egress-" + EGRESS, {GATEWAY_LABEL: EGRESS}, "Egress", waypoint_egress + ([internet] if public_ports else []))
    private_rules = defaultdict(list)
    public_clients = set()
    for endpoint in settings["endpoints"]:
        if not endpoint.get("cidrs"):
            public_clients.update(endpoint["clients"])
            continue
        rule = {"to": [{"ipBlock": {"cidr": cidr}} for cidr in endpoint["cidrs"]],
                "ports": ports(endpoint["port"])}
        for name in endpoint["clients"]:
            if rule not in private_rules[name]:
                private_rules[name].append(rule)
    for name in sorted({*components, CHECKS, *private_rules}):
        rules = [dns]
        if name in components:
            rules.append({"to": [collector], "ports": ports(15008)})
        if name in components or name == CHECKS:
            rules.append({"to": [waypoints[0]], "ports": ports(15008)})
        if name in {"frontend", CHECKS}:
            direct = [peer(workload_labels("api-gateway"))]
            if name == CHECKS:
                direct.append(peer(workload_labels("frontend")))
            rules.append({"to": direct, "ports": ports(15008)})
        if name in public_clients:
            rules.append({"to": [waypoints[1]], "ports": ports(15008)})
        policy("egress-" + name, workload_labels(name), "Egress", rules + private_rules[name])
    return network


def principal(profile, name, namespace=None):
    return f"{profile['transport']['trust_domain']}/ns/{namespace or profile['namespace']}/sa/{name}"


def allow(callers, paths=None, listener=None, methods=None):
    operation = {}
    if paths:
        operation["paths"] = paths
    if listener:
        operation["ports"] = [str(listener)]
    if methods:
        operation["methods"] = methods
    rule = {"from": [{"source": {"principals": callers}}]}
    if operation:
        rule["to"] = [{"operation": operation}]
    return rule


def service_policy(profile, service, rules):
    return kube_resource(profile, "AuthorizationPolicy", "allow-" + service, {
        "action": "ALLOW", "targetRefs": [{"group": "", "kind": "Service", "name": service}], "rules": rules})


def mesh_guards(profile, components):
    settings = profile["transport"]
    checker = principal(profile, CHECKS)
    metrics = principal(profile, settings["prometheus_service_account"], profile["observability_namespace"])
    ingress = principal(profile, settings["ingress_service_account"], profile["ingress_namespace"])
    resources = [kube_resource(profile, "AuthorizationPolicy", "l4-baseline", {
        "action": "ALLOW", "selector": selector({WORKLOAD_LABEL: "true"}),
        "rules": [allow([principal(profile, WAYPOINT)])]})]
    for gateway in (WAYPOINT, EGRESS):
        resources.append(kube_resource(profile, "AuthorizationPolicy", "deny-" + gateway, {
            "action": "ALLOW", "targetRefs": [{"group": "gateway.networking.k8s.io", "kind": "Gateway", "name": gateway}]}))
    if progressive.enabled(profile):
        # PodMonitor needs per-replica labels and scrapes BFF by IP. Only that workload opts into
        # a workload waypoint, and its Gateway-level grant is limited to the Prometheus identity.
        resources.append(kube_resource(profile, "AuthorizationPolicy", "allow-bff-pod-metrics", {
            "action": "ALLOW", "targetRefs": [{"group": "gateway.networking.k8s.io", "kind": "Gateway", "name": WAYPOINT}],
            "rules": [allow([metrics], ["/actuator/prometheus"], components[progressive.SERVICE]["ports"]["http"], ["GET"])]}))
    for service in sorted(DIRECT):
        callers = [ingress]
        listener = 8080 if service == "api-gateway" else 3000
        if service == "api-gateway":
            callers.append(principal(profile, "frontend"))
            rules = [allow(callers, listener=listener), allow([checker, metrics], listener=8090)]
        else:
            rules = [allow(callers + [checker], listener=listener)]
        resources.append(kube_resource(profile, "AuthorizationPolicy", "allow-" + service,
                         {"selector": selector(workload_labels(service)), "action": "ALLOW", "rules": rules}))
    return resources


def mesh_egress(profile):
    resources = []
    for entry in profile["transport"]["endpoints"]:
        if entry.get("cidrs"):
            continue
        service = kube_resource(profile, "ServiceEntry", entry["name"], {"hosts": [entry["host"]],
            "location": "MESH_EXTERNAL", "resolution": "DNS", "exportTo": ["."],
            "ports": [{"number": entry["port"], "name": "tls", "protocol": "TLS"}]}, MESH_NETWORK_API)
        service["metadata"]["labels"] = {"istio.io/use-waypoint": EGRESS}
        resources.append(service)
        resources.append(kube_resource(profile, "AuthorizationPolicy", "allow-egress-" + entry["name"], {
            "action": "ALLOW", "targetRefs": [{"group": "networking.istio.io", "kind": "ServiceEntry", "name": entry["name"]}],
            "rules": [allow([principal(profile, name) for name in entry["clients"]], listener=entry["port"])]}))
    return resources


def mesh_application_resources(profile, components, probes):
    ns, settings = profile["namespace"], profile["transport"]
    checker = principal(profile, CHECKS)
    metrics = principal(profile, settings["prometheus_service_account"], profile["observability_namespace"])
    business = set(components) - DIRECT
    resources = mesh_guards(profile, components)
    internal = ROOT / "deploy/mesh/generated"
    interactions = yaml.safe_load((ROOT / "contracts/catalog/interactions.yaml").read_text(encoding="utf-8"))["interactions"]
    providers = {interaction["id"]: interaction["provider"] for interaction in interactions}
    checks_by_service = defaultdict(list)
    for probe in probes["probes"]:
        service = providers[probe["interaction"]]
        url = urlsplit(probes["targets"][service])
        path = urlsplit(probe["request"]["path"]).path
        path = "/".join("{*}" if "${" in segment else segment for segment in path.split("/"))
        rule = allow([checker], [path], url.port, [probe["request"]["method"]])
        if rule not in checks_by_service[service]:
            checks_by_service[service].append(rule)
    generated = list(yaml.safe_load_all((internal / "41-authorizationpolicies-ecommerce.yaml").read_text(encoding="utf-8")))
    for obj in generated:
        spec = obj["spec"]
        target = spec.get("targetRefs", [{}])[0]
        service = target.get("name") if target.get("kind") == "Service" else None
        if service not in business:
            continue
        rules = []
        for rule in spec["rules"]:
            mapped = []
            for source in rule["from"][0]["source"]["principals"]:
                location, name = source.split("/ns/", 1)[1].split("/sa/", 1)
                if location == "edge" and name == "traefik":
                    mapped.append(principal(profile, "api-gateway"))
                elif location == "observability" and name == "prometheus":
                    mapped.append(metrics)
                elif name in components:
                    mapped.append(principal(profile, name))
            if mapped:
                rule["from"][0]["source"]["principals"] = mapped
                rules.append(rule)
        component = components[service]
        rules.append(allow([checker], [component["ready"]], component["ports"][component["probe_port"]], ["GET"]))
        resources.append(service_policy(profile, service, rules + checks_by_service[service]))
    resources.append(service_policy(profile, "gateway-auth", [
        allow([principal(profile, "api-gateway")], ["/verify"], 8080, ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]),
        allow([metrics], ["/metrics"], 8080, ["GET"]),
        allow([checker], [components["gateway-auth"]["ready"]], 8080, ["GET"]),
    ]))
    for filename in ("50-virtualservices.yaml", "51-destinationrules.yaml"):
        for obj in yaml.safe_load_all((internal / filename).read_text(encoding="utf-8")):
            name = obj["metadata"]["name"]
            if name not in business:
                continue
            host = f"{name}.{ns}.svc.{settings['cluster_dns_suffix']}"
            spec = obj["spec"]
            if obj["kind"] == "VirtualService":
                spec["hosts"] = [host]
                for route in spec["http"]:
                    for dest in route["route"]:
                        dest["destination"]["host"] = host
            else:
                spec["host"] = host
            resources.append(kube_resource(profile, obj["kind"], name, spec, MESH_NETWORK_API))
    resources.extend(mesh_egress(profile))
    return resources


def transport_digest(resources):
    return sha([obj for obj in resources if obj["kind"] in MESH_KINDS | {"NetworkPolicy"}])

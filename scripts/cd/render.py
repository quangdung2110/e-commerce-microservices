"""Render portable Kustomize bundles from explicit production configuration and verified digests."""
import copy
import ipaddress
import json
from pathlib import Path
import re
import ssl
from urllib.parse import urlparse

import yaml
import progressive
import transport

from common import ROOT, REPOSITORY, catalog, sha, write_json

ENVIRONMENTS = ("staging", "production")
TOOLS = ("database-migrations", "deployment-checks")
E2E_PROPERTIES = ("E2E_EMAIL", "E2E_PASSWORD", "E2E_ADMIN_EMAIL", "E2E_ADMIN_PASSWORD", "E2E_ADDRESS_ID", "E2E_SKU_ID", "E2E_STRIPE_SECRET_KEY", "E2E_WEBHOOK_SECRET")


def secret_contract(components, environment, profile):
    result = {name: {key: key for key in component["runtime_properties"]} for name, component in components.items() if component["runtime_properties"]}
    for name, component in components.items():
        if component.get("migration"):
            keys = ("FLYWAY_URL", "FLYWAY_USER", "FLYWAY_PASSWORD") if component["migration"] == "flyway" else ("MIGRATE_DATABASE_URL",)
            result["migration-" + name] = {key: key for key in keys}
    result["registry"] = {".dockerconfigjson": "dockerconfigjson"}
    result["temporal-client"] = temporal_secret_properties(profile)
    if environment == "staging":
        result["checks-e2e"] = {key: key for key in E2E_PROPERTIES}
    return result


def temporal_secret_properties(profile):
    if profile["transport"]["temporal"]["mode"] == "mtls":
        return {"tls.crt": "client_certificate", "tls.key": "client_key"}
    return {"api-key": "api_key"}


def required_versions(components, environment, profile):
    return set(secret_contract(components, environment, profile))


def validate_config(config, activation=False):
    if config.get("schema_version") != 1:
        raise ValueError("Unsupported environment config schema")
    for key in ("source_repository", "gitops_repository"):
        if not REPOSITORY.fullmatch(config.get(key) or ""):
            raise ValueError(f"Configure {key}")
    if config["source_repository"].lower() == config["gitops_repository"].lower():
        raise ValueError("Use a separate GitOps repository")
    if config.get("gitops_branch") != "main" or not re.fullmatch(r"[A-Za-z0-9_-]+\[bot\]", config.get("gitops_bot_login") or ""):
        raise ValueError("GitOps main branch and GitHub App bot login are required")
    if not config.get("production_owners") or not all(re.fullmatch(r"@[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)?", o) for o in config["production_owners"]):
        raise ValueError("Configure production CODEOWNERS (GitHub users or organization teams)")
    url = urlparse(config.get("argocd_url") or "")
    if url.scheme != "https" or not url.hostname or url.username or url.query or url.fragment:
        raise ValueError("Configure an HTTPS Argo CD URL without credentials")
    if not 1 <= config.get("proof_max_age_hours", 0) <= 72:
        raise ValueError("Proof expiry must be between 1 and 72 hours")
    components = catalog()
    seen = {key: set() for key in ("namespace", "domain", "cluster_server", "secret_prefix")}
    for environment in ENVIRONMENTS:
        p = config["environments"][environment]
        for key in ("namespace", "domain", "cluster_server", "secret_prefix"):
            value = p.get(key)
            if not isinstance(value, str) or not value.strip() or value in seen[key]:
                raise ValueError(f"Configure distinct {key} for {environment}")
            seen[key].add(value)
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,61}[a-z0-9]", p["namespace"]) or p["namespace"].startswith("kube-"):
            raise ValueError("Invalid application namespace")
        if not re.fullmatch(r"[a-z0-9.-]+\.[a-z]{2,}", p["domain"]) or p["domain"] in ("example.com", "example.org"):
            raise ValueError("Configure the actual storefront DNS name")
        for key in ("cluster_server", "media_origin"):
            parsed = urlparse(p.get(key) or "")
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
                raise ValueError(f"Configure HTTPS {key} for {environment}")
        if not p.get("secret_store") or not p.get("ingress_class") or not p.get("ingress_namespace") or not p.get("ingress_selector"):
            raise ValueError("Configure ClusterSecretStore and ingress controller identity")
        if not p.get("observability_namespace") or not (p.get("otlp_endpoint") or "").startswith(("https://", "http://")):
            raise ValueError("Configure observability namespace and collector")
        if not p.get("trusted_proxy_cidrs") or any(ipaddress.ip_network(cidr).prefixlen == 0 for cidr in p["trusted_proxy_cidrs"]):
            raise ValueError("Configure trusted proxy CIDRs")
        transport.validate_profile(p, environment, components)
        ca_bundle = p["transport"].get("data_ca_pem")
        if ca_bundle:
            certificates = re.findall(r"-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----", ca_bundle)
            if not certificates or re.sub(r"\s+", "", ca_bundle) != re.sub(r"\s+", "", "".join(certificates)):
                raise ValueError("Data CA bundle must contain only PEM certificates")
            for certificate in certificates:
                ssl.PEM_cert_to_DER_cert(certificate)
        if type(p.get("replicas")) is not int or p["replicas"] < 2 or not 0 <= p.get("sampling", -1) <= 1:
            raise ValueError("At least two replicas and an explicit sampling ratio are required")
        versions = p.get("secret_versions", {})
        progressive.validate(p)
        ssl.PEM_cert_to_DER_cert(p.get("database_ca_pem") or "")
        version_keys = required_versions(components, environment, p) | ({"operations-metrics"} if progressive.canary_enabled(p) else set())
        if any(not isinstance(versions.get(k), str) or not versions[k].strip() or versions[k].lower() in ("latest", "current", "awscurrent", "awsprevious")
               for k in version_keys):
            raise ValueError(f"Pin every runtime/migration/registry secret version for {environment}")
        if not p.get("tls_secret") or not p.get("image_pull_secret"):
            raise ValueError("TLS certificate and registry pull secret names are required")
    if progressive.canary_enabled(config["environments"]["production"]):
        stage = config["environments"]["staging"]
        if not progressive.canary_enabled(stage) or stage["operations"]["canary"] != config["environments"]["production"]["operations"]["canary"]:
            raise ValueError("Rehearse the same canary strategy in staging before enabling it in production")
    prod_ops = progressive.settings(config["environments"]["production"])
    if prod_ops.get("slo", {}).get("enforce") and not progressive.settings(config["environments"]["staging"]).get("slo", {}).get("enforce"):
        raise ValueError("Enforce the staging SLO gate before enabling it for production")
    if activation and not all(config.get("readiness", {}).get(k) is True for k in (
            "payment_production_cipher_implemented", "gateway_shared_rate_limits_verified", "backup_restore_drill_passed")):
        raise ValueError("Activation blocked: implement Payment crypto, verify shared rate limits, and drill restore first")


def resource(kind, name, namespace, spec=None, api="v1", wave=0):
    obj = {"apiVersion": api, "kind": kind, "metadata": {"name": name, "namespace": namespace,
           "annotations": {"argocd.argoproj.io/sync-wave": str(wave)}}}
    if spec is not None:
        obj["spec"] = spec
    return obj


def external_secret(name, properties, profile, key=None, target_name=None, secret_type="Opaque"):
    key = key or name
    suffix = sha({"remote_key": profile["secret_prefix"] + "/" + key, "version": profile["secret_versions"][key],
                  "properties": properties, "store": profile["secret_store"], "target": target_name or name, "type": secret_type})[:10]
    return resource("ExternalSecret", name + "-" + suffix, profile["namespace"], {
        "refreshPolicy": "OnChange", "secretStoreRef": {"name": profile["secret_store"], "kind": "ClusterSecretStore"},
        "target": {"name": (target_name or name) + "-" + suffix, "creationPolicy": "Owner", "template": {"type": secret_type}},
        "data": [{"secretKey": target, "remoteRef": {"key": profile["secret_prefix"] + "/" + key,
                  "property": prop, "version": profile["secret_versions"][key]}}
                 for target, prop in properties.items()]}, api="external-secrets.io/v1", wave=-30)


def pod_spec(image, profile, name):
    registry = external_secret("registry", {".dockerconfigjson": "dockerconfigjson"}, profile,
                               target_name=profile["image_pull_secret"], secret_type="kubernetes.io/dockerconfigjson")
    return {"serviceAccountName": name, "automountServiceAccountToken": False, "enableServiceLinks": False,
            "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001,
                                "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
            "imagePullSecrets": [{"name": registry["spec"]["target"]["name"]}],
            "terminationGracePeriodSeconds": 45,
            "containers": [{"name": name, "image": image, "imagePullPolicy": "IfNotPresent",
                            "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                                                "capabilities": {"drop": ["ALL"]}},
                            "volumeMounts": [{"name": "tmp", "mountPath": "/tmp"}, {"name": "database-ca", "mountPath": "/etc/platform-ca", "readOnly": True}]}],
            "volumes": [{"name": "tmp", "emptyDir": {"sizeLimit": "256Mi"}},
                        {"name": "database-ca", "configMap": {"name": "database-ca-" + sha(profile["database_ca_pem"])[:10]}}]}


def probe(path, port, startup=False):
    return {"httpGet": {"path": path, "port": port}, "timeoutSeconds": 3,
            "periodSeconds": 5 if startup else 10, "failureThreshold": 120 if startup else 3}


def gateway_configuration(profile):
    # Reuse the reviewed route contract, not local Deployment settings or credentials.
    dynamic = yaml.safe_load((ROOT / "go/services/api-gateway/config/dynamic.yml").read_text(encoding="utf-8"))
    routes = yaml.safe_load((ROOT / "deploy/k8s/services/routes.yml").read_text(encoding="utf-8"))
    forbidden = {"dev-auth", "dev-admin-auth", "auth-dashboard", "ip-whitelist"}
    for name in forbidden:
        dynamic["http"]["middlewares"].pop(name, None)
    dynamic = {"http": dynamic["http"]}
    dynamic["http"].get("serversTransports", {}).pop("insecure-transport", None)
    dynamic["http"].pop("services", None)
    dynamic["http"]["middlewares"]["product-media-path"]["replacePathRegex"]["replacement"] = "/$1"
    for router in routes["http"]["routers"].values():
        if any(m.split("@")[0] in forbidden for m in router.get("middlewares", [])):
            raise ValueError("Development middleware is referenced by a production route")
    for name, service in routes["http"]["services"].items():
        if service["loadBalancer"].get("serversTransport", "").split("@")[0] == "insecure-transport":
            raise ValueError("Production routes cannot disable TLS verification")
        for server in service["loadBalancer"]["servers"]:
            if name == "product-media":
                server["url"] = profile["media_origin"].rstrip("/")
            else:
                server["url"] = re.sub(r"\.(?:ecommerce|edge)\.svc\.cluster\.local", "", server["url"])
    static = {"global": {"checkNewVersion": False, "sendAnonymousUsage": False},
              "entryPoints": {"web": {"address": ":8080", "forwardedHeaders": {"trustedIPs": profile["trusted_proxy_cidrs"]}},
                              "metrics": {"address": ":8090"}},
              "ping": {"entryPoint": "metrics"},
              "providers": {"file": {"directory": "/etc/traefik/dynamic", "watch": True}},
              "log": {"level": "INFO", "format": "json"},
              "accessLog": {"format": "json", "fields": {"defaultMode": "keep", "names": {"RequestPath": "drop", "RequestLine": "drop", "ClientUsername": "drop"}, "headers": {"defaultMode": "drop"}}},
              "metrics": {"prometheus": {"entryPoint": "metrics", "addRoutersLabels": True}},
              "tracing": {"serviceName": "api-gateway", "sampleRate": profile["sampling"], "otlp": {"http": {"endpoint": profile["otlp_endpoint"].rstrip("/") + "/v1/traces"}}}}
    return {name: yaml.safe_dump(value, sort_keys=False) for name, value in
            (("traefik.yml", static), ("dynamic.yml", dynamic), ("routes.yml", routes))}


def workload(name, component, image, profile, state_hash):
    ns = profile["namespace"]
    labels = {"app.kubernetes.io/name": name}
    account = resource("ServiceAccount", name, ns, wave=-20)
    account["automountServiceAccountToken"] = False
    account["metadata"]["annotations"].update(profile.get("service_account_annotations", {}).get(name, {}))
    ports = [{"name": n, "port": 80 if name == "api-gateway" and n == "web" else p, "targetPort": n,
              "appProtocol": "grpc" if n == "grpc" else "http"} for n, p in component["ports"].items()]
    service = resource("Service", name, ns, {"selector": labels, "ports": ports}, wave=-20)
    if name not in transport.DIRECT:
        service["metadata"]["labels"] = {"istio.io/use-waypoint": transport.WAYPOINT}
        labels = {**labels, transport.WORKLOAD_LABEL: "true"}
        if name == progressive.SERVICE and progressive.enabled(profile):
            labels["istio.io/use-waypoint"] = transport.WAYPOINT
    pod = pod_spec(image, profile, name)
    container = pod["containers"][0]
    # Explicit env entries permit Kubernetes $(VAR) expansion in the Kafka JAAS config.
    runtime_secret = external_secret(name, {k: k for k in component["runtime_properties"]}, profile)["spec"]["target"]["name"] if component["runtime_properties"] else None
    container["env"] = [{"name": k, "valueFrom": {"secretKeyRef": {"name": runtime_secret, "key": k}}}
                        for k in component["runtime_properties"]]
    substitutions = {"domain": profile["domain"], "otlp_endpoint": profile["otlp_endpoint"].rstrip("/"),
                     "sampling": str(profile["sampling"]), "trusted_proxy_cidrs": ",".join(profile["trusted_proxy_cidrs"])}
    container["env"] += [{"name": k, "value": v.format(**substitutions)} for k, v in component["env"].items()]
    container["env"].append({"name": "HOME", "value": "/tmp"})
    if component.get("migration") == "migrate":
        container["env"].append({"name": "PGSSLROOTCERT", "value": "/etc/platform-ca/ca.crt"})
    if component["kind"] == "java":
        container["command"] = ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar " + component["java_jar"]]
    container["ports"] = [{"name": k, "containerPort": v} for k, v in component["ports"].items()]
    container["startupProbe"] = probe(component["ready"], component["probe_port"], True)
    container["readinessProbe"] = probe(component["ready"], component["probe_port"])
    container["livenessProbe"] = probe(component["live"], component["probe_port"])
    container["lifecycle"] = {"preStop": {"exec": {"command": ["/bin/sh", "-c", "sleep 5"]}}}
    container["resources"] = {"requests": {"cpu": "250m" if component["kind"] == "java" else "100m", "memory": component["memory_request"]},
                              "limits": {"cpu": "2", "memory": component["memory_limit"]}}
    if name == "frontend":
        pod["volumes"].append({"name": "next-cache", "emptyDir": {"sizeLimit": "256Mi"}})
        container["volumeMounts"].append({"name": "next-cache", "mountPath": "/app/.next/cache"})
    extra = []
    if name == "order-service":
        temporal = profile["transport"]["temporal"]
        endpoint = next(e for e in profile["transport"]["endpoints"] if e["kind"] == "temporal")
        certificate = external_secret("temporal-client", temporal_secret_properties(profile), profile)
        mount = "/etc/temporal-client"
        pod["volumes"].append({"name": "temporal-client", "secret": {
            "secretName": certificate["spec"]["target"]["name"], "defaultMode": 0o440}})
        container["volumeMounts"].append({"name": "temporal-client", "mountPath": mount, "readOnly": True})
        settings = {"TEMPORAL_TARGET": f"{endpoint['host']}:{endpoint['port']}", "TEMPORAL_NAMESPACE": temporal["namespace"],
                    "TEMPORAL_TLS_MODE": temporal["mode"]}
        if temporal["mode"] == "mtls":
            settings.update(TEMPORAL_TLS_CERT_FILE=mount + "/tls.crt", TEMPORAL_TLS_KEY_FILE=mount + "/tls.key")
        else:
            settings["TEMPORAL_API_KEY_FILE"] = mount + "/api-key"
        if temporal.get("use_data_ca"):
            settings["TEMPORAL_TLS_CA_FILE"] = "/etc/data-ca/ca.crt"
        container["env"] += [{"name": k, "value": v} for k, v in settings.items()]
        extra.append(certificate)
    ca_bundle = profile["transport"].get("data_ca_pem")
    if ca_bundle:
        pod["volumes"].append({"name": "data-ca", "configMap": {"name": "data-ca-" + sha(ca_bundle)[:10]}})
        container["volumeMounts"].append({"name": "data-ca", "mountPath": "/etc/data-ca", "readOnly": True})
        ca_file = "/etc/data-ca/ca.crt"
        settings = {}
        if component["kind"] == "go":
            settings["SSL_CERT_FILE"] = ca_file
        if "SPRING_KAFKA_SECURITY_PROTOCOL" in component["env"]:
            settings.update(SPRING_KAFKA_SSL_TRUSTSTORETYPE="PEM", SPRING_KAFKA_SSL_TRUSTSTORELOCATION="file:" + ca_file)
        if name in {"checkout-service", "order-service", "promotion-service"}:
            settings.update(SPRING_SSL_BUNDLE_PEM_DATA_TRUSTSTORE_CERTIFICATE="file:" + ca_file,
                            SPRING_DATA_REDIS_SSL_BUNDLE="data")
        if name == "search-service":
            settings["SEARCH_ELASTICSEARCH_CA_FILE"] = ca_file
        container["env"] += [{"name": k, "value": v} for k, v in settings.items()]
    if name == "api-gateway":
        config_name = "gateway-config-" + state_hash[:10]
        cm = resource("ConfigMap", config_name, ns, wave=-20)
        cm["data"] = gateway_configuration(profile)
        extra.append(cm)
        pod["volumes"].append({"name": "gateway-config", "configMap": {"name": config_name}})
        for file, path in (("traefik.yml", "/etc/traefik/traefik.yml"), ("dynamic.yml", "/etc/traefik/dynamic/dynamic.yml"), ("routes.yml", "/etc/traefik/dynamic/routes.yml")):
            container["volumeMounts"].append({"name": "gateway-config", "mountPath": path, "subPath": file, "readOnly": True})
    pod["topologySpreadConstraints"] = [{"maxSkew": 1, "topologyKey": "kubernetes.io/hostname", "whenUnsatisfiable": "DoNotSchedule", "labelSelector": {"matchLabels": labels}}]
    deployment = resource("Deployment", name, ns, {"replicas": profile["replicas"], "revisionHistoryLimit": 3,
        "progressDeadlineSeconds": 900, "strategy": {"type": "RollingUpdate", "rollingUpdate": {"maxUnavailable": 0, "maxSurge": 1}},
        "selector": {"matchLabels": {transport.APP_LABEL: name}}, "template": {"metadata": {"labels": labels,
        "annotations": {"delivery.ecommerce/state-sha256": state_hash}}, "spec": pod}}, api="apps/v1")
    if progressive.canary_enabled(profile) and name == progressive.SERVICE:
        deployment = progressive.convert(deployment, profile)
    pdb = resource("PodDisruptionBudget", name, ns, {"maxUnavailable": 1, "selector": {"matchLabels": {transport.APP_LABEL: name}}}, api="policy/v1")
    return [account, service, *extra, deployment, pdb]


def migration_job(name, component, image, profile, state_hash):
    key = "migration-" + name
    properties = ["FLYWAY_URL", "FLYWAY_USER", "FLYWAY_PASSWORD"] if component["migration"] == "flyway" else ["MIGRATE_DATABASE_URL"]
    secret = external_secret(key, {k: k for k in properties}, profile)
    pod = pod_spec(image, profile, transport.MIGRATIONS)
    pod["restartPolicy"] = "Never"
    pod["containers"][0]["name"] = "migrate"
    pod["containers"][0]["env"] = [{"name": "MIGRATION_SERVICE", "value": name}]
    pod["containers"][0]["envFrom"] = [{"secretRef": {"name": secret["spec"]["target"]["name"]}}]
    pod["containers"][0]["resources"] = {"requests": {"cpu": "100m", "memory": "128Mi"}, "limits": {"cpu": "1", "memory": "512Mi"}}
    job = resource("Job", key, profile["namespace"], {"backoffLimit": 0, "activeDeadlineSeconds": 600,
        "template": {"metadata": {"labels": {transport.APP_LABEL: transport.MIGRATIONS,
            transport.MIGRATION_LABEL: name, "delivery.ecommerce/role": "migration"}}, "spec": pod}}, api="batch/v1", wave=-10)
    job["metadata"]["labels"] = {"delivery.ecommerce/role": "migration"}
    job["metadata"]["annotations"].update({"argocd.argoproj.io/hook": "Sync", "argocd.argoproj.io/hook-delete-policy": "BeforeHookCreation,HookSucceeded"})
    return [secret, job]


def provider_probes():
    probes = yaml.safe_load((ROOT / "contracts/tools/provider-probes.yaml").read_text(encoding="utf-8"))
    allowed = {"product-export-page", "product-category-export-page", "product-snapshot-for-search", "product-snapshot-unknown",
               "product-detail-for-bff", "product-detail-unknown-slug", "review-pdp-summary", "review-pdp-summary-wrong-caller",
               "inventory-availability-for-bff", "inventory-allocation-preview", "inventory-allocation-preview-invalid",
               "promotion-discount-without-code", "order-submission-unknown-token", "payment-unknown-transaction", "payment-wrong-caller"}
    probes["probes"] = [probe for probe in probes["probes"] if probe["id"] in allowed]
    return probes


def render_environment(config, environment, images, release_id, revision, configuration_revision, source_run, rollback_revisions):
    components = catalog()
    p = config["environments"][environment]
    probes = provider_probes()
    security = transport.mesh_network_policies(p, components) + transport.mesh_application_resources(p, components, probes)
    state = {"schema_version": 2, "environment": environment, "release_id": release_id,
             "source_repository": config["source_repository"], "source_revision": revision,
             "configuration_revision": configuration_revision, "source_run_id": str(source_run),
             "namespace": p["namespace"], "profile_sha256": sha(p), "images": images,
             "transport_sha256": transport.transport_digest(security),
             "rollback_compatible_revisions": rollback_revisions}
    if progressive.enabled(p):
        state["workload_kinds"] = progressive.workload_kinds(p)
    state["state_sha256"] = sha(state)
    state_hash = state["state_sha256"]
    resources = []
    ca = resource("ConfigMap", "database-ca-" + sha(p["database_ca_pem"])[:10], p["namespace"], wave=-20)
    ca["data"] = {"ca.crt": p["database_ca_pem"]}
    resources.append(ca)
    if data_ca := p["transport"].get("data_ca_pem"):
        ca = resource("ConfigMap", "data-ca-" + sha(data_ca)[:10], p["namespace"], wave=-20)
        ca["data"] = {"ca.crt": data_ca}
        resources.append(ca)
    registry = external_secret("registry", {".dockerconfigjson": "dockerconfigjson"}, p,
                               target_name=p["image_pull_secret"], secret_type="kubernetes.io/dockerconfigjson")
    resources.append(registry)
    for name, component in components.items():
        if component["runtime_properties"]:
            resources.append(external_secret(name, {k: k for k in component["runtime_properties"]}, p))
        resources.extend(workload(name, component, images[name]["reference"], p, state_hash))
        if component.get("migration"):
            resources.extend(migration_job(name, component, images["database-migrations"]["reference"], p, state_hash))
    for obj in resources:
        if obj["kind"] in ("Deployment", "Rollout"):
            obj["spec"]["template"]["metadata"]["labels"] = {**obj["spec"]["template"]["metadata"]["labels"], "delivery.ecommerce/revision": revision}
    if progressive.enabled(p):
        monitoring_profile = copy.deepcopy(p)
        monitoring_profile["operations"]["source_repository"] = config["source_repository"]
        resources.extend(progressive.monitoring(monitoring_profile, resource))
        if progressive.canary_enabled(p):
            metrics_secret = external_secret("operations-metrics", {"authorization": "authorization"}, p)
            resources += [metrics_secret, progressive.analysis_template(p, metrics_secret["spec"]["target"]["name"], resource)]
    for name in (transport.CHECKS, transport.MIGRATIONS):
        account = resource("ServiceAccount", name, p["namespace"], wave=-20)
        account["automountServiceAccountToken"] = False
        resources.append(account)
    resources.extend(security)
    paths = [{"path": path, "pathType": kind, "backend": {"service": {"name": service, "port": {"number": port}}}}
             for path, kind, service, port in (("/api", "Prefix", "api-gateway", 80), ("/media", "Prefix", "api-gateway", 80),
                ("/v1/payments/webhook/stripe", "Exact", "api-gateway", 80), ("/", "Prefix", "frontend", 3000))]
    resources.append(resource("Ingress", "storefront", p["namespace"], {"ingressClassName": p["ingress_class"],
        "tls": [{"hosts": [p["domain"]], "secretName": p["tls_secret"]}],
        "rules": [{"host": p["domain"], "http": {"paths": paths}}]}, api="networking.k8s.io/v1"))
    # The ingress controller must redirect plaintext HTTP to HTTPS; issuer/TLS Secret are platform-owned.
    checks_config = {"environment": environment, "state_sha256": state_hash,
        "health": [{"name": name, "url": f"http://{name}:{component['ports'][component['probe_port']]}{component['ready']}"}
                   for name, component in components.items()], "frontend": "http://frontend:3000/help/about"}
    checks_config_name = "deployment-checks-" + state_hash[:10]
    cm = resource("ConfigMap", checks_config_name, p["namespace"], wave=-20)
    cm["data"] = {"checks.json": json.dumps(checks_config), "provider-probes.yaml": yaml.safe_dump(probes, sort_keys=False)}
    resources.append(cm)
    pod = pod_spec(images["deployment-checks"]["reference"], p, "deployment-checks")
    pod["restartPolicy"] = "Never"
    pod["volumes"].append({"name": "checks", "configMap": {"name": checks_config_name}})
    container = pod["containers"][0]
    container["volumeMounts"].append({"name": "checks", "mountPath": "/checks", "readOnly": True})
    container["command"] = ["/bin/sh", "-c", "python /app/checks.py --config /checks/checks.json --probes /checks/provider-probes.yaml" +
                            (" && python /app/e2e.py" if environment == "staging" else "")]
    container["env"] = [{"name": "DEPLOYMENT_ENVIRONMENT", "value": environment},
                        {"name": "STOREFRONT_URL", "value": "https://" + p["domain"]},
                        {"name": "API_BASE_URL", "value": "https://" + p["domain"]},
                        {"name": "E2E_ALLOWED_NAMESPACE", "value": p["namespace"]},
                        {"name": "POD_NAMESPACE", "valueFrom": {"fieldRef": {"fieldPath": "metadata.namespace"}}}]
    if environment == "staging":
        e2e_secret = external_secret("checks-e2e", {k: k for k in E2E_PROPERTIES}, p)
        resources.append(e2e_secret)
        container["envFrom"] = [{"secretRef": {"name": e2e_secret["spec"]["target"]["name"]}}]
    container["resources"] = {"requests": {"cpu": "100m", "memory": "128Mi"}, "limits": {"cpu": "1", "memory": "512Mi"}}
    hook = resource("Job", "verify-" + state_hash[:16], p["namespace"], {"backoffLimit": 0, "activeDeadlineSeconds": 900,
        "template": {"metadata": {"labels": {"app.kubernetes.io/name": "deployment-checks", "delivery.ecommerce/role": "verification"}}, "spec": pod}}, api="batch/v1")
    hook["metadata"]["annotations"].update({"argocd.argoproj.io/hook": "PostSync", "argocd.argoproj.io/hook-delete-policy": "BeforeHookCreation,HookSucceeded"})
    resources.append(hook)
    return state, resources


def write_bundle(path, profile, state, resources):
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    write_json(path / "state.json", state)
    write_json(path / "profile.json", profile)
    (path / "resources.yaml").write_text(yaml.safe_dump_all(resources, sort_keys=False), encoding="utf-8", newline="\n")
    (path / "kustomization.yaml").write_text("apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - resources.yaml\n", encoding="utf-8", newline="\n")


def bootstrap_resources(config):
    result = {}
    for environment in ENVIRONMENTS:
        p = config["environments"][environment]
        namespace = {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": p["namespace"], "labels": {
            "pod-security.kubernetes.io/enforce": "restricted", "pod-security.kubernetes.io/audit": "restricted",
            "pod-security.kubernetes.io/warn": "restricted", "istio.io/dataplane-mode": "ambient"}}}
        project = {"apiVersion": "argoproj.io/v1alpha1", "kind": "AppProject", "metadata": {"name": "ecommerce-" + environment, "namespace": "argocd"}, "spec": {
            "sourceRepos": ["https://github.com/" + config["gitops_repository"] + ".git"],
            "destinations": [{"server": p["cluster_server"], "namespace": p["namespace"]}],
            "clusterResourceWhitelist": [], "namespaceResourceWhitelist": [
                {"group": group, "kind": kind} for group, kind in (("", "ServiceAccount"), ("", "Service"), ("", "ConfigMap"),
                    ("apps", "Deployment"), ("batch", "Job"), ("policy", "PodDisruptionBudget"), ("networking.k8s.io", "Ingress"),
                    ("networking.k8s.io", "NetworkPolicy"), ("external-secrets.io", "ExternalSecret"),
                    ("security.istio.io", "AuthorizationPolicy"), ("networking.istio.io", "ServiceEntry"),
                    ("networking.istio.io", "VirtualService"), ("networking.istio.io", "DestinationRule"))]}}
        if progressive.enabled(p):
            project["spec"]["namespaceResourceWhitelist"] += [{"group": "monitoring.coreos.com", "kind": k} for k in ("PodMonitor", "PrometheusRule")]
        if progressive.canary_enabled(p):
            project["spec"]["namespaceResourceWhitelist"] += [{"group": "argoproj.io", "kind": k} for k in ("Rollout", "AnalysisTemplate")]
        app = {"apiVersion": "argoproj.io/v1alpha1", "kind": "Application", "metadata": {"name": "ecommerce-" + environment, "namespace": "argocd"}, "spec": {
            "project": project["metadata"]["name"], "source": {"repoURL": "https://github.com/" + config["gitops_repository"] + ".git", "targetRevision": "main", "path": "environments/" + environment},
            "destination": {"server": p["cluster_server"], "namespace": p["namespace"]},
            "syncPolicy": {"automated": {"prune": True, "selfHeal": True, "allowEmpty": False}, "syncOptions": ["PruneLast=true", "FailOnSharedResource=true"]}}}
        mesh_project = copy.deepcopy(project)
        mesh_project["metadata"]["name"] = "ecommerce-" + environment + "-mesh"
        mesh_project["spec"]["namespaceResourceWhitelist"] = [{"group": group, "kind": kind}
            for group, kind in (("", "ConfigMap"), ("security.istio.io", "PeerAuthentication"), ("gateway.networking.k8s.io", "Gateway"),
                                ("policy", "PodDisruptionBudget"), ("autoscaling", "HorizontalPodAutoscaler"))]
        mesh_app = copy.deepcopy(app)
        mesh_app["metadata"]["name"] = mesh_project["metadata"]["name"]
        mesh_app["spec"]["project"] = mesh_project["metadata"]["name"]
        mesh_app["spec"]["source"]["path"] = f"bootstrap/{environment}/mesh"
        result[environment] = {"namespace": namespace, "argocd": [project, app, mesh_project, mesh_app],
                               "mesh": transport.platform_resources(p)}
    return result

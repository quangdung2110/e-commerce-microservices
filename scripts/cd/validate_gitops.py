#!/usr/bin/env python3
"""Offline GitOps PR gate: immutable candidates, exact pointers and stale-state protection."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess

import yaml
import progressive
import transport
from transport_evidence import load_report, timestamp

from common import ROOT, SHA, catalog, read_json, sha, tree_sha, validate_manifest
from render import validate_config

FILES = {"resources.yaml", "state.json", "profile.json", "kustomization.yaml"}
ALLOWED = {"ServiceAccount", "Service", "ConfigMap", "Deployment", "Job", "PodDisruptionBudget", "Ingress", "NetworkPolicy", "ExternalSecret"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def documents(path):
    return list(yaml.safe_load_all(Path(path).read_text(encoding="utf-8")))


def bundle(path, config, environment, rollback=False):
    path = Path(path)
    require({p.name for p in path.iterdir()} == FILES, "Unexpected bundle files")
    tree_sha(path)  # Reject symlinks as well as path substitutions.
    state, profile = read_json(path / "state.json"), read_json(path / "profile.json")
    progressive.validate(profile)
    require(state.get("schema_version") in {1, 2}, "Unknown bundle schema")
    ambient = state["schema_version"] == 2
    if ambient:
        transport.validate_profile(profile, environment, catalog())
    require(state.get("state_sha256") == sha({k: v for k, v in state.items() if k != "state_sha256"}), "State checksum mismatch")
    require(state["environment"] == environment and state["source_repository"] == config["source_repository"], "State environment/source mismatch")
    require(SHA.fullmatch(state["configuration_revision"]) and state["profile_sha256"] == sha(profile) and state["namespace"] == profile["namespace"], "Profile binding mismatch")
    images = validate_manifest({"schema_version": 1, "repository": state["source_repository"], "revision": state["source_revision"],
                               "run_id": state["source_run_id"], "images": list(state["images"].values())}, config["source_repository"])
    require(all(SHA.fullmatch(r) for r in state["rollback_compatible_revisions"]), "Invalid rollback compatibility revision")
    kustomize = yaml.safe_load((path / "kustomization.yaml").read_text(encoding="utf-8"))
    require(kustomize == {"apiVersion": "kustomize.config.k8s.io/v1beta1", "kind": "Kustomization", "resources": ["resources.yaml"]}, "Kustomize may only load the frozen resources")
    resources = documents(path / "resources.yaml")
    identities, deployments, migrations, pdbs = set(), set(), set(), set()
    hooks, default_deny, ingress = [], False, False
    for obj in resources:
        kind, meta = obj["kind"], obj["metadata"]
        identity = (kind, meta["name"])
        optional = {"PodMonitor", "PrometheusRule"} if progressive.enabled(profile) else set()
        if progressive.canary_enabled(profile):
            optional |= {"Rollout", "AnalysisTemplate"}
        if ambient:
            optional |= transport.MESH_KINDS
        require(kind in ALLOWED | optional and meta.get("namespace") == state["namespace"] and identity not in identities, "Unknown, duplicate or cross-namespace resource")
        identities.add(identity)
        annotations = meta.get("annotations", {})
        spec = obj.get("spec", {})
        if ambient and kind in transport.MESH_KINDS:
            expected_api = transport.SECURITY_API if kind == "AuthorizationPolicy" else transport.MESH_NETWORK_API
            require(obj["apiVersion"] == expected_api, "Incorrect Istio API group")
        if ambient and kind == "Service":
            name = meta["name"]
            require(name in catalog() and all(port.get("appProtocol") == ("grpc" if port["name"] == "grpc" else "http")
                                               for port in spec["ports"]), "Service protocol declaration missing")
            waypoint = meta.get("labels", {}).get("istio.io/use-waypoint")
            require(waypoint == (None if name in transport.DIRECT else transport.WAYPOINT), "Service waypoint binding mismatch")
        if kind == "ExternalSecret":
            require(obj["apiVersion"] == "external-secrets.io/v1" and annotations.get("argocd.argoproj.io/sync-wave") == "-30", "Secrets must precede all application work")
            require(spec["refreshPolicy"] == "OnChange" and all(d["remoteRef"].get("version") not in (None, "", "latest") for d in spec["data"]), "Unpinned secret")
            require("dataFrom" not in spec and "data" not in spec["target"].get("template", {}), "Raw/wildcard secret data is forbidden")
        if kind in ("Deployment", "Rollout", "Job"):
            pod = spec["template"]["spec"]
            require(pod.get("automountServiceAccountToken") is False and not pod.get("hostNetwork") and not pod.get("hostPID"), "Unsafe pod identity/network")
            security = pod.get("securityContext", {})
            require(security.get("runAsNonRoot") is True and security.get("runAsUser", 0) > 0 and security.get("seccompProfile", {}).get("type") == "RuntimeDefault", "Unsafe pod security")
            require(not pod.get("initContainers") and len(pod["containers"]) == 1 and not any("hostPath" in v for v in pod.get("volumes", [])), "Unexpected container or host volume")
            c = pod["containers"][0]
            security = c.get("securityContext", {})
            require(c["image"] in {i["reference"] for i in images.values()} and c.get("imagePullPolicy") != "Never", "Unsigned/unbound image")
            require(security.get("readOnlyRootFilesystem") is True and security.get("allowPrivilegeEscalation") is False and security.get("capabilities", {}).get("drop") == ["ALL"], "Unsafe container security")
            require(c.get("resources", {}).get("requests") and c["resources"].get("limits"), "Missing resource budget")
            values = {e["name"]: e.get("value") for e in c.get("env", [])}
            require("PAYMENT_CRYPTO_LOCAL_KEY" not in values, "Local crypto cannot be activated")
            if "SPRING_PROFILES_ACTIVE" in values:
                require(values["SPRING_PROFILES_ACTIVE"] == "production" and values.get("SPRING_FLYWAY_ENABLED") == "false", "Applications must not execute migrations or dev profiles")
            if kind in ("Deployment", "Rollout"):
                name = meta["name"]
                if ambient:
                    labels = spec["template"]["metadata"]["labels"]
                    require(pod["serviceAccountName"] == name and labels[transport.APP_LABEL] == name,
                            "Workload must use its own identity")
                    require(labels.get(transport.WORKLOAD_LABEL) == (None if name in transport.DIRECT else "true"),
                            "Workload waypoint guard selector mismatch")
                    pod_waypoint = transport.WAYPOINT if progressive.enabled(profile) and name == progressive.SERVICE else None
                    require(labels.get("istio.io/use-waypoint") == pod_waypoint, "Unexpected workload waypoint enrollment")
                    require("ambient.istio.io/redirection" not in spec["template"]["metadata"].get("annotations", {})
                            and "sidecar.istio.io/inject" not in spec["template"]["metadata"].get("annotations", {})
                            and "istio.io/dataplane-mode" not in labels and "sidecar.istio.io/inject" not in labels,
                            "Workloads must inherit ambient enrollment")
                deployments.add(name)
                require(name in catalog() and c["image"] == images[name]["reference"] and spec["replicas"] >= 2, "Deployment inventory/image/replicas mismatch")
                expected_kind = progressive.workload_kinds(profile).get(name, "Deployment")
                require(kind == expected_kind and state.get("workload_kinds", {}) == progressive.workload_kinds(profile), "Workload kind differs from reviewed canary policy")
                if kind == "Rollout":
                    expected = progressive.convert({"spec": {"strategy": {}, "replicas": 2}}, profile)["spec"]
                    require(spec["strategy"] == expected["strategy"] and spec["replicas"] == expected["replicas"] and spec.get("progressDeadlineAbort") is True, "Unsafe canary strategy")
                else:
                    require(spec["strategy"] == {"type": "RollingUpdate", "rollingUpdate": {"maxUnavailable": 0, "maxSurge": 1}}, "Unsafe rollout strategy")
                require(all(k in c for k in ("startupProbe", "readinessProbe", "livenessProbe")), "Missing probes")
                require(spec["template"]["metadata"]["annotations"]["delivery.ecommerce/state-sha256"] == state["state_sha256"], "Stale deployment annotation")
            elif meta.get("labels", {}).get("delivery.ecommerce/role") == "migration":
                migrations.add(meta["name"])
                if ambient:
                    labels = spec["template"]["metadata"]["labels"]
                    require(pod["serviceAccountName"] == transport.MIGRATIONS
                            and labels[transport.APP_LABEL] == transport.MIGRATIONS
                            and labels[transport.MIGRATION_LABEL] == meta["name"].removeprefix("migration-"),
                            "Migration identity or egress selector mismatch")
                require(annotations.get("argocd.argoproj.io/hook") == "Sync" and annotations.get("argocd.argoproj.io/sync-wave") == "-10", "Migration ordering mismatch")
                require(c["image"] == images["database-migrations"]["reference"] and not c.get("command") and not c.get("args"), "Migration runner override")
            else:
                hooks.append(obj)
                if ambient:
                    require(pod["serviceAccountName"] == transport.CHECKS
                            and spec["template"]["metadata"]["labels"][transport.APP_LABEL] == transport.CHECKS,
                            "PostSync check identity mismatch")
                require(c["image"] == images["deployment-checks"]["reference"] and annotations.get("argocd.argoproj.io/hook") == "PostSync", "Unrecognized job")
                require(("/app/e2e.py" in " ".join(c.get("command", []))) == (environment == "staging"), "Mutating E2E must run only in staging")
                require((bool(c.get("envFrom"))) == (environment == "staging"), "Production checks must not receive E2E credentials")
        if kind == "PodDisruptionBudget":
            pdbs.add(meta["name"])
            require(spec.get("maxUnavailable") == 1, "PDB missing availability budget")
        if kind == "NetworkPolicy" and meta["name"] == "default-deny":
            default_deny = spec == {"podSelector": {}, "policyTypes": ["Ingress", "Egress"]}
        if kind == "Ingress":
            ingress = spec.get("tls") == [{"hosts": [profile["domain"]], "secretName": profile["tls_secret"]}]
    require(deployments == set(catalog()) == pdbs and default_deny and ingress, "Incomplete application availability/network/TLS policy")
    if ambient:
        require(state.get("transport_sha256") == transport.transport_digest(resources), "Transport policy digest mismatch")
        policies = [obj for obj in resources if obj["kind"] == "NetworkPolicy"]
        require(policies == transport.mesh_network_policies(profile, catalog()), "Effective NetworkPolicy allowlist differs from the reviewed profile")
        mesh = {(obj["kind"], obj["metadata"]["name"]): obj for obj in resources if obj["kind"] in transport.MESH_KINDS}
        guards = transport.mesh_guards(profile, catalog()) + transport.mesh_egress(profile)
        expected_auth = {obj["metadata"]["name"] for obj in guards if obj["kind"] == "AuthorizationPolicy"}
        expected_auth.update("allow-" + service for service in catalog())
        require({name for kind, name in mesh if kind == "AuthorizationPolicy"} == expected_auth,
                "Istio authorization policy inventory differs from the reviewed profile")
        require({name for kind, name in mesh if kind == "ServiceEntry"} ==
                {obj["metadata"]["name"] for obj in guards if obj["kind"] == "ServiceEntry"},
                "Public waypoint endpoints differ from profile")
        for obj in guards:
            require(mesh.get((obj["kind"], obj["metadata"]["name"])) == obj, "Istio guard or egress authorization differs from profile")
        for name in set(catalog()) - transport.DIRECT:
            spec = mesh["AuthorizationPolicy", "allow-" + name]["spec"]
            require(spec.get("action") == "ALLOW" and spec.get("targetRefs") == [{"group": "", "kind": "Service", "name": name}]
                    and all(rule.get("from") and rule.get("to") for rule in spec.get("rules", []))
                    and spec.get("rules"), "Expected explicit authorization rules for " + name)

    if progressive.enabled(profile):
        require(("PodMonitor", "bff-slo") in identities and ("PrometheusRule", "bff-slo") in identities, "Missing SLO monitoring")
        from render import resource
        monitoring_profile = json.loads(json.dumps(profile))
        monitoring_profile["operations"]["source_repository"] = config["source_repository"]
        for expected in progressive.monitoring(monitoring_profile, resource):
            require(expected in resources, "SLO rules or scrape/revision labels were modified")
    if progressive.canary_enabled(profile):
        secrets = [o for o in resources if o["kind"] == "ExternalSecret" and o["metadata"]["name"].startswith("operations-metrics-")]
        templates = [o for o in resources if o["kind"] == "AnalysisTemplate"]
        require(len(secrets) == len(templates) == 1, "Missing or extra canary metric credential/template")
        from render import resource
        require(templates[0] == progressive.analysis_template(profile, secrets[0]["spec"]["target"]["name"], resource), "Canary metric policy was modified")
    expected_migrations = {"migration-" + n for n, c in catalog().items() if c.get("migration")}
    require(migrations == (set() if rollback else expected_migrations), "Missing or unexpected migration jobs")
    require(len(hooks) == 1 and hooks[0]["metadata"]["name"] == "verify-" + state["state_sha256"][:16], "Verification hook does not bind this release")
    return state


def git_bytes(root, base, path):
    result = subprocess.run(["git", "show", base + ":" + path], cwd=root, capture_output=True)
    return result.stdout if result.returncode == 0 else None


def validate(root, base=None, author=None, same_repository=True):
    root = Path(root)
    config = read_json(root / "config.json")
    validate_config(config)
    for env, profile in config["environments"].items():
        platform = root / "bootstrap" / env
        namespace = yaml.safe_load((platform / "namespace.yaml").read_text(encoding="utf-8"))
        require(namespace["metadata"]["name"] == profile["namespace"] and
                namespace["metadata"]["labels"].get("istio.io/dataplane-mode") == "ambient"
                and namespace["metadata"]["labels"].get("pod-security.kubernetes.io/enforce") == "restricted",
                "The platform namespace must enroll in ambient and enforce Restricted Pod Security")
        require(documents(platform / "mesh/resources.yaml") == transport.platform_resources(profile),
                "Bootstrap mesh resources differ from the reviewed platform profile")
    if base:
        paths = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", base, "HEAD"], cwd=root, text=True).splitlines()
        for path in paths:
            if path.startswith("transport-evidence/"):
                require(git_bytes(root, base, path) is None, "Previously reviewed transport evidence is immutable")
            if path.startswith("candidates/"):
                # Once any candidate directory exists at base, every byte is immutable.
                prefix = "/".join(path.split("/")[:2])
                existed = subprocess.check_output(["git", "ls-tree", base, "--", prefix], cwd=root)
                require(not existed, "Historical candidates cannot be changed/deleted")
        production_changed = any(p.startswith("environments/production/") for p in paths)
        if production_changed and author is not None:
            require(same_repository and author == config["gitops_bot_login"], "Production bundles must originate from the delivery GitHub App PR")
    candidates = {}
    for path in sorted((root / "candidates").glob("*")):
        require(path.is_dir() and not path.is_symlink(), "Invalid candidate directory")
        metadata = read_json(path / "metadata.json")
        require(path.name == metadata["release_id"], "Candidate identity mismatch")
        states = {}
        for env in ("staging", "production"):
            require(tree_sha(path / env) == metadata[env + "_bundle_sha256"], "Candidate bundle checksum mismatch")
            states[env] = bundle(path / env, config, env)
            require(states[env]["release_id"] == path.name and states[env]["source_revision"] == metadata["source_revision"], "Candidate source mismatch")
        require(states["staging"]["images"] == states["production"]["images"], "Promotion must reuse the exact staging images")
        candidates[path.name] = path
    evidence_root = root / "transport-evidence" / "staging"
    require(not evidence_root.is_symlink() and not evidence_root.parent.is_symlink(), "Transport evidence cannot be a symlink")
    for dossier in evidence_root.glob("*"):
        require(dossier.is_dir() and not dossier.is_symlink() and dossier.name in candidates, "Transport evidence must name a candidate")
        candidate = candidates[dossier.name]
        for path in dossier.iterdir():
            require(path.suffix == ".json", "Unexpected transport evidence file")
            load_report(path, read_json(candidate / "staging/profile.json"), read_json(candidate / "staging/state.json"),
                        read_json(candidate / "metadata.json"))
    for env in ("staging", "production"):
        path = root / "environments" / env
        if not (path / "state.json").exists():
            require(not base or git_bytes(root, base, "environments/" + env + "/state.json") is None, "An initialized environment cannot be reset to an empty bootstrap")
            require({p.name for p in path.iterdir()} == {"kustomization.yaml"} and yaml.safe_load((path / "kustomization.yaml").read_text(encoding="utf-8")).get("resources") == [], "Bootstrap environments must remain empty until a verified release exists")
            continue
        operation_path = "operations/" + env + ".json"
        operation = read_json(root / operation_path)
        rollback = operation["operation"] == "rollback"
        require(operation["operation"] in (("stage",) if env == "staging" else ("promote", "rollback")), "Invalid environment operation")
        state = bundle(path, config, env, rollback)
        require(operation["release_id"] == state["release_id"] and state["release_id"] in candidates, "Environment points to an unknown candidate")
        source = candidates[state["release_id"]] / env
        if env == "production" and state["schema_version"] == 2 and not rollback:
            evidence = operation.get("transport_evidence_sha256", "")
            require(bool(re.fullmatch(r"[a-f0-9]{64}", evidence)), "Production requires reviewed transport evidence")
            report_path = evidence_root / state["release_id"] / (evidence + ".json")
            require(report_path.is_file(), "The reviewed transport report is missing")
            if base and any(p.startswith("environments/production/") for p in paths):
                report = read_json(report_path)
                stage = read_json(root / "environments/staging/state.json")
                age = (datetime.now(timezone.utc) - timestamp(report["verified_at"])).total_seconds()
                require(stage["state_sha256"] == report["state_sha256"] and -60 <= age <= config["proof_max_age_hours"] * 3600
                        and str(operation["verification_run_id"]) == str(report["verification_run_id"]),
                        "Production transport evidence is stale or staging changed")
        for name in FILES:
            if rollback and name == "resources.yaml":
                expected = [o for o in documents(source / name) if not (o["kind"] == "Job" and o["metadata"].get("labels", {}).get("delivery.ecommerce/role") == "migration")]
                require(documents(path / name) == expected, "Rollback may only remove migration hooks")
            else:
                require((source / name).read_bytes() == (path / name).read_bytes(), "Environment differs from its frozen candidate")
        if base and any(p.startswith("environments/" + env + "/") for p in paths):
            require(state["schema_version"] == 2, "New environment changes require the ambient transport policy")
            prior = git_bytes(root, base, "environments/" + env + "/state.json")
            previous = json.loads(prior) if prior else None
            require(operation.get("expected_previous_state") == (previous["state_sha256"] if previous else None), "Stale release PR; rebuild against current main")
            if rollback:
                require(previous and state["source_revision"] in previous["rollback_compatible_revisions"], "Rollback compatibility was not reviewed in the current release")
                prior_op = git_bytes(root, base, operation_path)
                require(not prior_op or json.loads(prior_op)["operation"] != "rollback", "Chained rollback requires a new reviewed release")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--base")
    args = parser.parse_args()
    validate(args.root, args.base, os.environ.get("PR_AUTHOR"), os.environ.get("PR_SAME_REPOSITORY", "true") == "true")
    print("GitOps validation passed")

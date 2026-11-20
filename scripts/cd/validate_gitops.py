#!/usr/bin/env python3
"""Offline GitOps PR gate: immutable candidates, exact pointers and stale-state protection."""
import argparse
import json
import os
from pathlib import Path
import subprocess

import yaml
import progressive

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
        require(kind in ALLOWED | optional and meta.get("namespace") == state["namespace"] and identity not in identities, "Unknown, duplicate or cross-namespace resource")
        identities.add(identity)
        annotations = meta.get("annotations", {})
        spec = obj.get("spec", {})
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
                require(annotations.get("argocd.argoproj.io/hook") == "Sync" and annotations.get("argocd.argoproj.io/sync-wave") == "-10", "Migration ordering mismatch")
                require(c["image"] == images["database-migrations"]["reference"] and not c.get("command") and not c.get("args"), "Migration runner override")
            else:
                hooks.append(obj)
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
    if base:
        paths = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", base, "HEAD"], cwd=root, text=True).splitlines()
        for path in paths:
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
        for name in FILES:
            if rollback and name == "resources.yaml":
                expected = [o for o in documents(source / name) if not (o["kind"] == "Job" and o["metadata"].get("labels", {}).get("delivery.ecommerce/role") == "migration")]
                require(documents(path / name) == expected, "Rollback may only remove migration hooks")
            else:
                require((source / name).read_bytes() == (path / name).read_bytes(), "Environment differs from its frozen candidate")
        if base and any(p.startswith("environments/" + env + "/") for p in paths):
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

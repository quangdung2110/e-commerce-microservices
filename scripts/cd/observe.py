#!/usr/bin/env python3
"""Read-only Argo CD verification, bound to exact desired state and PostSync hook."""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import progressive

from common import SHA, catalog, now, read_json, sha, tree_sha, write_json
from render import validate_config


def evaluate(app, managed, state):
    errors = []
    status = app.get("status", {})
    if status.get("sync", {}).get("status") != "Synced" or status.get("health", {}).get("status") != "Healthy":
        errors.append("Application must be Synced and Healthy")
    operation = status.get("operationState", {})
    if operation.get("phase") != "Succeeded":
        errors.append("The full sync operation has not succeeded")
    if (not SHA.fullmatch(status.get("sync", {}).get("revision", "")) or
            operation.get("syncResult", {}).get("revision") != status.get("sync", {}).get("revision")):
        errors.append("The completed operation must match the currently synchronized GitOps commit")
    hook_name = "verify-" + state["state_sha256"][:16]
    if not any(r.get("kind") == "Job" and r.get("name") == hook_name and r.get("namespace") == state["namespace"] and r.get("hookPhase") == "Succeeded"
               for r in operation.get("syncResult", {}).get("resources", [])):
        errors.append("No successful PostSync verification hook for this exact state")
    deployments = {}
    for item in managed.get("items", []):
        if item.get("kind") in ("Deployment", "Rollout"):
            live = item.get("liveState") or "{}"
            if item["name"] in deployments:
                errors.append("Duplicate Deployment/Rollout for " + item["name"])
            deployments[item["name"]] = json.loads(live) if isinstance(live, str) else live
            deployments[item["name"]]["kind"] = item["kind"]
    if set(deployments) != set(catalog()):
        errors.append("Live deployment inventory does not match the signed release")
    for name, obj in deployments.items():
        if name not in state["images"]:
            continue
        metadata, spec, live_status = obj.get("metadata", {}), obj.get("spec", {}), obj.get("status", {})
        expected_kind = state.get("workload_kinds", {}).get(name, "Deployment")
        if obj.get("kind") != expected_kind:
            errors.append(name + ": workload controller differs from tested state")
        if expected_kind == "Rollout" and (live_status.get("phase") != "Healthy" or
                not live_status.get("currentPodHash") or live_status.get("stableRS") != live_status.get("currentPodHash") or
                live_status.get("abort") or live_status.get("pauseConditions")):
            errors.append(name + ": canary has not completed successfully")
        template = spec.get("template", {})
        containers = template.get("spec", {}).get("containers", [])
        if (metadata.get("namespace") != state["namespace"] or
                template.get("metadata", {}).get("annotations", {}).get("delivery.ecommerce/state-sha256") != state["state_sha256"] or
                len(containers) != 1 or containers[0].get("image") != state["images"][name]["reference"]):
            errors.append(name + ": live configuration/image differs from the tested state")
        replicas = spec.get("replicas", 0)
        if (replicas < 2 or live_status.get("updatedReplicas", 0) != replicas or live_status.get("readyReplicas", 0) != replicas or
                int(live_status.get("observedGeneration", 0) or 0) < metadata.get("generation", 1)):
            errors.append(name + ": rollout has not completed")
    return errors


def argo_get(base, path, token):
    request = urllib.request.Request(base.rstrip("/") + path, headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Argo CD read failed with HTTP {error.code}; body redacted") from None


def observe(config, environment, bundle, timeout=1200):
    validate_config(config, activation=True)
    state = read_json(Path(bundle) / "state.json")
    if state.get("state_sha256") != sha({k: v for k, v in state.items() if k != "state_sha256"}) or state["environment"] != environment:
        raise ValueError("Deployment state has been modified")
    token = os.environ["ARGOCD_AUTH_TOKEN"]
    if progressive.canary_enabled(read_json(Path(bundle) / "profile.json")):
        timeout = max(timeout, 2700)
    name = "ecommerce-" + environment
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        app = argo_get(config["argocd_url"], "/api/v1/applications/" + name + "?refresh=normal", token)
        managed = argo_get(config["argocd_url"], "/api/v1/applications/" + name + "/managed-resources", token)
        status = app.get("status", {})
        operation = status.get("operationState", {})
        # A failed previous release must not abort observation before the new GitOps PR merges.
        targets_this_state = False
        for item in managed.get("items", []):
            if item.get("kind") not in ("Deployment", "Rollout"):
                continue
            target = item.get("targetState") or "{}"
            target = json.loads(target) if isinstance(target, str) else target
            if target.get("spec", {}).get("template", {}).get("metadata", {}).get("annotations", {}).get("delivery.ecommerce/state-sha256") == state["state_sha256"]:
                targets_this_state = True
        if (targets_this_state and operation.get("phase") in ("Failed", "Error") and
                operation.get("syncResult", {}).get("revision") == status.get("sync", {}).get("revision")):
            raise RuntimeError("Argo CD sync or PostSync checks failed; promotion is blocked")
        if not evaluate(app, managed, state):
            proof = {"schema_version": 1, "environment": environment, "release_id": state["release_id"],
                    "state_sha256": state["state_sha256"], "bundle_sha256": tree_sha(bundle),
                    "source_revision": state["source_revision"], "source_repository": config["source_repository"],
                    "gitops_revision": app["status"]["sync"]["revision"], "verified_at": now(),
                    "verification_run_id": os.environ["GITHUB_RUN_ID"], "verification_run_attempt": os.environ["GITHUB_RUN_ATTEMPT"]}
            profile = read_json(Path(bundle) / "profile.json")
            proof["slo_enforced"] = progressive.enabled(profile) and progressive.settings(profile)["slo"].get("enforce") is True
            if proof["slo_enforced"]:
                from slo import evaluate_profile
                proof["slo"] = evaluate_profile(profile, state)
                if proof["slo"]["result"] != "passed":
                    raise RuntimeError("SLO assessment failed; proof and promotion are blocked")
            return proof
        time.sleep(5)
    raise RuntimeError("Argo CD did not converge to the exact release before timeout")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--environment", required=True, choices=("staging", "production"))
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    write_json(args.output, observe(read_json(args.config), args.environment, args.bundle))

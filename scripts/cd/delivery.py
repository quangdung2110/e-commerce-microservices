#!/usr/bin/env python3
"""Bootstrap GitOps and prepare audited release PRs. No direct writes to a cluster."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil

import yaml

from common import ROOT, SHA, catalog, compatible_revisions, now, read_json, run, sha, tree_sha, validate_manifest, verify_images, write_json
from render import bootstrap_resources, render_environment, secret_contract, validate_config, write_bundle

BUNDLE_FILES = ("resources.yaml", "state.json", "profile.json", "kustomization.yaml")


def inside(root, relative):
    root = Path(root).resolve()
    target = (root / relative).resolve()
    target.relative_to(root)
    return target


def candidate_path(gitops, release_id):
    if not re.fullmatch(r"[a-z0-9-]{1,90}", release_id):
        raise ValueError("Invalid release identifier")
    return inside(gitops, "candidates/" + release_id)


def load_state(gitops, environment):
    path = inside(gitops, "environments/" + environment + "/state.json")
    return read_json(path) if path.exists() else None


def state_id(state):
    return state["state_sha256"] if state else None


def copy_bundle(source, target, rollback=False):
    target = Path(target)
    target.mkdir(parents=True, exist_ok=True)
    if any(p.name not in BUNDLE_FILES for p in target.iterdir()):
        raise ValueError("Unexpected file in environment directory; refusing to overwrite it")
    for name in BUNDLE_FILES:
        shutil.copyfile(Path(source) / name, target / name)
    if rollback:
        path = target / "resources.yaml"
        resources = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        resources = [obj for obj in resources if not (obj.get("kind") == "Job" and obj["metadata"].get("labels", {}).get("delivery.ecommerce/role") == "migration")]
        path.write_text(yaml.safe_dump_all(resources, sort_keys=False), encoding="utf-8", newline="\n")


def check_candidate(path):
    metadata = read_json(Path(path) / "metadata.json")
    for environment in ("staging", "production"):
        if tree_sha(Path(path) / environment) != metadata[environment + "_bundle_sha256"]:
            raise ValueError("Frozen candidate bundle has changed")
    return metadata


def prepare_stage(config, manifest, gitops, release_id, configuration_revision, rollback_revisions):
    validate_config(config, activation=True)
    images = validate_manifest(manifest, config["source_repository"])
    if not SHA.fullmatch(configuration_revision):
        raise ValueError("Configuration must be bound to a Git commit")
    target = candidate_path(gitops, release_id)
    if target.exists():
        raise ValueError("Candidate already exists; frozen bundles are immutable")
    previous_production = state_id(load_state(gitops, "production"))
    target.mkdir(parents=True)
    for environment in ("staging", "production"):
        state, resources = render_environment(config, environment, images, release_id, manifest["revision"],
                                              configuration_revision, manifest["run_id"], rollback_revisions)
        write_bundle(target / environment, config["environments"][environment], state, resources)
    metadata = {"schema_version": 1, "release_id": release_id, "source_revision": manifest["revision"],
                "source_run_id": str(manifest["run_id"]), "configuration_revision": configuration_revision,
                "previous_production_state": previous_production,
                **{env + "_bundle_sha256": tree_sha(target / env) for env in ("staging", "production")}}
    write_json(target / "metadata.json", metadata)
    previous_staging = state_id(load_state(gitops, "staging"))
    copy_bundle(target / "staging", inside(gitops, "environments/staging"))
    write_json(inside(gitops, "operations/staging.json"), {"operation": "stage", "release_id": release_id,
               "expected_previous_state": previous_staging, "prepared_at": now()})
    return metadata


def prepare_promotion(config, gitops, proof, verification_run):
    validate_config(config, activation=True)
    if proof.get("environment") != "staging" or str(proof.get("verification_run_id")) != str(verification_run):
        raise ValueError("A successful staging workflow proof is required")
    age = (datetime.now(timezone.utc) - datetime.fromisoformat(proof["verified_at"])).total_seconds()
    if age < -60 or age > config["proof_max_age_hours"] * 3600:
        raise ValueError("Staging proof expired or has an invalid future timestamp")
    path = candidate_path(gitops, proof["release_id"])
    metadata = check_candidate(path)
    if any(read_json(path / env / "profile.json") != config["environments"][env] for env in ("staging", "production")):
        raise ValueError("Environment configuration changed after staging; create and verify a new candidate")
    staged = load_state(gitops, "staging")
    if (proof["bundle_sha256"] != metadata["staging_bundle_sha256"] or proof.get("production_bundle_sha256") != metadata["production_bundle_sha256"] or proof["state_sha256"] != state_id(staged) or
            proof["source_repository"] != config["source_repository"] or proof["source_revision"] != metadata["source_revision"]):
        raise ValueError("Staging moved or the tested candidate/configuration does not match")
    if state_id(load_state(gitops, "production")) != metadata["previous_production_state"]:
        raise ValueError("Production changed after candidate creation; stage a new candidate")
    copy_bundle(path / "production", inside(gitops, "environments/production"))
    write_json(inside(gitops, "operations/production.json"), {"operation": "promote", "release_id": proof["release_id"],
               "expected_previous_state": metadata["previous_production_state"], "verification_run_id": str(verification_run), "prepared_at": now()})
    return metadata


def prepare_rollback(config, gitops, proof, verification_run):
    validate_config(config, activation=True)
    current = load_state(gitops, "production")
    last_operation = inside(gitops, "operations/production.json")
    if last_operation.exists() and read_json(last_operation).get("operation") == "rollback":
        raise ValueError("A chained rollback requires a newly reviewed release and compatibility policy")
    if proof.get("environment") != "production" or str(proof.get("verification_run_id")) != str(verification_run) or not current:
        raise ValueError("Rollback requires proof from a previously healthy production deployment")
    path = candidate_path(gitops, proof["release_id"])
    metadata = check_candidate(path)
    target = read_json(path / "production/state.json")
    profile = read_json(path / "production/profile.json")
    if any(profile[key] != config["environments"]["production"][key] for key in ("namespace", "cluster_server", "domain")):
        raise ValueError("Rollback destination identity differs from the current production environment")
    if (proof.get("source_repository") != config["source_repository"] or proof.get("bundle_sha256") != metadata["production_bundle_sha256"] or proof["state_sha256"] != target["state_sha256"] or
            proof["source_revision"] != target["source_revision"]):
        raise ValueError("Rollback target differs from verified production state")
    if target["source_revision"] not in current["rollback_compatible_revisions"]:
        raise ValueError("Current release has not declared schema/workflow compatibility with this rollback target")
    copy_bundle(path / "production", inside(gitops, "environments/production"), rollback=True)
    write_json(inside(gitops, "operations/production.json"), {"operation": "rollback", "release_id": proof["release_id"],
               "expected_previous_state": current["state_sha256"], "verification_run_id": str(verification_run), "prepared_at": now()})
    return metadata


def github_run(repository, run_id, workflow, events, gitops=False):
    if not str(run_id).isdigit():
        raise ValueError("Invalid workflow run ID")
    env = gitops_environment() if gitops else None
    metadata = json.loads(run(["gh", "api", f"repos/{repository}/actions/runs/{run_id}"], env=env))
    if (metadata.get("conclusion") != "success" or metadata.get("status") != "completed" or metadata.get("head_branch") != "main" or
            metadata.get("event") not in events or metadata.get("path", "").split("@")[0] != workflow or
            metadata.get("repository", {}).get("full_name", "").lower() != repository.lower()):
        raise ValueError("Workflow run is not a successful trusted main-branch run")
    return metadata


def download(repository, run_id, name, directory, gitops=False):
    Path(directory).mkdir(parents=True, exist_ok=True)
    run(["gh", "run", "download", str(run_id), "--repo", repository, "--name", name, "--dir", str(directory)],
        env=gitops_environment() if gitops else None)


def gitops_environment():
    env = dict(os.environ)
    env["GH_TOKEN"] = os.environ["GITOPS_TOKEN"]
    return env


def open_pr(config, gitops, branch, title, body):
    env = gitops_environment()
    base = run(["git", "rev-parse", "HEAD"], cwd=gitops)
    run(["git", "config", "credential.helper", "!gh auth git-credential"], cwd=gitops)
    run(["git", "fetch", "origin", "main"], cwd=gitops, env=env)
    if run(["git", "rev-parse", "origin/main"], cwd=gitops) != base:
        raise ValueError("GitOps main changed while preparing the PR; rerun without overwriting newer state")
    run(["git", "switch", "-c", branch], cwd=gitops)
    run(["git", "config", "user.name", config["gitops_bot_login"]], cwd=gitops)
    run(["git", "config", "user.email", "delivery@users.noreply.github.com"], cwd=gitops)
    run(["git", "add", "candidates", "environments", "operations"], cwd=gitops)
    run(["git", "commit", "-m", title], cwd=gitops)
    run(["git", "push", "origin", "HEAD:" + branch], cwd=gitops, env=env)
    body_file = Path(gitops).parent / "delivery-pr-body.md"
    body_file.write_text(body, encoding="utf-8")
    return run(["gh", "pr", "create", "--repo", config["gitops_repository"], "--base", "main", "--head", branch,
                "--title", title, "--body-file", str(body_file.resolve())], cwd=gitops, env=env)


def bootstrap(config, output):
    validate_config(config)
    output = Path(output)
    if output.exists():
        raise ValueError("Bootstrap output must be a new directory")
    output.mkdir(parents=True)
    write_json(output / "config.json", config)
    (output / ".gitattributes").write_text("* text eol=lf\n", encoding="utf-8", newline="\n")
    write_json(output / "deploy/gitops/catalog.json", {"schema_version": 1, "components": catalog()})
    secret_contracts = {env: secret_contract(catalog(), env) for env in ("staging", "production")}
    from progressive import canary_enabled
    for env in secret_contracts:
        if canary_enabled(config["environments"][env]):
            secret_contracts[env]["operations-metrics"] = {"authorization": "authorization"}
    write_json(output / "secrets-contract.json", {env: {
        key: {"remote_key": config["environments"][env]["secret_prefix"] + "/" + key,
              "version": config["environments"][env]["secret_versions"][key], "properties": properties}
        for key, properties in secret_contracts[env].items()} for env in ("staging", "production")})
    for environment, resources in bootstrap_resources(config).items():
        platform = output / "bootstrap" / environment
        platform.mkdir(parents=True)
        (platform / "namespace.yaml").write_text(yaml.safe_dump(resources["namespace"], sort_keys=False), encoding="utf-8", newline="\n")
        (platform / "argocd.yaml").write_text(yaml.safe_dump_all(resources["argocd"], sort_keys=False), encoding="utf-8", newline="\n")
        target = output / "environments" / environment
        target.mkdir(parents=True)
        (target / "kustomization.yaml").write_text("apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: []\n", encoding="utf-8", newline="\n")
    for file in ("common.py", "render.py", "observe.py", "validate_gitops.py", "progressive.py", "slo.py"):
        target = output / "tools/cd" / file
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / "scripts/cd" / file, target)
    (output / "tools/requirements.txt").write_text("PyYAML==6.0.2\n", encoding="utf-8", newline="\n")
    workflows = output / ".github/workflows"
    workflows.mkdir(parents=True)
    for file in (ROOT / "deploy/gitops/templates").glob("*.yml"):
        shutil.copyfile(file, workflows / file.name)
    shutil.copyfile(ROOT / "deploy/gitops/argocd-health.patch.yaml", output / "bootstrap/argocd-health.patch.yaml")
    (output / "operations").mkdir()
    (output / "candidates").mkdir()
    (output / ".github/CODEOWNERS").write_text("* " + " ".join(config["production_owners"]) + "\n", encoding="utf-8", newline="\n")
    (output / "README.md").write_text("# E-Commerce GitOps\n\nBootstrap instructions: " +
        "https://github.com/" + config["source_repository"] + "/blob/main/docs/cicd/phase-2.md\n\n" +
        "Production changes require reviewed release PRs. Never deploy a candidate directory directly.\n", encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("bootstrap", "preflight", "stage", "promote", "rollback"))
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--gitops", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--output", type=Path, default=Path("ci-artifacts/delivery"))
    args = parser.parse_args()
    config = read_json(args.config)
    if args.operation == "bootstrap":
        bootstrap(config, args.output)
        print("Prepared bootstrap repository: " + str(args.output))
        return
    validate_config(config, activation=True)
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_REPOSITORY") != config["source_repository"]:
        raise ValueError("Delivery must run from the trusted source repository main branch")
    if args.operation == "preflight":
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            owner, name = config["gitops_repository"].split("/")
            output.write(f"repository={config['gitops_repository']}\nowner={owner}\nname={name}\n")
        return
    if not args.gitops or not args.run_id:
        raise ValueError("GitOps checkout and workflow run ID are required")
    if config != read_json(args.gitops / "config.json"):
        raise ValueError("Source and GitOps configuration differ; review and synchronize configuration first")
    args.output.mkdir(parents=True, exist_ok=True)
    if args.operation == "stage":
        ci = github_run(config["source_repository"], args.run_id, ".github/workflows/ci.yml", ("push", "workflow_dispatch"))
        download(config["source_repository"], args.run_id, "release-manifest", args.output)
        manifest = read_json(args.output / "release-manifest.json")
        if manifest["revision"] != ci["head_sha"] or str(manifest["run_id"]) != str(args.run_id):
            raise ValueError("Artifact does not belong to the successful CI run")
        images = validate_manifest(manifest, config["source_repository"])
        verify_images(images, config["source_repository"])
        previous = load_state(args.gitops, "staging")
        if previous:
            run(["git", "merge-base", "--is-ancestor", previous["source_revision"], manifest["revision"]], cwd=ROOT)
        release_id = manifest["revision"][:12] + "-" + str(args.run_id) + "-" + os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"]
        metadata = prepare_stage(config, manifest, args.gitops, release_id, run(["git", "rev-parse", "HEAD"], cwd=ROOT), compatible_revisions(manifest["revision"]))
        body = "Deploy this complete, verified image snapshot to staging. Production is frozen as a candidate only.\n\n" + json.dumps(metadata, indent=2)
        url = open_pr(config, args.gitops, "codex/stage-" + release_id, "Stage " + release_id, body)
        run(["gh", "pr", "merge", url, "--auto", "--squash"], env=gitops_environment())
        print(url)
        write_json(args.output / "candidate.json", metadata)
    else:
        is_rollback = args.operation == "rollback"
        repository = config["gitops_repository"] if is_rollback else config["source_repository"]
        workflow = ".github/workflows/production-health.yml" if is_rollback else ".github/workflows/cd-staging.yml"
        github_run(repository, args.run_id, workflow, ("push", "workflow_dispatch") if is_rollback else ("workflow_run", "workflow_dispatch"), gitops=is_rollback)
        download(repository, args.run_id, "production-proof" if is_rollback else "staging-proof", args.output, gitops=is_rollback)
        proof = read_json(args.output / ("production-proof.json" if is_rollback else "staging-proof.json"))
        path = candidate_path(args.gitops, proof["release_id"])
        verify_images(read_json(path / "production/state.json")["images"], config["source_repository"])
        metadata = (prepare_rollback if is_rollback else prepare_promotion)(config, args.gitops, proof, args.run_id)
        branch = "codex/" + args.operation + "-" + metadata["release_id"] + "-" + os.environ["GITHUB_RUN_ID"]
        print(open_pr(config, args.gitops, branch, args.operation.capitalize() + " " + metadata["release_id"],
            "Review the exact production bundle before merging.\n\n" + json.dumps({"operation": args.operation, "proof": proof, "candidate": metadata}, indent=2)))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        raise SystemExit(f"Delivery blocked: {type(error).__name__}: {error}")

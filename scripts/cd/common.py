"""Shared, strict data validation for delivery tooling. Never reads local .env files."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SHA = re.compile(r"[a-f0-9]{40}")
DIGEST = re.compile(r"sha256:[a-f0-9]{64}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8", newline="\n")


def sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def tree_sha(path):
    result = hashlib.sha256()
    for file in sorted(Path(path).rglob("*")):
        if file.is_symlink():
            raise ValueError("Symlinks are not permitted in deployment bundles")
        if file.is_file():
            result.update(file.relative_to(path).as_posix().encode() + b"\0" + file.read_bytes() + b"\0")
    return result.hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def run(args, cwd=None, env=None):
    # No shell interpolation and no raw command/output in exceptions (credentials may be present).
    result = subprocess.run(args, cwd=cwd, env=env, text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} failed with exit code {result.returncode}")
    return result.stdout.strip()


def catalog():
    return read_json(ROOT / "deploy/gitops/catalog.json")["components"]


def expected_images():
    return set(catalog()) | {"database-migrations", "deployment-checks"}


def validate_manifest(manifest, repository):
    if manifest.get("schema_version") != 1 or manifest.get("repository") != repository:
        raise ValueError("Release manifest schema or repository mismatch")
    revision = manifest.get("revision", "")
    if not SHA.fullmatch(revision) or not str(manifest.get("run_id", "")).isdigit():
        raise ValueError("Invalid source revision or CI run")
    records = manifest.get("images", [])
    names = [r.get("name") for r in records]
    if len(names) != len(set(names)) or not expected_images() <= set(names) or set(names) - expected_images() - {"minio"}:
        raise ValueError("CD requires a complete application/tools snapshot, without duplicate or unknown components")
    for record in records:
        image = f"ghcr.io/{repository.lower()}/{record['name']}"
        digest = record.get("digest", "")
        if (not DIGEST.fullmatch(digest) or digest == "sha256:" + "0" * 64 or
                record.get("image") != image or record.get("reference") != image + "@" + digest or
                record.get("revision") != revision or str(record.get("run_id")) != str(manifest["run_id"])):
            raise ValueError("Release contains an invalid/unbound image digest")
    return {r["name"]: r for r in records if r["name"] in expected_images()}


def verify_images(records, repository):
    for record in records.values():
        command = ["gh", "attestation", "verify", "oci://" + record["reference"], "--repo", repository,
                   "--signer-workflow", repository + "/.github/workflows/publish.yml",
                   "--source-digest", record["revision"], "--source-ref", "refs/heads/main", "--deny-self-hosted-runners"]
        run(command)
        run(command + ["--predicate-type", "https://cyclonedx.org/bom"])


def compatible_revisions(revision):
    content = run(["git", "show", revision + ":deploy/gitops/rollback-policy.json"], cwd=ROOT)
    policy = json.loads(content)
    revisions = policy.get("compatible_source_revisions", [])
    if policy.get("strategy") != "expand-contract" or not all(SHA.fullmatch(r) for r in revisions):
        raise ValueError("Invalid reviewed rollback compatibility policy")
    return sorted(set(revisions))

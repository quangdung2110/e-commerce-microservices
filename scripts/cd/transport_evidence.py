"""Validate reviewed runtime evidence before admitting a candidate to production."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import re
from urllib.parse import urlsplit

from common import SHA, now, read_json, sha, tree_sha, write_json

REQUIRED_CHECKS = {
    "ambient-workload-identity", "waypoint-authorization", "plaintext-denied",
    "direct-business-pod-denied", "direct-public-egress-denied", "unauthorized-egress-denied",
    "waypoint-failure-and-recovery", "credential-rotation", "product-grpc-replicas",
    "checkout-payment-refund", "temporal-task-queues", "ingress-mtls", "collector-otlp", "prometheus-scrape",
}


def expected_checks(profile):
    return REQUIRED_CHECKS | {
        f"{endpoint['name']}/{client}" for endpoint in profile["transport"]["endpoints"] for client in endpoint["clients"]
    }


def timestamp(value):
    try:
        result = datetime.fromisoformat(value)
    except (TypeError, ValueError):
        raise ValueError("Transport evidence requires an ISO 8601 timestamp") from None
    if result.tzinfo is None:
        raise ValueError("Transport evidence timestamp requires a timezone")
    return result


def validate_report(report, profile, state, metadata):
    expected = {"schema_version": 1, "environment": "staging", "release_id": state["release_id"],
                "source_revision": state["source_revision"], "state_sha256": state["state_sha256"],
                "platform_revision": profile["transport"]["platform_revision"], "transport_sha256": state["transport_sha256"],
                "staging_bundle_sha256": metadata["staging_bundle_sha256"],
                "production_bundle_sha256": metadata["production_bundle_sha256"]}
    if any(report.get(key) != value for key, value in expected.items()):
        raise ValueError("Transport evidence is bound to a different candidate")
    timestamp(report.get("verified_at"))
    if not SHA.fullmatch(report.get("gitops_revision") or "") or not str(report.get("verification_run_id", "")).isdigit():
        raise ValueError("Transport evidence requires the exact GitOps revision and staging workflow run")
    results = report.get("checks")
    if not isinstance(results, dict) or set(results) != expected_checks(profile):
        raise ValueError("Transport evidence must cover every required check and endpoint/client pair")
    for record in results.values():
        reference = urlsplit(record.get("evidence") or "") if isinstance(record, dict) else urlsplit("")
        if (not reference.hostname or reference.scheme != "https" or reference.username or reference.query
                or record.get("result") != "passed"):
            raise ValueError("Each passing transport check requires a non-secret HTTPS evidence reference")


def load_report(path, profile, state, metadata):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("Transport evidence must be a regular reviewed file")
    report = read_json(path)
    validate_report(report, profile, state, metadata)
    if path.stem != sha(report):
        raise ValueError("Transport evidence filename must match its content digest")
    return report


def reviewed_report(gitops, candidate, metadata, stage_proof, max_age):
    stage = Path(candidate) / "staging"
    profile, state = read_json(stage / "profile.json"), read_json(stage / "state.json")
    if (state.get("schema_version") != 2 or stage_proof.get("transport_sha256") != state["transport_sha256"]
            or stage_proof.get("platform_revision") != profile["transport"]["platform_revision"]):
        raise ValueError("Staging observation must verify the candidate transport policy and platform")
    recorded = Path(gitops) / "transport-evidence" / "staging" / state["release_id"]
    if any(parent.is_symlink() for parent in (recorded, recorded.parent, recorded.parent.parent)):
        raise ValueError("Transport evidence must be reviewed in the GitOps repository")
    start = timestamp(stage_proof["verified_at"])
    current = datetime.now(timezone.utc)
    valid = []
    for path in sorted(recorded.glob("*.json")):
        report = load_report(path, profile, state, metadata)
        verified = timestamp(report["verified_at"])
        if (report["gitops_revision"] == stage_proof.get("gitops_revision")
                and str(report["verification_run_id"]) == str(stage_proof.get("verification_run_id"))
                and verified >= start and -60 <= (current - verified).total_seconds() <= max_age * 3600):
            valid.append((verified, path.stem))
    if not valid:
        raise ValueError("Promotion requires current reviewed staging transport evidence for this candidate")
    return max(valid)[1]



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gitops", type=Path, required=True)
    parser.add_argument("--staging-proof", type=Path, required=True)
    parser.add_argument("--checks", type=Path, help="JSON object keyed by check ID with observed result and evidence")
    args = parser.parse_args()
    proof = read_json(args.staging_proof)
    release = proof["release_id"]
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}", release):
        raise ValueError("Invalid candidate release ID")
    candidate = args.gitops / "candidates" / release
    stage = candidate / "staging"
    metadata = read_json(candidate / "metadata.json")
    profile = read_json(stage / "profile.json")
    state = read_json(stage / "state.json")
    if (proof.get("environment") != "staging" or state.get("schema_version") != 2
            or proof.get("state_sha256") != state["state_sha256"] or proof.get("source_revision") != state["source_revision"]
            or proof.get("transport_sha256") != state["transport_sha256"]
            or proof.get("platform_revision") != profile["transport"]["platform_revision"]
            or proof.get("bundle_sha256") != tree_sha(stage)
            or proof.get("production_bundle_sha256") != tree_sha(candidate / "production")):
        raise ValueError("The staging observation does not match this transport candidate")
    if args.checks is None:
        print("\n".join(sorted(expected_checks(profile))))
        return
    if timestamp(proof["verified_at"]) > datetime.now(timezone.utc):
        raise ValueError("Staging observation is dated in the future")
    report = {key: proof[key] for key in ("environment", "source_revision", "state_sha256", "platform_revision",
                                         "transport_sha256", "production_bundle_sha256", "gitops_revision", "verification_run_id")}
    report.update(schema_version=1, release_id=release, staging_bundle_sha256=proof["bundle_sha256"],
                  verified_at=now(), checks=read_json(args.checks))
    validate_report(report, profile, state, metadata)
    path = args.gitops / "transport-evidence" / "staging" / release / (sha(report) + ".json")
    if any(parent.is_symlink() for parent in (path, path.parent, path.parent.parent, path.parent.parent.parent)):
        raise ValueError("Transport evidence must be written to the GitOps repository")
    write_json(path, report)
    print(path)


if __name__ == "__main__":
    main()

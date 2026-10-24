#!/usr/bin/env python3
"""Bind a successful Argo/PostSync staging observation to both immutable candidate bundles."""
import argparse
import base64
import json
from pathlib import Path
from urllib.parse import quote

from common import read_json, run, write_json
from delivery import candidate_path, check_candidate, gitops_environment
from observe import observe


def verify(config, gitops, metadata):
    candidate = candidate_path(gitops, metadata["release_id"])
    if check_candidate(candidate) != metadata:
        raise ValueError("Candidate metadata changed")
    proof = observe(config, "staging", candidate / "staging")
    ref = proof["gitops_revision"]
    # Read the desired state at the exact revision reported by Argo, not merely repository HEAD.
    response = json.loads(run(["gh", "api", f"repos/{config['gitops_repository']}/contents/environments/staging/state.json?ref={quote(ref)}"], env=gitops_environment()))
    applied = json.loads(base64.b64decode(response["content"]))
    if applied.get("state_sha256") != proof["state_sha256"]:
        raise ValueError("Argo CD is not running this candidate's GitOps revision")
    proof["production_bundle_sha256"] = metadata["production_bundle_sha256"]
    return proof


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--gitops", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    write_json(args.output, verify(read_json(args.config), args.gitops, read_json(args.candidate)))

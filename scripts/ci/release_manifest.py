#!/usr/bin/env python3
"""Record verified image digests; aggregate only a complete, single-run release."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys


def aggregate(records, expected, repository, revision, run_id, attempt):
    names = [record["name"] for record in records]
    if len(names) != len(set(names)) or set(names) != set(expected):
        raise ValueError("Missing, duplicate, or unexpected release component")
    prefix = "ghcr.io/" + repository.lower() + "/"
    for record in records:
        if (record["revision"] != revision or record["run_id"] != run_id or
                not 1 <= int(record["run_attempt"]) <= int(attempt) or record["image"] != prefix + record["name"] or
                not re.fullmatch(r"sha256:[a-f0-9]{64}", record["digest"]) or
                record["reference"] != record["image"] + "@" + record["digest"]):
            raise ValueError("Release record does not match the current run or immutable image")
    return {"schema_version": 1, "scope": "selected-components", "repository": repository,
            "revision": revision, "run_id": run_id, "run_attempt": attempt,
            "images": sorted(records, key=lambda r: r["name"])}


if __name__ == "__main__":
    output = Path("ci-artifacts")
    repository, revision = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"]
    run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
    if sys.argv[1] == "record":
        name, image, digest = (os.environ[k] for k in ("COMPONENT", "IMAGE_NAME", "IMAGE_DIGEST"))
        result = {"name": name, "image": image, "tag": os.environ["IMAGE_TAG"], "digest": digest,
                  "reference": image + "@" + digest, "revision": revision,
                  "run_id": run_id, "run_attempt": attempt,
                  "sbom_sha256": hashlib.sha256((output / "sbom.cdx.json").read_bytes()).hexdigest(),
                  "scan_sha256": hashlib.sha256((output / "image-scan.json").read_bytes()).hexdigest()}
        (output / ("release-" + name + ".json")).write_text(json.dumps(result, indent=2) + "\n")
    elif sys.argv[1] == "aggregate":
        records = [json.loads(p.read_text()) for p in (output / "releases").glob("*/release-*.json")]
        expected = [item["name"] for item in json.loads(os.environ["IMAGE_MATRIX"])["include"]]
        result = aggregate(records, expected, repository, revision, run_id, attempt)
        (output / "release-manifest.json").write_text(json.dumps(result, indent=2) + "\n")
    else:
        raise SystemExit("Usage: release_manifest.py record|aggregate")

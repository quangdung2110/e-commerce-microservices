#!/usr/bin/env python3
"""Download/check/pin the Rollouts controller for platform review. Never applies resources."""
import argparse
import hashlib
from pathlib import Path
import sys
import urllib.request

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cd"))
from common import read_json, write_json


def export(lock, output):
    if output.exists():
        raise ValueError("Controller bootstrap output must be a new directory")
    pin = lock["argo_rollouts"]
    expected = "https://github.com/argoproj/argo-rollouts/releases/download/" + pin["version"] + "/install.yaml"
    if lock.get("schema_version") != 1 or pin["manifest_url"] != expected or "@sha256:" not in pin["image"]:
        raise ValueError("Invalid controller lock")
    with urllib.request.urlopen(pin["manifest_url"], timeout=60) as response:
        content = response.read()
    if hashlib.sha256(content).hexdigest() != pin["manifest_sha256"]:
        raise ValueError("Controller manifest checksum mismatch")
    resources = list(yaml.safe_load_all(content))
    replacements = 0
    for obj in resources:
        if not obj or obj.get("kind") != "Deployment":
            continue
        for container in obj["spec"]["template"]["spec"]["containers"]:
            if container["image"].split(":")[0] == "quay.io/argoproj/argo-rollouts":
                container["image"] = pin["image"]
                replacements += 1
    if replacements != 1:
        raise ValueError("Unexpected upstream controller image inventory")
    output.mkdir(parents=True)
    (output / "install.yaml").write_text(yaml.safe_dump_all(resources, sort_keys=False), encoding="utf-8", newline="\n")
    write_json(output / "controllers.lock.json", lock)
    (output / "REVIEW-REQUIRED.txt").write_text("Platform administrator: review RBAC, namespace, controller security, CNI and image scan before applying. This tool did not connect to a cluster.\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path("deploy/operations/controllers.lock.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(read_json(args.lock), args.output)
    print("Prepared pinned controller manifests for platform review")

#!/usr/bin/env python3
"""Read-only post-deploy health and strict provider contract checks; no account tokens."""
import argparse
import json
from pathlib import Path
import sys
import time
import urllib.request

HERE = Path(__file__).resolve()
ROOT = next(path for path in (HERE.parent, *HERE.parents) if (path / "contracts/tools/verify_providers.py").is_file())
sys.path.insert(0, str(ROOT / "contracts/tools"))
from verify_providers import run as verify_providers  # noqa: E402


def health_check(targets, timeout=90):
    deadline = time.monotonic() + timeout
    pending = {target["name"]: target["url"] for target in targets}
    while pending and time.monotonic() < deadline:
        for name, url in list(pending.items()):
            try:
                with urllib.request.urlopen(url, timeout=3) as response:
                    if response.status == 200:
                        pending.pop(name)
            except OSError:
                pass
        if pending:
            time.sleep(2)
    if pending:
        raise RuntimeError("Health checks failed: " + ", ".join(sorted(pending)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--probes", required=True, type=Path)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    health_check(config["health"])
    if verify_providers(args.probes, set(), True, 5, strict=True, redact_bodies=True):
        raise RuntimeError("Provider checks failed; deployment must not be promoted")
    print(json.dumps({"phase": "smoke", "result": "passed", "environment": config["environment"],
                      "state_sha256": config["state_sha256"]}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Deployment smoke failed: {type(error).__name__}", file=sys.stderr)
        raise SystemExit(1)

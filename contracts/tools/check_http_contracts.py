#!/usr/bin/env python3
"""Semantic checks over contracts/http that the PowerShell smoke validator does not do.

Checks (each maps to a contracts ADR):

* ADR-005  every ``429`` and ``503`` response declares a ``Retry-After`` header.

Usage: python contracts/tools/check_http_contracts.py [--root contracts/http]
Exit code 1 when any check fails. Requires PyYAML.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

METHODS = ("get", "post", "put", "patch", "delete", "head", "options")
RETRY_AFTER_CODES = ("429", "503")


def _resolve(response, components):
    """Follow ``$ref`` chains to a components/responses entry."""
    seen = 0
    while isinstance(response, dict) and "$ref" in response and seen < 8:
        name = response["$ref"].rsplit("/", 1)[-1]
        response = components.get(name, {})
        seen += 1
    return response if isinstance(response, dict) else {}


def retry_after_violations(doc: dict) -> list[str]:
    components = (doc.get("components") or {}).get("responses") or {}
    problems = []
    for path, item in (doc.get("paths") or {}).items():
        for method in METHODS:
            operation = item.get(method)
            if not operation:
                continue
            for code, response in (operation.get("responses") or {}).items():
                if str(code) not in RETRY_AFTER_CODES:
                    continue
                headers = _resolve(response, components).get("headers") or {}
                if not any(str(name).lower() == "retry-after" for name in headers):
                    problems.append(f"{method.upper()} {path} {code} has no Retry-After header (ADR-005)")
    return problems


def check_file(path: Path) -> list[str]:
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    return retry_after_violations(doc)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1] / "http"))
    args = parser.parse_args(argv)

    failures = 0
    specs = sorted(Path(args.root).rglob("openapi.yaml"))
    if not specs:
        print(f"no openapi.yaml found under {args.root}", file=sys.stderr)
        return 1
    for spec in specs:
        for problem in check_file(spec):
            failures += 1
            print(f"{spec.relative_to(args.root).as_posix()}: {problem}", file=sys.stderr)
    print(f"checked {len(specs)} OpenAPI files, {failures} violation(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

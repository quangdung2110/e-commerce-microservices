#!/usr/bin/env python3
"""Calls the real providers the way their consumers do and checks every answer against the OpenAPI contract
(contracts ADR-009).

Consumers test against fixtures, so a provider that drifts from its contract is only noticed in an end-to-end run.
This tool closes that gap on a running stack: for each probe in ``provider-probes.yaml`` it sends the request as the
consumer service (``X-Service-Identity``) and verifies

* the status is declared for the operation,
* the response media type is declared for that status,
* the body validates against the declared schema (JSON Schema 2020-12, ``$ref`` resolved across files),
* every header the contract marks ``required`` is present.

Usage (inside the compose network so service names resolve; see scripts/ci/verify-providers.sh):

    python contracts/tools/verify_providers.py [--probes FILE] [--only ID ...] [--format-check]

Exit code 1 when any probe fails. Requires PyYAML and jsonschema >= 4.18.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import url2pathname

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

CONTRACTS = Path(__file__).resolve().parents[1]
HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options")


# --------------------------------------------------------------------------- contract model


def _load(path: Path) -> Any:
    text = path.read_text(encoding="utf-8")
    return json.loads(text) if path.suffix == ".json" else yaml.safe_load(text)


def _file_registry() -> Registry:
    """A registry that loads ``file:`` references (shared JSON Schemas, other YAML files) from disk."""

    def retrieve(uri: str) -> Resource:
        if not uri.startswith("file:"):
            raise LookupError(f"cannot load {uri}")
        path = Path(url2pathname(urlparse(uri).path))
        return Resource.from_contents(_load(path), default_specification=DRAFT202012)

    return Registry(retrieve=retrieve)


def _pointer(*parts: str) -> str:
    return "/" + "/".join(str(p).replace("~", "~0").replace("/", "~1") for p in parts)


def _resolve_pointer(doc: Any, pointer: str) -> Any:
    node = doc
    for raw in pointer.lstrip("#/").split("/"):
        if raw == "":
            continue
        key = raw.replace("~1", "/").replace("~0", "~")
        node = node[int(key)] if isinstance(node, list) else node[key]
    return node


@dataclass
class Verdict:
    problems: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems


class Contract:
    """One OpenAPI document and the lookups a response check needs."""

    def __init__(self, path: Path, format_check: bool = False):
        self.path = path.resolve()
        self.uri = self.path.as_uri()
        self.doc = _load(self.path)
        self.registry = _file_registry().with_resource(
            self.uri, Resource.from_contents(self.doc, default_specification=DRAFT202012)
        )
        self.format_checker = FormatChecker() if format_check else None
        self._templates = [(self._regex(template), template) for template in (self.doc.get("paths") or {})]

    @staticmethod
    def _regex(template: str) -> re.Pattern[str]:
        parts = re.split(r"(\{[^}]+\})", template)
        return re.compile("^" + "".join("[^/]+" if p.startswith("{") else re.escape(p) for p in parts) + "$")

    def operation(self, method: str, path: str) -> tuple[str, dict] | None:
        # A literal path beats a templated one (/exports/changes vs /exports/{id}).
        literal = [t for _, t in self._templates if "{" not in t and t == path]
        candidates = literal or [t for regex, t in self._templates if regex.match(path)]
        for template in candidates:
            operation = self.doc["paths"][template].get(method.lower())
            if operation:
                return template, operation
        return None

    def _follow(self, node: Any, pointer: str) -> tuple[Any, str]:
        """Resolve a ``$ref`` that points into the same document; returns the target and its pointer."""
        seen = 0
        while isinstance(node, dict) and "$ref" in node and node["$ref"].startswith("#/") and seen < 10:
            pointer = node["$ref"][1:]
            node = _resolve_pointer(self.doc, pointer)
            seen += 1
        return node, pointer

    def response(self, template: str, method: str, status: int) -> tuple[dict, str] | None:
        responses = self.doc["paths"][template][method.lower()].get("responses") or {}
        for key in (str(status), status, f"{status // 100}XX", "default"):
            if key in responses:
                base = _pointer("paths", template, method.lower(), "responses", str(key))
                target, pointer = self._follow(responses[key], base)
                return target, pointer
        return None

    def validate(self, template: str, method: str, status: int, content_type: str, headers: dict[str, str],
                 body: bytes) -> Verdict:
        verdict = Verdict()
        found = self.response(template, method, status)
        if found is None:
            declared = ", ".join(str(s) for s in (self.doc["paths"][template][method.lower()].get("responses") or {}))
            verdict.problems.append(f"status {status} is not declared (declared: {declared})")
            return verdict
        response, pointer = found

        for name, header in (response.get("headers") or {}).items():
            header, _ = self._follow(header, "")
            if isinstance(header, dict) and header.get("required") and name.lower() not in headers:
                verdict.problems.append(f"required response header {name} is missing")

        content = response.get("content") or {}
        if not content:
            if body.strip():
                verdict.problems.append("the contract declares no body for this status but one was returned")
            return verdict

        media = content_type.split(";")[0].strip().lower()
        entry = next((v for k, v in content.items() if k.lower() == media), None)
        entry_key = next((k for k in content if k.lower() == media), None)
        if entry is None:
            verdict.problems.append(f"media type '{media or 'none'}' is not declared (declared: {', '.join(content)})")
            return verdict
        schema = entry.get("schema")
        if schema is None or not media.endswith("json"):
            return verdict
        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            verdict.problems.append(f"body is not valid JSON: {error}")
            return verdict

        location = f"{self.uri}#{pointer}/content/{entry_key.replace('~', '~0').replace('/', '~1')}/schema"
        validator = Draft202012Validator({"$ref": location}, registry=self.registry, format_checker=self.format_checker)
        for error in sorted(validator.iter_errors(payload), key=lambda e: list(e.absolute_path))[:8]:
            where = "/".join(str(p) for p in error.absolute_path) or "(root)"
            verdict.problems.append(f"body {where}: {error.message[:200]}")
        return verdict


# --------------------------------------------------------------------------- probes


def _substitute(value: Any, variables: dict[str, str]) -> Any:
    if isinstance(value, str):
        return re.sub(r"\$\{(\w+)\}", lambda m: variables[m.group(1)] if m.group(1) in variables else m.group(0), value)
    if isinstance(value, list):
        return [_substitute(v, variables) for v in value]
    if isinstance(value, dict):
        return {k: _substitute(v, variables) for k, v in value.items()}
    return value


def _dig(payload: Any, path: str) -> Any:
    """Tiny path language for captures: ``items[0].product_id``."""
    node = payload
    for token in re.findall(r"[^.\[\]]+|\[\d+\]", path):
        if token.startswith("["):
            node = node[int(token[1:-1])]
        else:
            node = node[token]
    return node


def _send(method: str, url: str, headers: dict[str, str], body: Any, timeout: float) -> tuple[int, dict[str, str], bytes]:
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers = {**headers, "Content-Type": "application/json"}
    request = urllib.request.Request(url, data=data, method=method.upper(), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, {k.lower(): v for k, v in response.headers.items()}, response.read()
    except urllib.error.HTTPError as error:
        return error.code, {k.lower(): v for k, v in error.headers.items()}, error.read()


def run(probes_path: Path, only: set[str], format_check: bool, timeout: float,
        strict: bool = False, redact_bodies: bool = False) -> int:
    config = yaml.safe_load(probes_path.read_text(encoding="utf-8"))
    interactions = {i["id"]: i for i in yaml.safe_load((CONTRACTS / "catalog" / "interactions.yaml").read_text(encoding="utf-8"))["interactions"]}
    targets: dict[str, str] = config["targets"]
    run_id = uuid.uuid4().hex[:8]
    variables = {"uuid": str(uuid.uuid4()), "uuid2": str(uuid.uuid4()), "run": run_id}
    contracts: dict[str, Contract] = {}

    passed = failed = skipped = 0
    for probe in config["probes"]:
        # With --only, probes that capture a value still run (silently) so later probes can use it.
        quiet = bool(only) and probe["id"] not in only
        if quiet and not probe.get("capture"):
            continue
        interaction = interactions.get(probe["interaction"])
        if interaction is None:
            print(f"FAIL {probe['id']}: unknown interaction {probe['interaction']!r}")
            failed += 1
            continue
        provider = interaction["provider"]
        contract_path = CONTRACTS / probe.get("contract", interaction["contract"])
        contract = contracts.setdefault(str(contract_path), Contract(contract_path, format_check))

        request = _substitute(probe["request"], variables)
        if "${" in json.dumps(request):
            print(f"SKIP {probe['id']}: needs a value no earlier probe captured")
            skipped += 1
            continue
        method, path = request["method"], request["path"]
        headers = {
            **({"X-Service-Identity": probe["as"]} if probe.get("as") else {}),
            "X-Request-Id": f"verify-{run_id}-{probe['id']}"[:128],
            "X-Correlation-Id": f"verify-{run_id}-{probe['id']}"[:128],
            "Accept": "application/json, application/problem+json",
            **{k: str(v) for k, v in (request.get("headers") or {}).items()},
        }
        url = targets[provider].rstrip("/") + path
        try:
            status, response_headers, body = _send(method, url, headers, request.get("body"), timeout)
        except Exception as error:  # noqa: BLE001 - any transport failure is a failed probe, not a crash
            print(f"FAIL {probe['id']}: {method} {url}: {error}")
            failed += 1
            continue

        operation = contract.operation(method, path.split("?")[0])
        verdict = Verdict()
        if operation is None:
            verdict.problems.append(f"{method} {path} matches no operation in {contract_path.name}")
        else:
            verdict = contract.validate(operation[0], method, status, response_headers.get("content-type", ""),
                                        response_headers, body)
        expected = probe.get("expect")
        if expected and status not in expected:
            verdict.problems.append(f"status {status} is not one of the expected {expected}")

        if quiet:
            if strict and not verdict.ok:
                failed += 1
                print(f"FAIL prerequisite {probe['id']}: response did not satisfy its contract")
        elif verdict.ok:
            passed += 1
            print(f"ok   {probe['id']}  {method} {path.split('?')[0]} -> {status}")
        else:
            failed += 1
            print(f"FAIL {probe['id']}  {method} {path.split('?')[0]} -> {status}")
            for problem in verdict.problems:
                print("       - response violates its contract (details redacted)" if redact_bodies else f"       - {problem}")
            if not redact_bodies:
                snippet = body[:700].decode("utf-8", "replace").replace("\n", " ")
                print(f"       body: {snippet}")
        if status < 300 and probe.get("capture"):
            try:
                payload = json.loads(body.decode("utf-8"))
                for name, expression in probe["capture"].items():
                    variables[name] = str(_dig(payload, expression))
            except Exception as error:  # noqa: BLE001
                if strict:
                    failed += 1
                print("       capture failed" if redact_bodies else f"       (capture failed: {error})")

    print(f"\n{passed} passed, {failed} failed, {skipped} skipped")
    return 1 if failed or (strict and (skipped or not passed)) else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--probes", default=str(Path(__file__).with_name("provider-probes.yaml")))
    parser.add_argument("--only", nargs="*", default=[], help="run only these probe ids")
    parser.add_argument("--format-check", action="store_true", help="also enforce JSON Schema 'format' (uuid, date-time...)")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--strict", action="store_true", help="fail on skipped probes, failed prerequisites or captures")
    parser.add_argument("--redact-bodies", action="store_true", help="do not print response bodies")
    args = parser.parse_args(argv)
    return run(Path(args.probes), set(args.only), args.format_check, args.timeout, args.strict, args.redact_bodies)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

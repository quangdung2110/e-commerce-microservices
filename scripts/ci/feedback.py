#!/usr/bin/env python3
"""Write the failing checks and local reproduction commands to the current job summary."""
import argparse
import html
import json
import os
from pathlib import Path
import shlex
from xml.etree.ElementTree import ParseError

from test_reports import summarize_go, summarize_java


CHECKS = {
    "java": {
        "docker": ("Docker", "docker info"),
        "minio": ("MinIO fixture", "docker build -t ecommerce-minio:RELEASE.2025-10-15T17-29-55Z infra/minio"),
        "minio_build": ("MinIO fixture build", "docker build -t ecommerce-minio:RELEASE.2025-10-15T17-29-55Z infra/minio"),
        "maven": ("Maven Verify", "mvn -B -ntp verify"),
        "java_report": ("Maven test evidence", "mvn -B -ntp verify"),
    },
    "go": {
        "docker": ("Docker", "docker info"),
        "vet": ("Go vet", "go vet ./..."),
        "format": ("Go formatting", "gofmt -l ."),
        "unit": ("Go race tests", "go test -race -count=1 ./..."),
        "go_report": ("Go test evidence", "go test -race -count=1 -json ./..."),
        "integration": ("Tagged Go integration", "go test -race -count=1 -tags=integration -timeout 20m ./test/integration/... ./internal/..."),
        "integration_report": ("Integration test evidence", "go test -race -count=1 -tags=integration -json ./test/integration/... ./internal/..."),
        "redis": ("Cart Lua against Redis", "CART_TEST_REDIS_ADDR=127.0.0.1:16379 go test -race -count=1 ./internal/infrastructure/cartredis/..."),
    },
    "frontend": {
        "install": ("Install", "npm ci"),
        "generated": ("Generated API types", "npm run gen:api:check"),
        "lint": ("Lint", "npm run lint"),
        "types": ("TypeScript", "npm run typecheck"),
        "tests": ("Frontend tests", "npm test"),
        "build": ("Production build", "npm run build"),
    },
    "contracts": {
        "lint": ("Contract linters", "pwsh -File contracts/tools/validate-contracts.ps1 -StrictTools"),
        "http": ("HTTP contract rules", "python contracts/tools/check_http_contracts.py"),
        "breaking": ("Breaking changes", "bash contracts/tools/check-breaking.sh origin/main"),
        "self_test": ("Breaking gate self-test", "bash contracts/tools/tests/test_check_breaking.sh origin/main"),
    },
    "source": {"scan": ("Source scan", "bash scripts/ci/scan.sh source")},
    "image": {
        "build": ("Image build", ""),
        "migration": ("Disposable PostgreSQL migrations", "python scripts/cd/test_migrations.py --image ci/repro"),
        "sbom": ("Generate SBOM", "bash scripts/ci/scan.sh sbom"),
        "scan": ("Image scan", "bash scripts/ci/scan.sh image"),
        "package": ("Package the scanned archive", "sha256sum ci-artifacts/image.tar"),
    },
}


def code(value):
    return "<code>" + html.escape(value).replace("\r", " ").replace("\n", " ") + "</code>"


def evidence(kind, directory):
    if kind in ("java", "go"):
        root = Path(directory)
        try:
            tests = (summarize_java(root) if kind == "java" else
                     [test for report in sorted(root.glob("*-tests.jsonl")) for test in summarize_go(report)])
        except (OSError, ValueError, KeyError, ParseError):
            return []
        return sorted({test["name"] for test in tests if test["failed"]})
    if kind in ("source", "image"):
        reports = Path("ci-artifacts").glob("source-*.json" if kind == "source" else "image-scan.json")
        findings = set()
        for report in reports:
            try:
                results = json.loads(report.read_text(encoding="utf-8")).get("Results", [])
                for result in results:
                    for finding in result.get("Vulnerabilities", []):
                        findings.add(f"{finding['VulnerabilityID']} · {finding['PkgName']} · {finding['Severity']}")
                    if result.get("Secrets"):
                        findings.add("Embedded secret finding. Check restricted scanner output.")
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                continue
        return sorted(findings)
    return []


def summary(kind, component, directory, dockerfile, artifact, steps):
    text = [f"### Failed checks · {code(component)}", ""]
    failed = [key for key, value in steps.items() if value.get("outcome") == "failure"]
    if kind == "image" and failed:
        build = shlex.join(["docker", "build", "--platform", "linux/amd64", "-t", "ci/repro", "-f", dockerfile, directory])
        text.append(f"- Build the image from the checkout · {code(build)}")
        if any(key in failed for key in ("sbom", "scan", "package")):
            text.append(f"- Export the archive · {code('mkdir -p ci-artifacts && docker save ci/repro -o ci-artifacts/image.tar')}")
    for key in failed:
        label, command = CHECKS[kind].get(key, (key, ""))
        if command and directory and kind in ("java", "go", "frontend") and key not in ("docker", "minio", "minio_build"):
            command = f"cd {shlex.quote(directory)} && {command}"
        text.append(f"- {code(label)}" + (f" · {code(command)}" if command else ""))
    if not failed:
        text.append("- A job-setup or untracked step failed. Check the first error in the job log.")
    details = evidence(kind, directory)
    if details:
        text += ["", "Failed tests or findings (up to 10)", "", *[f"- {code(detail)}" for detail in details[:10]]]
    if artifact:
        text.append(f"- See artifact {code(artifact)} for evidence generated before the failure.")
    return "\n".join(text) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=CHECKS)
    parser.add_argument("--component", required=True)
    parser.add_argument("--directory", default="")
    parser.add_argument("--dockerfile", default="")
    parser.add_argument("--artifact", default="")
    args = parser.parse_args()
    report = summary(args.kind, args.component, args.directory, args.dockerfile, args.artifact,
                     json.loads(os.environ.get("CI_STEPS", "{}")))
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
        output.write(report)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Validate Maven/Go reports and retain an explicit list of skipped tests."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize_java(root):
    cases = []
    for path in sorted(root.glob("target/*-reports/TEST-*.xml")):
        for case in ET.parse(path).getroot().iter("testcase"):
            cases.append({"name": case.get("classname", "") + "." + case.get("name", ""),
                          "skipped": case.find("skipped") is not None,
                          "failed": case.find("failure") is not None or case.find("error") is not None})
    return cases


def summarize_go(path):
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        event = json.loads(line)
        if event.get("Test") and event.get("Action") in ("pass", "fail", "skip"):
            cases.append({"name": event["Package"] + "." + event["Test"],
                          "skipped": event["Action"] == "skip", "failed": event["Action"] == "fail"})
    return cases


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("java", "go"))
    parser.add_argument("path", type=Path)
    parser.add_argument("--integration", action="store_true")
    args = parser.parse_args()
    cases = summarize_java(args.path) if args.kind == "java" else summarize_go(args.path)
    skipped = [c["name"] for c in cases if c["skipped"]]
    print(json.dumps({"tests": len(cases), "executed": len(cases) - len(skipped), "skipped": skipped}, indent=2))
    if not cases or len(skipped) == len(cases) or any(c["failed"] for c in cases):
        raise SystemExit("Missing, entirely skipped, or failed test suite")
    if args.integration and not any("integration" in c["name"].lower() and not c["skipped"] for c in cases):
        raise SystemExit("No integration test executed")

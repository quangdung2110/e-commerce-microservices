#!/usr/bin/env python3
"""Fail if a required job failed, was cancelled, or was unexpectedly skipped."""
import json
import os


def check(plan, needs):
    expected = {"plan": True, "tooling": True, "security": True,
                "services": bool(plan["services"]["include"]),
                "images": bool(plan["images"]["include"]),
                **{key: plan[key] for key in ("frontend", "contracts", "mesh", "kubernetes")}}
    errors = []
    for name, required in expected.items():
        status = needs.get(name, {}).get("result", "missing")
        allowed = ("success",) if required else ("success", "skipped")
        if status not in allowed:
            errors.append(f"{name}: {status} (expected {' or '.join(allowed)})")
    return errors


if __name__ == "__main__":
    plan = json.loads(os.environ["CI_PLAN"])
    needs = json.loads(os.environ["CI_NEEDS"])
    errors = check(plan, needs)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as output:
            print(f"### Required CI checks · {'failed' if errors else 'passed'}", file=output)
            print("\n| Check | Result |\n| --- | --- |", file=output)
            for name, job in sorted(needs.items()):
                print(f"| {name} | {job.get('result', 'missing')} |", file=output)
            if errors:
                print("\nOpen the failing job summary for the step, reproduction command and evidence.", file=output)
    if errors:
        raise SystemExit("Required CI checks did not pass:\n" + "\n".join(errors))
    print("All required CI checks passed.")

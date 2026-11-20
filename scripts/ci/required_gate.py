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
    errors = check(json.loads(os.environ["CI_PLAN"]), json.loads(os.environ["CI_NEEDS"]))
    if errors:
        raise SystemExit("Required CI checks did not pass:\n" + "\n".join(errors))
    print("All required CI checks passed.")

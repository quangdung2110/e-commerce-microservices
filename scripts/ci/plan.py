#!/usr/bin/env python3
"""Plan tests and image builds from committed Git changes; standard library only."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def inventory(root):
    services, images = [], []
    for kind, pattern in (("java", "java/services/*/pom.xml"), ("go", "go/services/*/go.mod"),
                          ("go", "go/*/go.mod")):
        for source in sorted(root.glob(pattern)):
            directory = source.parent.relative_to(root).as_posix()
            services.append({"name": source.parent.name, "kind": kind, "dir": directory})
            if (source.parent / "Dockerfile").is_file():
                images.append({"name": source.parent.name, "dir": directory,
                               "context": "go" if kind == "go" else directory,
                               "dockerfile": directory + "/Dockerfile"})
    for name, directory in (("frontend", "frontend"), ("api-gateway", "go/services/api-gateway"),
                            ("minio", "infra/minio")):
        if (root / directory / "Dockerfile").is_file():
            images.append({"name": name, "dir": directory, "context": directory,
                           "dockerfile": directory + "/Dockerfile"})
    for name, directory in (("database-migrations", "deploy/migrations"), ("deployment-checks", "deploy/checks")):
        if (root / directory / "Dockerfile").is_file():
            images.append({"name": name, "dir": directory, "context": ".", "dockerfile": directory + "/Dockerfile"})
    return services, images


def plan(root, paths, force=False):
    services, images = inventory(root)
    global_change = force or any(p.startswith((".github/", "scripts/ci/", "deploy/gitops/", "deploy/operations/")) or
                                 p in (".gitattributes", ".gitignore", "docker-compose.yml", "init-db.sh")
                                 for p in paths)
    contracts = global_change or any(p.startswith("contracts/") for p in paths)
    go_shared = any(p.startswith("go/observability/") or p in ("go/.dockerignore", "go/go.work", "go/go.work.sum")
                    for p in paths)
    infra = any(p.startswith("infra/") for p in paths)
    frontend = contracts or any(p.startswith("frontend/") for p in paths)
    mesh = contracts or any(p.startswith(("deploy/mesh/", "docs/mesh/")) for p in paths)
    kubernetes = mesh or infra or any(p.startswith("deploy/k8s/") for p in paths)

    def affected(component):
        if component["name"] == "database-migrations" and any(
                (p.startswith("java/services/") and "/db/migration/" in p) or
                (p.startswith("go/services/") and "/migrations/" in p) for p in paths):
            return True
        if component["name"] == "deployment-checks" and any(p.startswith("scripts/cd/") for p in paths):
            return True
        return contracts or infra or (go_shared and component["dir"].startswith("go/")) or any(
            p.startswith(component["dir"] + "/") for p in paths)

    return {
        "services": {"include": [s for s in services if affected(s)]},
        "images": {"include": [i for i in images if affected(i)]},
        "frontend": frontend, "contracts": contracts, "mesh": mesh, "kubernetes": kubernetes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main", help="commit/ref, or ALL")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--direct", action="store_true", help="two-dot comparison for push events")
    parser.add_argument("--all", action="store_true", help="full snapshot while preserving the real diff base")
    parser.add_argument("--section", choices=("services", "images"))
    parser.add_argument("--github-output", action="store_true")
    args = parser.parse_args()
    root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())
    force = args.base == "ALL" or set(args.base) == {"0"}
    head = git(root, "rev-parse", "--verify", args.head + "^{commit}").decode().strip()
    if force:
        base = git(root, "rev-parse", "--verify", head + "^1").decode().strip()
        paths = []
    else:
        base = git(root, "rev-parse", "--verify", args.base + "^{commit}").decode().strip()
        separator = ".." if args.direct else "..."
        paths = git(root, "diff", "--name-only", "--no-renames", "-z", base + separator + head).decode().split("\0")
    result = plan(root, paths, force or args.all)
    result["base"] = base
    if args.github_output:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in result.items():
                rendered = value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))
                output.write(f"{key}={rendered}\n")
            for key in ("services", "images"):
                output.write(f"{key}_any={str(bool(result[key]['include'])).lower()}\n")
            output.write("plan=" + json.dumps(result, separators=(",", ":")) + "\n")
    print(json.dumps(result[args.section] if args.section else result, separators=(",", ":")))


if __name__ == "__main__":
    main()

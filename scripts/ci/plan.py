#!/usr/bin/env python3
"""Plan tests and image builds from committed Git changes; standard library only."""
import argparse
import html
import json
import os
from pathlib import Path
import subprocess


ORDER_OPENAPI = "contracts/http/order/v1/openapi.yaml"
ORDER_HTTP_COMPONENTS = {"order-service", "api-gateway", "frontend", "deployment-checks"}
GLOBAL_PREFIXES = (".github/", "scripts/ci/", "deploy/gitops/", "deploy/operations/")
GLOBAL_FILES = {".gitattributes", ".gitignore", "docker-compose.yml", "init-db.sh"}
SHARED_GO_FILES = {"go/.dockerignore", "go/go.work", "go/go.work.sum"}
NON_RELEASE_DIRECTORIES = ("docs/", "note/")
ROOT_DOCUMENTS = {"README.md", "LICENSE"}


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


def plan(root, paths, force=False, release_snapshot=False):
    paths = sorted(set(filter(None, paths)))
    services, images = inventory(root)
    component_dirs = tuple(component["dir"] + "/" for component in services + images)
    docs_only = bool(paths) and all(p in ROOT_DOCUMENTS or p.startswith(NON_RELEASE_DIRECTORIES) for p in paths)
    full_triggers = [p for p in paths if p.startswith(GLOBAL_PREFIXES) or p in GLOBAL_FILES]
    known_prefixes = component_dirs + NON_RELEASE_DIRECTORIES + (
        "contracts/", "deploy/mesh/", "deploy/k8s/", "infra/", "loadtest/", "scripts/cd/", "scripts/ops/",
    )
    unknown_paths = [p for p in paths if p not in ROOT_DOCUMENTS | GLOBAL_FILES | SHARED_GO_FILES
                     and not p.startswith(known_prefixes + GLOBAL_PREFIXES)]
    contract_paths = {p for p in paths if p.startswith("contracts/")}
    scoped_order = (ORDER_OPENAPI in contract_paths
                    and contract_paths <= {ORDER_OPENAPI, "contracts/CHANGELOG.md"}
                    and (root / ORDER_OPENAPI).is_file())
    broad_contract_change = bool(contract_paths) and not scoped_order
    full_release = release_snapshot and bool(paths) and not docs_only
    global_change = force or full_release or bool(full_triggers or unknown_paths)
    contracts = global_change or bool(contract_paths)
    go_shared = any(p.startswith("go/observability/") or p in SHARED_GO_FILES for p in paths)
    infra = any(p.startswith("infra/") for p in paths)
    frontend = contracts or any(p.startswith("frontend/") for p in paths)
    mesh = contracts or any(p.startswith(("deploy/mesh/", "docs/mesh/")) for p in paths)
    kubernetes = mesh or infra or any(p.startswith("deploy/k8s/") for p in paths)

    def select(components):
        selected = []
        for component in components:
            name, directory = component["name"], component["dir"] + "/"
            if global_change or broad_contract_change or infra or (scoped_order and name in ORDER_HTTP_COMPONENTS):
                selected.append(component)
                continue
            if ((go_shared and directory.startswith("go/"))
                or any(p.startswith(directory) for p in paths)
                or (name == "database-migrations" and any(
                    (p.startswith("java/services/") and "/db/migration/" in p)
                    or (p.startswith("go/services/") and "/migrations/" in p) for p in paths))
                or (name == "deployment-checks" and any(p.startswith("scripts/cd/") for p in paths))):
                selected.append(component)
        return selected

    selected_services, selected_images = select(services), select(images)
    reasons = []
    if force:
        reasons.append("Full snapshot explicitly requested.")
    if full_release:
        reasons.append("CD requires a full runtime snapshot.")
    if full_triggers:
        reasons.append("CI, Compose or deployment configuration changed.")
    if unknown_paths:
        reasons.append("An unmapped path requires full verification.")
    if broad_contract_change:
        reasons.append("A shared or unmapped contract requires full compatibility coverage.")
    if scoped_order:
        reasons.append("Order public OpenAPI selects its provider, Gateway, frontend and deployment checks.")
    if infra:
        reasons.append("Shared infrastructure changed.")
    if go_shared:
        reasons.append("Shared Go workspace inputs changed.")
    if docs_only:
        reasons.append("Documentation only. The release is skipped unless explicitly requested.")
    if not reasons:
        reasons.append("Changed component paths select their checks and build contexts.")
    if global_change or broad_contract_change or infra:
        scope = "full-matrix"
    elif docs_only:
        scope = "docs-only"
    elif len(selected_services) == 1 and len(selected_images) <= 1 and not (frontend or contracts or mesh or kubernetes):
        scope = "single-service"
    else:
        scope = "focused"

    return {
        "services": {"include": selected_services},
        "images": {"include": selected_images},
        "frontend": frontend, "contracts": contracts, "mesh": mesh, "kubernetes": kubernetes,
        "scope": scope, "reasons": reasons, "changed_files": len(paths), "changed_paths": paths[:30],
    }


def write_summary(result, output):
    def code(value):
        return "<code>" + html.escape(value).replace("|", "&#124;").replace("\n", " ") + "</code>"

    text = [f"### CI plan · {result['scope']}", "", *[f"- {reason}" for reason in result["reasons"]], ""]
    for kind in ("services", "images"):
        names = [code(component["name"]) for component in result[kind]["include"]]
        text.append(f"- {kind.capitalize()} ({len(names)}) · " + (", ".join(names) or "none"))
    checks = [name for name in ("frontend", "contracts", "mesh", "kubernetes") if result[name]]
    text.append("- Additional checks · " + ", ".join(["tooling", "security", *checks]))
    if result["changed_paths"]:
        text += ["", f"Changed paths ({result['changed_files']} total, up to 30 shown)", ""]
        text += [f"- {code(path)}" for path in result["changed_paths"]]
    output.write("\n".join(text) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main", help="commit/ref, or ALL")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--direct", action="store_true", help="two-dot comparison for push events")
    parser.add_argument("--all", action="store_true", help="full snapshot while preserving the real diff base")
    parser.add_argument("--release-snapshot", action="store_true", help="full snapshot for a non-documentation push")
    parser.add_argument("--section", choices=("services", "images"))
    parser.add_argument("--github-output", action="store_true")
    parser.add_argument("--plan-file", type=Path)
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
    result = plan(root, paths, force or args.all, args.release_snapshot)
    result["base"] = base
    if args.plan_file:
        args.plan_file.parent.mkdir(parents=True, exist_ok=True)
        args.plan_file.write_text(json.dumps(result) + "\n", encoding="utf-8")
    if args.github_output:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in result.items():
                rendered = value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))
                output.write(f"{key}={rendered}\n")
            for key in ("services", "images"):
                output.write(f"{key}_any={str(bool(result[key]['include'])).lower()}\n")
            output.write("plan=" + json.dumps(result, separators=(",", ":")) + "\n")
        if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(summary, "a", encoding="utf-8") as output:
                write_summary(result, output)
    print(json.dumps(result[args.section] if args.section else result, separators=(",", ":")))


if __name__ == "__main__":
    main()

#!/usr/bin/env bash
# Prints the GitHub Actions matrix of services whose tests must run for a change (contracts ADR-008).
#
#   scripts/ci/changed-services.sh [BASE_REF]       BASE_REF defaults to origin/main; "ALL" selects every service
#
# A service is selected when a file under its directory changed. A change to contracts/, to the CI definitions or to
# this script selects every service, because contracts are copied into services (proto drift tests) and consumers
# validate fixtures against provider contracts. Output is one JSON line: {"include":[{"name","kind","dir"}...]}.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
BASE_REF="${1:-origin/main}"

all_services() {
  for pom in java/services/*/pom.xml; do
    [[ -f "$pom" ]] && printf 'java %s\n' "$(dirname "$pom")"
  done
  for mod in go/services/*/go.mod; do
    [[ -f "$mod" ]] && printf 'go %s\n' "$(dirname "$mod")"
  done
}

changed_files() {
  if [[ "$BASE_REF" == "ALL" ]] || [[ "$BASE_REF" =~ ^0+$ ]] || ! git rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null; then
    echo "__ALL__"
  else
    git diff --name-only "$BASE_REF"...HEAD
  fi
}

mapfile -t FILES < <(changed_files)
select_all=false
for file in "${FILES[@]}"; do
  case "$file" in
    __ALL__|contracts/*|.github/workflows/services.yml|scripts/ci/*) select_all=true ;;
  esac
done

selected=()
while read -r kind dir; do
  [[ -z "${dir:-}" ]] && continue
  hit=$select_all
  if [[ "$hit" == false ]]; then
    for file in "${FILES[@]}"; do
      if [[ "$file" == "$dir/"* ]]; then hit=true; break; fi
    done
  fi
  if [[ "$hit" == true ]]; then
    selected+=("{\"name\":\"$(basename "$dir")\",\"kind\":\"$kind\",\"dir\":\"$dir\"}")
  fi
done < <(all_services)

if ((${#selected[@]} == 0)); then
  printf '{"include":[]}\n'
else
  (IFS=,; printf '{"include":[%s]}\n' "${selected[*]}")
fi

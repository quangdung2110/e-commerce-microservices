#!/usr/bin/env bash
# Tests scripts/ci/changed-services.sh on a throwaway repository with two fake services.
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/changed-services.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
git init -q .
git config user.email test@example.com
git config user.name test
mkdir -p java/services/alpha go/services/beta contracts scripts/ci
touch java/services/alpha/pom.xml go/services/beta/go.mod contracts/readme.md docs.md
git add -A && git commit -q -m base
BASE="$(git rev-parse HEAD)"

failed=0
check() {  # check NAME EXPECTED-NAMES(comma list, may be empty)
  local name="$1" expected="$2" actual
  actual="$(bash "$SCRIPT" "$BASE" | python3 -c 'import json,sys; print(",".join(sorted(x["name"] for x in json.load(sys.stdin)["include"])))')"
  if [[ "$actual" == "$expected" ]]; then echo "ok   $name"; else echo "FAIL $name: got '$actual', want '$expected'"; failed=1; fi
}
change() {  # change FILE: commit a modification on top of BASE and reset afterwards
  git reset -q --hard "$BASE"
  mkdir -p "$(dirname "$1")"
  echo "$RANDOM" >>"$1"
  git add -A && git commit -q -m change
}

git reset -q --hard "$BASE"
check "no change selects nothing" ""

change java/services/alpha/Main.java
check "a Java service change selects only that service" "alpha"

change go/services/beta/main.go
check "a Go service change selects only that service" "beta"

change docs.md
check "a change outside services and contracts selects nothing" ""

change contracts/readme.md
check "a contracts change selects every service" "alpha,beta"

change scripts/ci/anything.sh
check "a CI script change selects every service" "alpha,beta"

echo "ALL selects everything:"
actual="$(bash "$SCRIPT" ALL | python3 -c 'import json,sys; print(",".join(sorted(x["name"] for x in json.load(sys.stdin)["include"])))')"
[[ "$actual" == "alpha,beta" ]] && echo "ok   ALL" || { echo "FAIL ALL: $actual"; failed=1; }

exit "$failed"

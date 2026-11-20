#!/usr/bin/env bash
# Breaking-change gate for contracts/ (contracts ADR-008).
#
#   contracts/tools/check-breaking.sh [BASE_REF]      BASE_REF defaults to origin/main
#   CONTRACTS_DIR=/some/contracts check-breaking.sh   compare another tree (used by tests/test_check_breaking.sh)
#
# Compares the contracts in the working tree against the contracts at BASE_REF:
#   * Protobuf: buf breaking (FILE rules from contracts/buf.yaml) over contracts/grpc and contracts/shared/proto.
#   * OpenAPI: oasdiff breaking --fail-on ERR for every http/**/openapi.yaml present in both trees.
#   * A contract file that exists at BASE_REF and is gone now counts as breaking.
# A change that is breaking on purpose gets a new major version directory (conventions/compatibility.md). The only
# other way through is a waiver naming the file, both snapshots and the ADR that approves the break.
#
# Needs git and Docker. The tools run from pinned images so local and CI results are identical.
set -euo pipefail

BUF_IMAGE="bufbuild/buf:1.47.2"
OASDIFF_IMAGE="tufin/oasdiff:v1.11.7"

REPO_ROOT="$(git rev-parse --show-toplevel)"
BASE_REF="${1:-origin/main}"
WAIVERS="$REPO_ROOT/contracts/tools/breaking-waivers.txt"

# git-bash on Windows rewrites /paths for docker and wants C:/ style host paths; Linux needs neither.
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

if [[ "$BASE_REF" =~ ^0+$ ]] || ! git -C "$REPO_ROOT" rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null; then
  echo "No base to compare with ('$BASE_REF' does not resolve); skipping the breaking-change check."
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/base"
git -C "$REPO_ROOT" archive "$BASE_REF" contracts | tar -x -C "$WORK/base"
CURRENT="${CONTRACTS_DIR:-$REPO_ROOT/contracts}"
BASE="$WORK/base/contracts"

spec_digest() {
  # Treat LF and CRLF checkouts as the same spec.
  sed 's/\r$//' "$1" | sha256sum | cut -d ' ' -f 1
}

waived() {
  local spec="$1" before after
  [[ -f "$WAIVERS" ]] || return 1
  before="$(spec_digest "$BASE/$spec")"
  after="$(spec_digest "$CURRENT/$spec")"
  awk -v spec="$spec" -v before="$before" -v after="$after" '
    $1 == spec && $2 == before && $3 == after && $4 ~ /ADR-[0-9]+-/ { found = 1 }
    END { exit !found }
  ' "$WAIVERS"
}

failures=0
fail() { echo "BREAKING: $*" >&2; failures=$((failures + 1)); }

echo "== Protobuf (buf breaking) against $BASE_REF"
if [[ -f "$BASE/buf.yaml" ]]; then
  if ! docker run --rm -v "$(host_path "$CURRENT"):/workspace/current:ro" -v "$(host_path "$BASE"):/workspace/base:ro" \
        "$BUF_IMAGE" breaking /workspace/current --against /workspace/base; then
    fail "buf reported breaking protobuf changes"
  fi
else
  echo "no buf.yaml at $BASE_REF; nothing to compare"
fi

echo "== OpenAPI (oasdiff breaking) against $BASE_REF"
while IFS= read -r spec; do
  if [[ ! -f "$CURRENT/$spec" ]]; then
    fail "$spec was removed"
    continue
  fi
  # A file identical to the baseline cannot be a breaking change; this keeps the gate fast.
  if cmp -s "$BASE/$spec" "$CURRENT/$spec"; then
    continue
  fi
  if ! output="$(docker run --rm -v "$(host_path "$CURRENT"):/workspace/current:ro" -v "$(host_path "$BASE"):/workspace/base:ro" \
        "$OASDIFF_IMAGE" breaking "/workspace/base/$spec" "/workspace/current/$spec" --fail-on ERR 2>&1)"; then
    if [[ "$output" =~ [1-9][0-9]*[[:space:]]error, ]] && waived "$spec"; then
      echo "waived: $spec"
    else
      echo "$output" >&2
      fail "$spec has breaking changes or could not be compared"
    fi
  elif [[ -n "$output" ]]; then
    echo "$spec: $output"
  fi
done < <(cd "$BASE" && find http -name openapi.yaml | sort)

if (( failures > 0 )); then
  echo "$failures contract file(s) failed the breaking-change check against $BASE_REF." >&2
  echo "Make the change compatible, publish it as a new major version (contracts/conventions/compatibility.md)," >&2
  echo "or record an approved exception in contracts/tools/breaking-waivers.txt." >&2
  exit 1
fi
echo "No breaking contract changes against $BASE_REF."

#!/usr/bin/env bash
# Proves contracts/tools/check-breaking.sh fails on breaking changes and passes on compatible ones (contracts ADR-008).
#
#   contracts/tools/tests/test_check_breaking.sh [REF]     REF is the commit whose contracts are the baseline (default HEAD)
#
# For each case it copies the contracts at REF, applies one mutation, and runs the gate against REF. Needs git, Docker, python3.
# Mutations are Python expressions on the file text `s`; NL stands for a newline so no shell escaping is involved.
set -euo pipefail

REF="${1:-HEAD}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$HERE/../check-breaking.sh"
REPO_ROOT="$(git rev-parse --show-toplevel)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SPEC="http/cart/v1/openapi.yaml"
PROTO="grpc/ecommerce/product/v1/product_service.proto"

fresh() {
  rm -rf "$WORK/current" && mkdir -p "$WORK/current"
  git -C "$REPO_ROOT" archive "$REF" contracts | tar -x -C "$WORK/current"
  CURRENT="$WORK/current/contracts"
}

mutate() {  # mutate FILE PYTHON-EXPRESSION-ON-s
  python3 - "$CURRENT/$1" "$2" <<'PY'
import sys
path, expr = sys.argv[1], sys.argv[2]
NL = chr(10)
with open(path, encoding="utf-8", newline="") as handle:
    s = handle.read()
crlf = chr(13) + NL in s
s = s.replace(chr(13) + NL, NL)
before = s
s = eval(expr)
assert s != before, "mutation changed nothing"
if crlf:
    s = s.replace(NL, chr(13) + NL)
with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(s)
PY
}

run_gate() { CONTRACTS_DIR="$CURRENT" bash "$GATE" "$REF" >"$WORK/out.log" 2>&1; }

failed=0
expect_pass() {
  if run_gate; then echo "ok   $1"; else echo "FAIL $1 (gate rejected a compatible change)"; tail -n 15 "$WORK/out.log"; failed=1; fi
}
expect_fail() {
  if run_gate; then echo "FAIL $1 (gate accepted a breaking change)"; failed=1; else echo "ok   $1"; fi
}

fresh
expect_pass "identical contracts"

fresh
mutate "$SPEC" 's.replace("openapi: 3.1.0", "openapi: 3.1.0" + NL + "# harmless comment", 1)'
expect_pass "comment-only OpenAPI edit"

fresh
mutate "$PROTO" 's.replace("message SkuNotFound {", "message SkuNotFound {" + NL + "  string note = 9;", 1)'
expect_pass "adding a protobuf field"

fresh
mutate "$SPEC" 's.replace("  /api/v1/carts/mine/items:", "  /api/v1/carts/mine/items-renamed:", 1)'
expect_fail "renaming an OpenAPI path"

fresh
rm -f "$CURRENT/$SPEC"
expect_fail "deleting an OpenAPI contract"

fresh
mutate "$PROTO" 's.replace("message SkuNotFound {" + NL + "  string sku_id = 1;", "message SkuNotFound {", 1)'
expect_fail "removing a protobuf field"

fresh
mutate "$PROTO" 's.replace("string product_id = 2;", "int64 product_id = 2;", 1)'
expect_fail "changing a protobuf field type"

exit "$failed"

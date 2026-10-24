#!/usr/bin/env bash
# Prints the GitHub Actions matrix; plan.py also includes shared Go modules and consumers.
#
#   scripts/ci/changed-services.sh [BASE_REF]       BASE_REF defaults to origin/main; "ALL" selects every service
#
# A service is selected when a file under its directory changed. A change to contracts/, to the CI definitions or to
# this script selects every service, because contracts are copied into services (proto drift tests) and consumers
# validate fixtures against provider contracts. Output is one JSON line: {"include":[{"name","kind","dir"}...]}.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/plan.py" --base "${1:-origin/main}" --section services

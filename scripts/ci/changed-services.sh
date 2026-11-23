#!/usr/bin/env bash
# Prints the GitHub Actions matrix; plan.py also includes shared Go modules and consumers.
#
#   scripts/ci/changed-services.sh [BASE_REF]       BASE_REF defaults to origin/main; "ALL" selects every service
#
# The planner selects direct service changes and the registered consumers of scoped contracts.
# Shared or unmapped changes select every service. Output is one JSON line: {"include":[{"name","kind","dir"}...]}.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/plan.py" --base "${1:-origin/main}" --section services

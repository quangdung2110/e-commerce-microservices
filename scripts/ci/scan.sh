#!/usr/bin/env bash
# Pin the scanner AND image digest. A scan error is a CI failure, never a warning.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
OUTPUT="$ROOT/ci-artifacts"
mkdir -p "$OUTPUT"
SCANNER='aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c'
# Keep the database on Docker's Linux filesystem, including on Docker Desktop.
# Hosted runners have isolated daemons; separate local source/image caches avoid concurrent DB writes.
CACHE_VOLUME=ecommerce-ci-trivy-image-cache
if [[ "${1:-}" == source ]]; then CACHE_VOLUME=ecommerce-ci-trivy-source-cache; fi
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
trivy() {
  docker run --rm \
    -e TRIVY_CACHE_DIR=/cache \
    -v "$(host_path "$ROOT"):/repo:ro" \
    -v "$(host_path "$OUTPUT"):/reports" \
    --mount "type=volume,source=$CACHE_VOLUME,target=/cache" "$SCANNER" "$@" --no-progress
}
case "${1:-}" in
  source)
    source_args=(--severity 'HIGH,CRITICAL' --exit-code 1 --format json --offline-scan
      --skip-dirs /repo/.git --skip-dirs /repo/.tools --skip-dirs /repo/.tmp
      --skip-dirs /repo/ci-artifacts --skip-dirs /repo/frontend/node_modules)
    status=0
    trivy fs --scanners secret "${source_args[@]}" --output /reports/source-secrets.json /repo || status=1
    # Resolve Java from the final image's JARs, not a second uncached Maven graph traversal.
    # POMs still receive secret scanning above; Java image scanning is a required gate.
    trivy fs --scanners vuln "${source_args[@]}" --include-dev-deps --skip-files '**/pom.xml' \
      --output /reports/source-dependencies.json /repo || status=1
    exit "$status"
    ;;
  image)
    trivy image --timeout 15m --input /reports/image.tar --scanners vuln,secret \
      --severity HIGH,CRITICAL --exit-code 1 --format json --output /reports/image-scan.json
    ;;
  sbom)
    trivy image --timeout 15m --input /reports/image.tar --format cyclonedx --output /reports/sbom.cdx.json
    ;;
  *) echo 'Usage: scan.sh source|image|sbom' >&2; exit 2 ;;
esac

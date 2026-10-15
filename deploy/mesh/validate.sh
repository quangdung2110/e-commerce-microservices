#!/usr/bin/env bash
# Validates the mesh manifests without a cluster (mesh ADR-001): generated files are current, the tests that compare them with
# docs/mesh/runtime-contract.md pass, every resource matches the Kubernetes and Istio schemas, `istioctl analyze` is clean, and
# the Helm values render with the pinned Istio charts. Needs Python (PyYAML) and Docker; run from anywhere.
set -euo pipefail

MESH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

KUBECONFORM=ghcr.io/yannh/kubeconform:v0.6.7
ISTIOCTL=istio/istioctl:1.24.2
HELM=alpine/helm:3.16.2
PY="$(command -v python3 || command -v python)"
cd "$MESH"
ISTIO_VERSION="$("$PY" -c "import yaml;print(yaml.safe_load(open('versions.yaml'))['istio']['version'])")"

echo "== generated files are current"
"$PY" generate.py --check

echo "== tests against docs/mesh/runtime-contract.md"
"$PY" -m unittest discover -s tests

MOUNT="$(host_path "$MESH")"

echo "== kubeconform (Kubernetes 1.31 + Istio and Gateway API CRDs)"
docker run --rm -v "$MOUNT:/mesh:ro" "$KUBECONFORM" -strict -summary -kubernetes-version 1.31.0 \
  -schema-location default \
  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
  /mesh/generated

echo "== istioctl analyze (offline)"
files=()
for f in "$MESH"/generated/*.yaml; do files+=("/mesh/generated/$(basename "$f")"); done
docker run --rm -v "$MOUNT:/mesh:ro" --entrypoint istioctl "$ISTIOCTL" analyze --use-kube=false --failure-threshold Warning "${files[@]}"

echo "== helm template (Istio $ISTIO_VERSION charts)"
for chart in base istiod cni ztunnel; do
  docker run --rm -v "$MOUNT/helm:/v:ro" "$HELM" template "$chart" "$chart" \
    --repo https://istio-release.storage.googleapis.com/charts --version "$ISTIO_VERSION" -n istio-system -f "/v/$chart.values.yaml" >/dev/null
  echo "  $chart renders"
done
echo "mesh manifests OK"

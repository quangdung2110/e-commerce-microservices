#!/usr/bin/env python3
"""Install the pinned ambient mesh on the isolated kind-ecommerce-mesh context.

Uses a separate kubeconfig; never changes or deploys to the user's current context.
Requires kubectl, PyYAML, and the pinned Windows tools under .tools/mesh (see README).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
MESH = ROOT / "deploy/mesh"
TOOLS = ROOT / ".tools/mesh"
CONFIG = TOOLS / "kubeconfig"
CONTEXT = "kind-ecommerce-mesh"
VERSIONS = yaml.safe_load((MESH / "versions.yaml").read_text(encoding="utf-8"))


def run(args, *, capture=False, input=None):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, input=input, text=True,
                            encoding="utf-8", errors="replace", stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, args[:5]))}\n"
                           f"{result.stdout or ''}{result.stderr or ''}")
    return result.stdout if capture else ""


def tool(name):
    candidates = {"helm": [TOOLS / "windows-amd64/helm.exe"],
                  "kind": [TOOLS / "kind.exe"], "istioctl": [TOOLS / "istioctl.exe"]}
    return next((str(p) for p in candidates.get(name, []) if p.is_file()), None) or shutil.which(name) or name


def kube(*args, capture=False, input=None):
    return run([tool("kubectl"), "--kubeconfig", CONFIG, "--context", CONTEXT, *args],
               capture=capture, input=input)


def apply(docs):
    kube("apply", "-f", "-", input=yaml.safe_dump_all(docs, sort_keys=False))


def load_image(image):
    try:
        run([tool("kind"), "load", "docker-image", "--name", "ecommerce-mesh", "--nodes", "ecommerce-mesh-worker", image])
    except RuntimeError:
        # Docker Desktop may retain an OCI index with uncached ARM64/attestation blobs.
        # Import only the architecture used by this cluster, not every descriptor in the index.
        archive = TOOLS / "load-amd64.tar"
        run(["docker", "image", "save", "--platform", "linux/amd64", "-o", archive, image])
        with archive.open("rb") as stream:
            subprocess.run(["docker", "exec", "-i", "ecommerce-mesh-worker", "ctr", "--namespace=k8s.io", "images", "import",
                            "--platform", "linux/amd64", "--digests", "-"], stdin=stream, check=True)


def guard():
    current = run([tool("kubectl"), "--kubeconfig", CONFIG, "config", "current-context"], capture=True).strip()
    if current != CONTEXT:
        raise RuntimeError(f"Refusing context {current!r}; expected {CONTEXT!r}")


def fetch_charts():
    """The release's chart source is platform-independent, even in the Windows zip."""
    TOOLS.mkdir(parents=True, exist_ok=True)
    archive = TOOLS / "istio-release.zip"
    if not archive.exists():
        urllib.request.urlretrieve(VERSIONS["istio"]["chart_bundle_url"], archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != VERSIONS["istio"]["chart_bundle_sha256"]:
        raise RuntimeError("Istio chart bundle checksum mismatch")
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(TOOLS)
    return TOOLS / f"istio-{VERSIONS['istio']['version']}/manifests/charts"


def bootstrap():
    if sys.platform != "win32":
        raise RuntimeError("Automatic tool download currently supports Windows amd64. Install helm/kind/istioctl manually.")
    TOOLS.mkdir(parents=True, exist_ok=True)
    downloads = [
        ("kind.exe", "https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-windows-amd64",
         "4b22adaa135368c5a465d56bbd8e520cbea87272a06ca00b6078e7b81515c9fc"),
        ("helm.zip", "https://get.helm.sh/helm-v4.3.1-windows-amd64.zip",
         "fcfd7b796dcf0f225abc40b5636a3b01f55f3de2fb1dfb18475df94e62c14112"),
        ("istio-release.zip", VERSIONS["istio"]["chart_bundle_url"], VERSIONS["istio"]["chart_bundle_sha256"]),
    ]
    for filename, url, checksum in downloads:
        path = TOOLS / filename
        if not path.exists():
            urllib.request.urlretrieve(url, path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
            raise RuntimeError(f"Checksum mismatch: {path}")
        if path.suffix == ".zip":
            with zipfile.ZipFile(path) as bundle:
                bundle.extractall(TOOLS)
    source = TOOLS / f"istio-{VERSIONS['istio']['version']}/bin/istioctl.exe"
    shutil.copy2(source, TOOLS / "istioctl.exe")


def create():
    run([tool("kind"), "create", "cluster", "--name", "ecommerce-mesh", "--config", MESH / "kind.yaml",
         "--kubeconfig", CONFIG, "--wait", "120s"])


def install():
    guard()
    run([sys.executable, MESH / "generate.py", "--check"])
    run([sys.executable, ROOT / "deploy/k8s/generate.py", "--check"])
    version = VERSIONS["istio"]["version"]
    charts = TOOLS / f"istio-{version}/manifests/charts"
    if not charts.exists():
        raise RuntimeError("Run local.py bootstrap first (official release bundle includes the pinned Helm charts).")
    kube("apply", "--server-side", "-f", f"https://github.com/kubernetes-sigs/gateway-api/releases/download/"
         f"{VERSIONS['gateway_api']['version']}/standard-install.yaml")
    paths = {"base": "base", "istiod": "istio-control/istio-discovery", "cni": "istio-cni", "ztunnel": "ztunnel"}
    for chart, relative in paths.items():
        release = {"base": "istio-base", "cni": "istio-cni"}.get(chart, chart)
        print(f"Installing {release} {version}", flush=True)
        run([tool("helm"), "upgrade", "--install", release, charts / relative, "--kubeconfig", CONFIG,
             "--kube-context", CONTEXT, "-n", "istio-system", "--create-namespace", "--wait", "--timeout", "5m",
             "-f", MESH / f"helm/{chart}.values.yaml"])
    # Establish enforcement before any application workload is deployed.
    for filename in ("00-namespaces.yaml", "10-serviceaccounts.yaml", "20-peerauthentication.yaml",
                     "30-waypoint.yaml", "40-authorizationpolicies-l4.yaml", "41-authorizationpolicies-ecommerce.yaml"):
        kube("apply", "-f", MESH / "generated" / filename)
    kube("-n", "ecommerce", "rollout", "status", "deployment/ecommerce-waypoint", "--timeout=180s")
    # HPA needs a metrics-server to scale; keep the required minimum even without one on kind.
    kube("-n", "ecommerce", "scale", "deployment/ecommerce-waypoint", "--replicas=2")
    kube("apply", "-f", MESH / "generated/80-networkpolicies.yaml")
    for name in ("50-virtualservices.yaml", "51-destinationrules.yaml", "60-serviceentries.yaml", "70-telemetry.yaml"):
        kube("apply", "-f", MESH / "generated" / name)


def stack():
    guard()
    run([sys.executable, ROOT / "deploy/k8s/data_credentials.py", "--context", CONTEXT, "--kubeconfig", CONFIG])
    kube("apply", "-f", MESH / "checkout-redis-exporter-policy.yaml")
    for layer in ("infra", "services", "observability"):
        print(f"Preparing {layer}", flush=True)
        rendered = kube("kustomize", "--load-restrictor", "LoadRestrictionsNone", ROOT / "deploy/k8s" / layer, capture=True)
        docs = [d for d in yaml.safe_load_all(rendered) if d]
        if layer == "observability":
            # Install only the exporter and its Redis alias; the full observability stack is optional.
            docs = [d for d in docs if (d["kind"], d["metadata"]["name"]) in {
                ("Service", "checkout-redis"), ("Service", "checkout-redis-exporter"),
                ("Deployment", "checkout-redis-exporter"),
            }]
        images = set()
        for doc in docs:
            pod = doc.get("spec", {}).get("template", {}).get("spec", {})
            for container in pod.get("containers", []) + pod.get("initContainers", []):
                image = container["image"]
                exists = subprocess.run(["docker", "image", "inspect", image], stdout=subprocess.DEVNULL,
                                        stderr=subprocess.DEVNULL).returncode == 0
                if container.get("imagePullPolicy") == "Never" and not exists:
                    raise RuntimeError(f"Build local image first: {image}")
                if exists:
                    images.add(image)
        if layer == "services":
            # Mesh ingress policy replaces the non-mesh L4 caller matrix. Keep all application manifests.
            docs = [d for d in docs if d["kind"] != "NetworkPolicy"]
            # Preserve mesh enrollment while updating namespaces, rather than temporarily removing it.
            for doc in docs:
                if doc["kind"] == "Namespace" and doc["metadata"]["name"] in ("edge", "ecommerce", "observability"):
                    labels = doc["metadata"].setdefault("labels", {})
                    labels["istio.io/dataplane-mode"] = "ambient"
                    if doc["metadata"]["name"] == "ecommerce":
                        labels["istio.io/use-waypoint"] = "ecommerce-waypoint"
        for image in sorted(images):
            print(f"Loading {image} into kind", flush=True)
            load_image(image)
        for doc in docs:
            if doc["kind"] != "Deployment":
                continue
            pod = doc["spec"]["template"]
            local = [c["image"] for c in pod["spec"]["containers"] if c.get("imagePullPolicy") == "Never"]
            if local:
                ids = [run(["docker", "image", "inspect", "--format", "{{.Id}}", image], capture=True).strip() for image in local]
                pod["metadata"].setdefault("annotations", {})["mesh.ecommerce/local-image-ids"] = ",".join(ids)
        if layer == "services":
            kube("-n", "data", "delete", "job", "kafka-connect-init", "--ignore-not-found")
        apply(docs)
    # k8s namespaces/ServiceAccounts may have been updated by kustomize: retain ambient enrollment and token rules.
    for name in ("00-namespaces.yaml", "10-serviceaccounts.yaml", "80-networkpolicies.yaml"):
        kube("apply", "-f", MESH / "generated" / name)


def validate():
    guard()
    run([sys.executable, MESH / "generate.py", "--check"])
    run([sys.executable, ROOT / "deploy/k8s/generate.py", "--check"])
    run([sys.executable, "-m", "unittest", "discover", "-s", MESH / "tests", "-v"])
    charts = TOOLS / f"istio-{VERSIONS['istio']['version']}/manifests/charts"
    for chart, relative in {"base": "base", "istiod": "istio-control/istio-discovery", "cni": "istio-cni", "ztunnel": "ztunnel"}.items():
        run([tool("helm"), "template", chart, charts / relative, "-n", "istio-system", "-f",
             MESH / f"helm/{chart}.values.yaml"], capture=True)
        print(f"Helm {chart}: OK", flush=True)
    # Kubernetes + Istio admission validate the actual schemas of the installed versions.
    for path in sorted((MESH / "generated").glob("*.yaml")):
        kube("apply", "--dry-run=server", "-f", path)
    kube("apply", "--dry-run=server", "-f", MESH / "checkout-redis-exporter-policy.yaml")
    run([tool("istioctl"), "--kubeconfig", CONFIG, "--context", CONTEXT,
         "analyze", "--all-namespaces", "--failure-threshold", "Error"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["bootstrap", "create", "install", "stack", "validate"])
    args = parser.parse_args()
    globals()[args.action]()


if __name__ == "__main__":
    main()

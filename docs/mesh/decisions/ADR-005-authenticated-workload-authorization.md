# ADR-005: Enforce workload identity and route authorization with ambient mesh

Date: 2026-11-04. Status: Accepted. Supersedes the version and path-translation choices of ADR-001.

## Problem

Application filters accept an allowlisted `X-Service-Identity` without cryptographic caller authentication.
A reachable workload can claim another caller and enter a more privileged route. Configuration files alone
do not establish the mesh boundary. The original L4 scraper exception also allowed bypassing waypoint checks.

## Decision

- Install Istio ambient 1.31.3 on an isolated, two-node kind cluster, Kubernetes 1.35.8. Pin the node image,
  all four mesh images, and the official release archive by SHA-256. Use Helm charts from that release archive
  because the official chart repository index did not contain this patch at verification time.
- Preserve one ServiceAccount per workload and disable application token mounting. Kubernetes RBAC must not
  allow these accounts to deploy workloads, alter their identity, or mint other accounts' tokens.
- Require STRICT mTLS on business listeners. Public entrypoint exceptions are limited to Traefik 80/443 and
  Frontend 3000. These exceptions do not authorize direct access to business APIs.
- Require the waypoint identity at the business Pod's L4 boundary. Configure the waypoint for `all` traffic.
  Business grants target Services; clients use Service DNS. Direct Pod IP requests are intentionally denied,
  even for an otherwise authorized caller. They must never bypass the Service policies.
- Match verified principal + destination port + path + method. Use URI templates for path parameters instead
  of broad suffixes. Preserve the existing attribution header checks in applications.
- Remove Prometheus from the business L4 baseline. Scrapes go through the waypoint and permit only GET of
  the exact metrics endpoint on the HTTP port. Gateway and startup health checks have equally narrow grants.
- Local mail provider simulators use port-restricted L4 grants, mTLS, and explicit waypoint opt-out. Only
  Notification can reach Mailpit SMTP or LocalStack AWS APIs. They are not business services.
- Keep namespace enrollment labels when applying the application layer. The non-mesh namespace manifests
  must never temporarily remove the mesh boundary. The local installer uses a dedicated kubeconfig/context.

## Verification and limits

`deploy/mesh/verify_live.py` uses the real Inventory, Payment, and Product applications and read-only callers
with real ServiceAccounts. It tests cross-node calls, spoofed headers, direct Pod IP, missing mTLS, HTTP/gRPC,
metrics access, health checks, and Kubernetes RBAC. Results and public certificate metadata are saved under
`docs/security/mesh-p1-evidence-2026-11-04/`. Invalid business data is not counted as an authorization success;
successful authorized reads or domain NOT_FOUND responses are required.

The deployment closes the caller-spoofing boundary on this local cluster. It does not certify production,
Docker Compose, certificate rotation over a full lifetime, retries, distributed tracing, egress restrictions,
or the complete business purchase/refund flow. The production transport readiness gate stays unchanged.

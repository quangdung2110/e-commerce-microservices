# =============================================================================
# Routing Rules Matrix
# =============================================================================
# Documents the routing configuration for all services in the E-Commerce platform.
# =============================================================================

# =============================================================================
# ROUTING TABLE
# =============================================================================

| Path Pattern | Service | Auth Required | Rate Limit | Priority | Notes |
|--------------|---------|---------------|------------|----------|-------|
| `/api/users/**` | user-service | Yes | 50 req/s | 10 | All endpoints require auth |
| `/api/v1/orders/**`, `/api/v1/order-operations/**` | order-service | Yes | 50 req/s | 20 | Buyer-scoped Order API |
| `/api/v1/admin/orders/**`, `/api/v1/admin/order-operations/**` | order-service | Yes, `ADMIN` | 20 req/s | 30 | Admin Order API |
| `/api/v1/products/**` | product-service | Yes, `ADMIN` | 50 req/s | 30 | Product, SKU, media and lifecycle administration |
| `/api/v1/categories/**` | product-service | Yes, `ADMIN` | 50 req/s | 30 | Category and category-attribute administration |
| `/api/v1/attributes/**` | product-service | Yes, `ADMIN` | 50 req/s | 30 | Attribute and option administration |
| `/api/v1/search/**` (GET) | search-service | No | 100 req/s | 10 | Public storefront browse/search |
| `/api/v2/search/**` (GET, trừ reindex) | search-service | No | 100 req/s | 10 | Canonical Search v2; chạy song song v1 trong migration |
| `/api/v2/search/reindex-runs/**` (GET, POST) | search-service | Yes, `ADMIN` | 50 req/s | 30 | Priority cao hơn route public Search; không dùng `/internal` |
| `/api/v1/storefront/products/**` (GET) | product-detail-bff | No | 100 req/s | 30 | PDP; response-header timeout 3 s (`product-detail-bff@file` transport), retry only on connection failure, keeps the BFF `Cache-Control` |
| `/media/**` (GET, HEAD) | minio (`product-media` bucket) | No | 100 req/s | 20 | **Local only**: stand-in for the product CDN, rewritten to `/product-media/**`; production serves media from its own CDN domain |
| `/api/payments/**` | payment-service | Yes | 20 req/s | 10 | Strictest rate limit |
| `/.well-known/jwks.json` | auth-service | No | 10 req/s | 5 | JWKS public key endpoint |
| `/.well-known/openid-configuration` | auth-service | No | 10 req/s | 5 | OIDC discovery |
| `/api/auth/**` | auth-service | No | 10 req/s | 5 | Login, register, refresh |
| `/health` | - | No | No limit | 1 | Health check endpoint |
| `/ping` | - | No | No limit | 1 | Liveness probe |

## Checkout routes

Checkout runs in Compose (`checkout-service`, host port 8096) and its four routes are active:
the Traefik labels live on the `checkout-service` container and the three user-keyed rate
profiles in `config/dynamic.yml`. The canonical source is
`contracts/http/checkout/v1/openapi.yaml`.

| Path | Method | Auth | Rate profile (`dynamic.yml` / Compose) | Retry |
|---|---|---|---|---|
| `/api/v1/checkout/preview` | POST | `authenticated` | `rate-limit-checkout-preview` (10/user/minute) | none |
| `/api/v1/checkout/submit` | POST | `authenticated` | `rate-limit-checkout-submit` (3/user/minute) | none |
| `/api/v1/checkout/previews/{checkout_token}/voucher-options` | GET | `authenticated` | `rate-limit-checkout-voucher-options` (20/user/minute) | connection failures only |
| `/api/v1/checkout/submissions/{checkout_token}` | GET | `authenticated` | `rate-limit-api` (`api-authenticated`) | connection failures only |

All four routes strip client-supplied identity headers (`authenticated` = strip + ForwardAuth),
use the `business-api` transport (25 s response-header wait, above the Checkout deadlines of
5 s, 1.5 s and 20 s) and return `Cache-Control: private, no-store`. Gateway never retries a POST.
The token segments are matched by regex, and Checkout's own access line records the route
template, never the path. The rate limit is Traefik CE's in-memory counter per replica;
production with several replicas needs the shared state of `docs/gateway/runtime-contract.md`
section 7.

`docker-compose.yml` là cấu hình development nên router Product dùng
`dev-admin-auth@file` để inject một identity `ADMIN` cố định sau khi đã xóa các
header giả mạo từ client. Khi triển khai thật, thay middleware này bằng
`jwt-auth-product@file` (hoặc ForwardAuth tương đương); Product Service vẫn là
nơi quyết định caller có role `ADMIN` hay không.

# =============================================================================
# HEADER ENRICHMENT (after authentication)
# =============================================================================

Nguon chuan cua bang nay la docs/gateway/runtime-contract.md muc 6.1. Khi hai
file lech nhau thi runtime-contract dung; dung sua bang duoi day mot minh.

| Header Name | Source (JWT Claim) | Description | Required |
|-------------|-------------------|-------------|----------|
| `X-User-Id` | `sub` | User's unique identifier | Yes |
| `X-User-Roles` | `roles` | Comma-separated roles | Yes |
| `X-User-Session-Id` | `sid` | Session family id | Yes |
| `X-Token-Jti` | `jti` | Verified token id; Auth denylists it on logout | Yes |

Access token khong co claim `email` va `device_id`, nen gateway khong sinh
`X-User-Email` hay `X-User-Device-Id`. Ca bon header tren deu bi xoa khoi
request cua client truoc khi xac thuc, ke ca tren route anonymous.

# =============================================================================
# LOAD BALANCING METHODS
# =============================================================================

| Service | Method | Use Case |
|---------|--------|----------|
| user-service | Round Robin | Default, stateless |
| order-service | Round Robin | Default, stateless |
| product-service | Round Robin | Default, stateless catalog API |
| payment-service | Round Robin | Transaction integrity |
| auth-service | Round Robin | JWKS fetch requests |

# =============================================================================
# HEALTH CHECK CONFIGURATION
# =============================================================================

| Service | Health Path | Interval | Timeout | Expected Status |
|---------|-------------|----------|---------|----------------|
| user-service | `/health` | 10s | 5s | 200 |
| order-service | `/health` | 10s | 5s | 200 |
| product-service | `/actuator/health` | 10s | 5s | 200 |
| payment-service | `/health` | 10s | 5s | 200 |
| auth-service | `/health` | 10s | 5s | 200 |

# =============================================================================
# EXAMPLE REQUESTS
# =============================================================================

```bash
# Test routing - User Service
curl -k https://localhost/api/users/123 \
  -H "Authorization: Bearer <token>"

# Test routing - Product Service admin API (development compose injects ADMIN)
curl http://localhost:8088/api/v1/products

# Public product browse remains on Search Service
curl http://localhost:8088/api/v1/search/products

# Canonical Search v2 storefront route
curl http://localhost:8088/api/v2/search/products

# Product Detail BFF (anonymous PDP read); media URLs in the body point at /media/**
curl -i http://localhost:8088/api/v1/storefront/products/smart-watch

# Local stand-in for the product CDN (MinIO product-media bucket)
curl -I http://localhost:8088/media/placeholder/product.svg

# Must stay 404 at the gateway; this endpoint is internal-only
curl http://localhost:8088/internal/v1/products/export

# Test routing - JWKS Endpoint
curl -k https://localhost/.well-known/jwks.json

# Test routing - Health Check
curl -k https://localhost/health

# Test with trace header
curl -k https://localhost/api/users/123 \
  -H "Authorization: Bearer <token>" \
  -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
```

# =============================================================================
# DOCKER COMPOSE EXAMPLE
# =============================================================================

```yaml
# Example user-service docker-compose.yml

services:
  user-service:
    image: user-service:latest
    container_name: user-service
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.user-service.rule=PathPrefix(`/api/users`)"
      - "traefik.http.routers.user-service.entrypoints=websecure"
      - "traefik.http.routers.user-service.tls=true"
      - "traefik.http.routers.user-service.middlewares=user-auth,rate-limit-api"
      - "traefik.http.services.user-service.loadbalancer.server.port=8080"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.path=/health"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.interval=10s"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.timeout=5s"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.expectedStatusCodes=200"

networks:
  ecommerce-network:
    external: true
```

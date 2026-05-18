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
| `/api/orders/**` | order-service | Yes | 50 req/s | 10 | All endpoints require auth |
| `/api/products/**` (GET) | product-service | No | 100 req/s | 10 | Public read access |
| `/api/products/**` (POST/PUT/DELETE) | product-service | Yes | 50 req/s | 20 | Write operations require auth |
| `/api/payments/**` | payment-service | Yes | 20 req/s | 10 | Strictest rate limit |
| `/.well-known/jwks.json` | auth-service | No | 10 req/s | 5 | JWKS public key endpoint |
| `/.well-known/openid-configuration` | auth-service | No | 10 req/s | 5 | OIDC discovery |
| `/api/auth/**` | auth-service | No | 10 req/s | 5 | Login, register, refresh |
| `/health` | - | No | No limit | 1 | Health check endpoint |
| `/ping` | - | No | No limit | 1 | Liveness probe |

# =============================================================================
# HEADER ENRICHMENT (after authentication)
# =============================================================================

| Header Name | Source (JWT Claim) | Description | Required |
|-------------|-------------------|-------------|----------|
| `X-User-Id` | `sub` | User's unique identifier | Yes |
| `X-User-Email` | `email` | User's email address | No |
| `X-User-Roles` | `roles` | Comma-separated roles | Yes |
| `X-User-Device-Id` | `device_id` | Device identifier | No |
| `X-User-Session-Id` | `session_id` | Session identifier | No |

# =============================================================================
# LOAD BALANCING METHODS
# =============================================================================

| Service | Method | Use Case |
|---------|--------|----------|
| user-service | Round Robin | Default, stateless |
| order-service | Round Robin | Default, stateless |
| product-service | Round Robin | Default, caching layer upstream |
| payment-service | Round Robin | Transaction integrity |
| auth-service | Round Robin | JWKS fetch requests |

# =============================================================================
# HEALTH CHECK CONFIGURATION
# =============================================================================

| Service | Health Path | Interval | Timeout | Expected Status |
|---------|-------------|----------|---------|----------------|
| user-service | `/health` | 10s | 5s | 200 |
| order-service | `/health` | 10s | 5s | 200 |
| product-service | `/health` | 10s | 5s | 200 |
| payment-service | `/health` | 10s | 5s | 200 |
| auth-service | `/health` | 10s | 5s | 200 |

# =============================================================================
# EXAMPLE REQUESTS
# =============================================================================

```bash
# Test routing - User Service
curl -k https://localhost/api/users/123 \
  -H "Authorization: Bearer <token>"

# Test routing - Product Service (public)
curl -k https://localhost/api/products

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

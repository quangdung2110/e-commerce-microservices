#!/bin/bash
# =============================================================================
# Test Script for Phase 4 - JWT authentication (gateway-auth + ForwardAuth)
#
# Xac thuc JWT do service go/services/gateway-auth dam nhiem; Traefik goi no qua
# middleware `authenticated@file` (strip-identity-headers + ForwardAuth). Chay
# voi stack docker-compose o thu muc goc:
#   docker compose up -d redis auth-service gateway-auth api-gateway
#   go/services/api-gateway/scripts/test-phase4.sh
# =============================================================================

GATEWAY="${GATEWAY:-http://localhost:8088}"
# Route can danh tinh cua Auth, di qua gateway-auth.
PROTECTED="${GATEWAY}/api/v1/auth/sessions"

echo "=========================================="
echo "Phase 4: JWT authentication (gateway-auth)"
echo "=========================================="
echo ""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; }
warn() { echo -e "${YELLOW}⚠ WARN${NC}: $1"; }

# Test 1: gateway-auth health
echo "1. Checking gateway-auth readiness..."
if docker exec gateway-auth wget -q -O - http://localhost:8080/health/ready 2>/dev/null | grep -q UP; then
    pass "gateway-auth ready (Redis + JWKS)"
else
    fail "gateway-auth not ready — check: docker logs gateway-auth"
fi

# Test 2: public path (no auth)
echo "2. Testing public refresh route without token..."
response=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${GATEWAY}/api/v1/auth/sessions/refresh")
if [ "$response" == "401" ]; then
    pass "Refresh route reaches Auth without ForwardAuth (401 from Auth: no cookie)"
else
    warn "Refresh route returned HTTP $response"
fi

# Test 3: protected path without token
echo "3. Testing protected path without token..."
body=$(curl -s -w "\n%{http_code}" "$PROTECTED")
if [ "$(echo "$body" | tail -1)" == "401" ] && echo "$body" | grep -q '"code":"UNAUTHORIZED"'; then
    pass "Missing token → 401 UNAUTHORIZED (problem+json)"
else
    fail "Expected 401 UNAUTHORIZED, got: $body"
fi

# Test 4: invalid token
echo "4. Testing with invalid token..."
body=$(curl -s -w "\n%{http_code}" "$PROTECTED" -H "Authorization: Bearer invalid.token.here")
if [ "$(echo "$body" | tail -1)" == "401" ] && echo "$body" | grep -q '"code":"TOKEN_INVALID"'; then
    pass "Invalid token → 401 TOKEN_INVALID"
else
    fail "Expected 401 TOKEN_INVALID, got: $body"
fi

# Test 5: spoofed identity header without token
echo "5. Testing spoofed X-User-Id without token..."
response=$(curl -s -o /dev/null -w "%{http_code}" "$PROTECTED" \
    -H "X-User-Id: 0b6f5e2e-3f7a-4c4e-9a53-3c1a0f2a9e11" -H "X-User-Roles: ADMIN")
if [ "$response" == "401" ]; then
    pass "Client identity headers are not trusted"
else
    fail "Spoofed identity returned HTTP $response (expected 401)"
fi

echo ""
echo "Manual checks (need a real token from Auth, e.g. frontend/scripts/create-dev-account.sh):"
echo "  - Valid token → 200 on ${PROTECTED}"
echo "  - DELETE ${PROTECTED} with the token, then reuse it → 401 TOKEN_REVOKED"
echo "  - docker pause redis → 503 DEPENDENCY_UNAVAILABLE + Retry-After; docker unpause redis"
echo "  - docker stop auth-service → a valid token still passes from the JWKS cache"
echo ""
echo "Unit tests: bash go/services/gateway-auth/scripts/go.sh test ./..."
echo "=========================================="

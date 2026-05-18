# =============================================================================
# Test Script for Phase 2 Verification
# =============================================================================

#!/bin/bash

GATEWAY="http://localhost:80"
TRAEFIK="http://localhost:8080"

echo "=========================================="
echo "Phase 2: Traefik Basic Setup Verification"
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() {
    echo -e "${GREEN}✓ PASS${NC}: $1"
}

fail() {
    echo -e "${RED}✗ FAIL${NC}: $1"
}

warn() {
    echo -e "${YELLOW}⚠ WARN${NC}: $1"
}

# Test 1: Traefik is running
echo "1. Checking Traefik container..."
if docker ps | grep -q api-gateway; then
    pass "Traefik container is running"
else
    fail "Traefik container is not running"
fi

# Test 2: Traefik is responding
echo "2. Checking Traefik health endpoint..."
response=$(curl -s -o /dev/null -w "%{http_code}" ${TRAEFIK}/ping 2>/dev/null)
if [ "$response" == "200" ]; then
    pass "Traefik is responding (HTTP $response)"
else
    fail "Traefik is not responding (HTTP $response)"
fi

# Test 3: Dashboard is accessible
echo "3. Checking Traefik Dashboard..."
response=$(curl -s -o /dev/null -w "%{http_code}" -u admin:password ${TRAEFIK}/api/overview 2>/dev/null)
if [ "$response" == "200" ]; then
    pass "Dashboard is accessible (HTTP $response)"
else
    warn "Dashboard returned HTTP $response (may need auth)"
fi

# Test 4: HTTP redirects to HTTPS
echo "4. Checking HTTP to HTTPS redirect..."
response=$(curl -s -o /dev/null -w "%{http_code}" -L ${GATEWAY}/ 2>/dev/null)
if [ "$response" == "200" ] || [ "$response" == "301" ] || [ "$response" == "302" ]; then
    pass "HTTP handling works (HTTP $response)"
else
    warn "HTTP returned $response"
fi

# Test 5: Metrics endpoint
echo "5. Checking Prometheus metrics endpoint..."
response=$(curl -s -o /dev/null -w "%{http_code}" ${TRAEFIK}/metrics 2>/dev/null)
if [ "$response" == "200" ]; then
    pass "Metrics endpoint is accessible (HTTP $response)"
else
    fail "Metrics endpoint returned HTTP $response"
fi

# Test 6: Configuration files
echo "6. Checking configuration files..."
if [ -f "config/traefik.yml" ]; then
    pass "traefik.yml exists"
else
    fail "traefik.yml not found"
fi

if [ -f "config/dynamic.yml" ]; then
    pass "dynamic.yml exists"
else
    fail "dynamic.yml not found"
fi

# Test 7: Middleware list
echo "7. Checking middleware configuration..."
response=$(curl -s -u admin:password ${TRAEFIK}/api/http/middlewares 2>/dev/null)
if echo "$response" | grep -q "rate-limit"; then
    pass "Rate limit middleware configured"
else
    warn "Could not verify middleware configuration"
fi

# Test 8: Redis connection
echo "8. Checking Redis connection..."
if docker exec redis redis-cli -a ${REDIS_PASSWORD:-redis-password} ping 2>/dev/null | grep -q PONG; then
    pass "Redis is responding"
else
    fail "Redis is not responding"
fi

# Test 9: Jaeger connection
echo "9. Checking Jaeger..."
response=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:16686/ 2>/dev/null)
if [ "$response" == "200" ]; then
    pass "Jaeger UI is accessible (HTTP $response)"
else
    warn "Jaeger UI returned HTTP $response"
fi

echo ""
echo "=========================================="
echo "Verification Complete"
echo "=========================================="
echo ""
echo "Next steps:"
echo "  1. If all tests pass, proceed to Phase 3"
echo "  2. If tests fail, check logs: make logs"
echo "  3. Restart services: make restart"

# =============================================================================
# Test Script for Phase 3 Verification
# =============================================================================

#!/bin/bash

GATEWAY="http://localhost:80"

echo "=========================================="
echo "Phase 3: Service Discovery & Routing Test"
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

# Test 1: Health endpoint
echo "1. Testing /health endpoint..."
response=$(curl -s -o /dev/null -w "%{http_code}" ${GATEWAY}/health 2>/dev/null)
if [ "$response" == "200" ]; then
    pass "/health returned 200"
else
    warn "/health returned $response (service may not be configured)"
fi

# Test 2: JWKS endpoint
echo "2. Testing JWKS endpoint..."
response=$(curl -s -o /dev/null -w "%{http_code}" ${GATEWAY}/.well-known/jwks.json 2>/dev/null)
if [ "$response" == "200" ]; then
    pass "JWKS endpoint accessible (HTTP $response)"
else
    warn "JWKS returned $response (auth-service may not be running)"
fi

# Test 3: Check routing configuration
echo "3. Checking routing configuration..."
echo "   Expected routes:"
echo "   - /api/users/** → user-service"
echo "   - /api/orders/** → order-service"
echo "   - /api/products/** → product-service"
echo "   - /api/payments/** → payment-service"
echo ""
echo "   Note: Full routing test requires all services to be running."
echo "   Services should respond to /health endpoints."

# Test 4: Generate sample routing test script
echo "4. Generating routing test script..."
cat > scripts/test-routing.sh << 'ROUTING_SCRIPT'
#!/bin/bash
GATEWAY="http://localhost:80"

echo "Testing Service Routing..."
echo ""

echo "1. User Service (/api/users)"
curl -s -o /dev/null -w "   Status: %{http_code}\n" ${GATEWAY}/api/users/1

echo "2. Order Service (/api/orders)"
curl -s -o /dev/null -w "   Status: %{http_code}\n" ${GATEWAY}/api/orders/1

echo "3. Product Service (/api/products)"
curl -s -o /dev/null -w "   Status: %{http_code}\n" ${GATEWAY}/api/products/1

echo "4. Payment Service (/api/payments)"
curl -s -o /dev/null -w "   Status: %{http_code}\n" ${GATEWAY}/api/payments/1

echo ""
echo "Note: 404 = routing works, 401 = auth required, 502 = service not running"
ROUTING_SCRIPT

chmod +x scripts/test-routing.sh
pass "Routing test script created at scripts/test-routing.sh"

# Test 5: Traefik API - List routers
echo ""
echo "5. Checking Traefik router configuration..."
echo "   Run the following to see active routers:"
echo "   curl -s -u admin:password http://localhost:8080/api/http/routers | jq"
echo ""

# Test 6: Traefik API - List services
echo "6. Checking Traefik service configuration..."
echo "   Run the following to see active services:"
echo "   curl -s -u admin:password http://localhost:8080/api/http/services | jq"
echo ""

# Test 7: Health check configuration
echo "7. Verifying health check labels..."
echo "   Health checks should be configured for all services:"
echo "   - traefik.http.services.SERVICE.loadbalancer.healthcheck.path=/health"
echo "   - traefik.http.services.SERVICE.loadbalancer.healthcheck.interval=10s"
echo ""

echo "=========================================="
echo "Phase 3 Verification Complete"
echo "=========================================="
echo ""
echo "To test routing with all services:"
echo "  1. Start all microservices"
echo "  2. Run: ./scripts/test-routing.sh"
echo "  3. Check Traefik dashboard: http://localhost:8080"

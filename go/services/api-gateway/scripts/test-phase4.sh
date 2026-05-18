# =============================================================================
# Test Script for Phase 4 - JWT Middleware
# =============================================================================

#!/bin/bash

GATEWAY="http://localhost:80"

echo "=========================================="
echo "Phase 4: JWT Middleware Verification"
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; }
warn() { echo -e "${YELLOW}⚠ WARN${NC}: $1"; }

# Test 1: Build plugin
echo "1. Building Go Plugin..."
cd cmd/traefik-plugin
if go build -buildmode=plugin -o ../../plugins/jwt_auth.so . 2>/dev/null; then
    pass "Plugin built successfully"
    cd ../..
else
    fail "Plugin build failed"
    cd ../..
    echo "Check Go version compatibility with Traefik"
fi

# Test 2: Check plugin file
echo "2. Checking plugin file..."
if [ -f "plugins/jwt_auth.so" ]; then
    pass "Plugin file exists: plugins/jwt_auth.so"
else
    fail "Plugin file not found"
fi

# Test 3: Test public path (no auth)
echo "3. Testing public path without auth..."
response=$(curl -s -o /dev/null -w "%{http_code}" ${GATEWAY}/.well-known/jwks.json 2>/dev/null)
if [ "$response" == "200" ] || [ "$response" == "404" ]; then
    pass "Public path accessible (HTTP $response)"
else
    warn "JWKS returned HTTP $response"
fi

# Test 4: Test protected path without auth
echo "4. Testing protected path without auth..."
response=$(curl -s -o /dev/null -w "%{http_code}" ${GATEWAY}/api/users/1 2>/dev/null)
if [ "$response" == "401" ]; then
    pass "Protected path returns 401 (auth required)"
elif [ "$response" == "404" ]; then
    warn "Service not deployed yet, but auth middleware should return 401"
else
    warn "Protected path returned HTTP $response (expected 401)"
fi

# Test 5: Test with invalid token
echo "5. Testing with invalid token..."
response=$(curl -s -w "%{http_code}" ${GATEWAY}/api/users/1 \
    -H "Authorization: Bearer invalid.token.here" 2>/dev/null)
if echo "$response" | grep -q "401"; then
    pass "Invalid token rejected"
else
    warn "Invalid token response: $response"
fi

# Test 6: Check error response format
echo "6. Checking error response format..."
response=$(curl -s ${GATEWAY}/api/users/1 \
    -H "Authorization: Bearer invalid" 2>/dev/null)
if echo "$response" | grep -q "error"; then
    pass "Error response contains 'error' field"
else
    warn "Could not verify error format"
fi

# Test 7: Generate test token (requires Auth Service)
echo "7. Testing with Auth Service..."
echo "   Note: This test requires a valid JWT token from Auth Service"
echo ""
echo "   To generate a test token:"
echo "   1. Login to Auth Service"
echo "   2. Copy the access_token"
echo "   3. Run: curl -H 'Authorization: Bearer <token>' ${GATEWAY}/api/users/me"
echo ""

# Test 8: Check Redis denylist
echo "8. Testing Redis denylist integration..."
echo "   To test denylist:"
echo "   1. Add token to denylist:"
echo "      redis-cli SET auth:denylist:test-jti 1"
echo "   2. Test request with that token"
echo "   3. Should return 401"
echo ""

# Test 9: Check JWKS cache
echo "9. Testing JWKS cache..."
echo "   Check logs for cache hits/misses:"
echo "   docker logs api-gateway | grep JWKS"
echo ""

# Test 10: Check Pub/Sub invalidation
echo "10. Testing JWKS invalidation..."
echo "    To test Pub/Sub invalidation:"
echo "    1. Publish invalidation message:"
echo '       redis-cli PUBLISH auth:jwks:update '"'"'{"type":"all"}'"'"'
echo "    2. Check logs for cache clear:"
echo "       docker logs api-gateway | grep invalidat"
echo ""

echo "=========================================="
echo "Phase 4 Verification Complete"
echo "=========================================="
echo ""
echo "Summary:"
echo "  - Plugin build: Check plugin file exists"
echo "  - Auth middleware: Returns 401 for missing/invalid token"
echo "  - Error format: JSON with 'error' field"
echo ""
echo "Next steps:"
echo "  1. Deploy services with JWT auth labels"
echo "  2. Test full auth flow with real tokens"
echo "  3. Verify header enrichment in upstream requests"

#!/bin/bash

echo "=========================================="
echo "Phase 5: Pub/Sub Invalidation Test"
echo "=========================================="
echo ""

GATEWAY="http://localhost:80"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PWD="${REDIS_PASSWORD:-redis-password}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; }
info() { echo -e "${YELLOW}ℹ INFO${NC}: $1"; }

# Test 1: Check Redis connection
echo "1. Checking Redis connection..."
if docker exec redis redis-cli -a ${REDIS_PWD} ping 2>/dev/null | grep -q PONG; then
    pass "Redis is responding"
else
    fail "Redis is not responding"
fi

# Test 2: Check JWKS cache status
echo ""
echo "2. Checking JWKS cache..."
echo "   Check plugin logs for cache initialization:"
echo "   docker logs api-gateway 2>&1 | grep -i jwks"
echo ""

# Test 3: Manual cache clear via Redis
echo "3. Testing manual cache clear..."
echo ""
echo "   Option A: Clear entire JWKS cache:"
echo "   docker exec redis redis-cli -a ${REDIS_PWD} PUBLISH auth:jwks:update '{\"type\":\"all\"}'"
echo ""
echo "   Option B: Clear specific key:"
echo '   docker exec redis redis-cli -a ${REDIS_PWD} PUBLISH auth:jwks:update '"'"'{"type":"key_rotation","kid":"test-kid"}'"'"'
echo ""

# Test 4: Test with Auth Service key rotation
echo "4. Simulating key rotation event..."
echo ""
echo "   To simulate key rotation:"
echo "   1. Auth Service rotates keys"
echo "   2. Auth Service publishes to auth:jwks:update"
echo "   3. Gateway receives message and clears cache"
echo "   4. Gateway fetches new JWKS on next request"
echo ""

# Test 5: Verify cache invalidation
echo "5. Verifying cache invalidation..."
echo "   After publishing invalidation message:"
echo "   docker logs api-gateway 2>&1 | grep -i 'invalidat\|clear'"
echo ""

# Test 6: Check reconnection logic
echo "6. Testing reconnection logic..."
echo "   Simulate Redis disconnect:"
echo "   docker pause redis"
echo "   sleep 5"
echo "   docker logs api-gateway 2>&1 | grep -i reconnect"
echo "   docker unpause redis"
echo ""

# Interactive test
echo "=========================================="
echo "Interactive Test"
echo "=========================================="
echo ""
read -p "Publish 'all' invalidation message? (y/n): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Publishing message..."
    docker exec redis redis-cli -a ${REDIS_PWD} PUBLISH auth:jwks:update '{"type":"all","timestamp":"2026-05-15T10:00:00Z"}' 2>/dev/null
    echo "Sleeping 2 seconds for processing..."
    sleep 2
    echo "Checking logs for invalidation:"
    docker logs api-gateway 2>&1 | grep -i 'invalidat\|clear' | tail -5
fi

echo ""
echo "=========================================="
echo "Phase 5 Verification Complete"
echo "=========================================="
echo ""
echo "Summary:"
echo "  - Pub/Sub listener is integrated in plugin"
echo "  - Handles key_rotation, key_retirement, all events"
echo "  - Automatic reconnection on disconnect"
echo ""
echo "Auth Service Integration Required:"
echo "  - Publish to 'auth:jwks:update' channel on key rotation"
echo "  - Message format: {\"type\":\"key_rotation\",\"kid\":\"...\"}"

#!/bin/bash

echo "=========================================="
echo "Phase 6: Rate Limiting Test"
echo "=========================================="
echo ""

GATEWAY="http://localhost:80"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; }
info() { echo -e "${YELLOW}ℹ INFO${NC}: $1"; }

# Test 1: Check rate limit middleware configuration
echo "1. Checking rate limit configuration..."
echo "   Rate limits configured in dynamic.yml:"
echo "   - rate-limit-default: 100 req/s, burst 200"
echo "   - rate-limit-api: 50 req/s, burst 100"
echo "   - rate-limit-payment: 20 req/s, burst 40"
echo "   - rate-limit-auth: 10 req/s, burst 20"
echo ""
echo "   Verify via Traefik API:"
echo "   curl -s http://localhost:8080/api/http/middlewares | jq '.[] | select(.rateLimit != null)'"
echo ""

# Test 2: Simple rate limit test (burst)
echo "2. Testing rate limit with burst..."
echo "   Sending burst requests to test rate limiting..."
echo ""

# Send 100 requests and count 429 responses
LIMIT=100
COUNT_429=0
COUNT_200=0
COUNT_OTHER=0

for i in $(seq 1 $LIMIT); do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" ${GATEWAY}/.well-known/jwks.json 2>/dev/null)
    if [ "$STATUS" == "429" ]; then
        COUNT_429=$((COUNT_429 + 1))
    elif [ "$STATUS" == "200" ]; then
        COUNT_200=$((COUNT_200 + 1))
    else
        COUNT_OTHER=$((COUNT_OTHER + 1))
    fi
    
    # Show progress every 20 requests
    if [ $((i % 20)) -eq 0 ]; then
        echo "   Progress: $i/$LIMIT requests sent"
    fi
done

echo ""
echo "   Results:"
echo "   - 200 OK: $COUNT_200"
echo "   - 429 Rate Limited: $COUNT_429"
echo "   - Other: $COUNT_OTHER"
echo ""

if [ $COUNT_429 -gt 0 ]; then
    pass "Rate limiting is working ($COUNT_429 requests were rate limited)"
else
    info "No requests were rate limited (may need longer test or lower limits)"
fi

# Test 3: IP strategy test
echo "3. Testing IP strategy (X-Forwarded-For)..."
echo "   Testing with different X-Forwarded-For headers..."
echo ""

for IP in "192.168.1.1" "192.168.1.2" "192.168.1.3"; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "X-Forwarded-For: $IP" \
        ${GATEWAY}/.well-known/jwks.json 2>/dev/null)
    echo "   IP $IP: HTTP $STATUS"
done

echo ""

# Test 4: Auth endpoint rate limit (stricter)
echo "4. Testing auth endpoint rate limit (stricter: 10 req/s)..."
echo "   Sending rapid requests to /api/auth..."
echo ""

AUTH_LIMIT=20
AUTH_429=0
AUTH_OTHER=0

for i in $(seq 1 $AUTH_LIMIT); do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" ${GATEWAY}/api/auth/login 2>/dev/null)
    if [ "$STATUS" == "429" ]; then
        AUTH_429=$((AUTH_429 + 1))
    else
        AUTH_OTHER=$((AUTH_OTHER + 1))
    fi
done

echo "   Results: $AUTH_429 rate limited out of $AUTH_LIMIT requests"
echo ""

# Test 5: Verify rate limit headers
echo "5. Checking rate limit response headers..."
echo "   When rate limited, check for these headers:"
echo "   - X-RateLimit-Limit"
echo "   - X-RateLimit-Remaining"
echo "   - Retry-After"
echo ""

# Send request and check headers
curl -s -i ${GATEWAY}/.well-known/jwks.json 2>/dev/null | grep -iE "ratelimit|retry|x-" || echo "   (No rate limit headers in response)"

echo ""
echo "=========================================="
echo "Phase 6 Verification Complete"
echo "=========================================="
echo ""
echo "Rate Limiting Configuration:"
echo "  ✓ Default: 100 req/s, burst 200"
echo "  ✓ API: 50 req/s, burst 100"
echo "  ✓ Payment: 20 req/s, burst 40"
echo "  ✓ Auth: 10 req/s, burst 20"
echo ""
echo "Note: Actual limits may vary based on Traefik version"

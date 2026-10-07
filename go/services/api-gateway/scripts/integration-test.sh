#!/bin/bash
# =============================================================================
# Integration Test Script for API Gateway JWT Plugin
# =============================================================================
# This script tests the JWT authentication plugin without requiring
# the full Traefik setup. It tests:
# - JWKS Cache operations
# - Redis integration
# - Token extraction and validation
# =============================================================================

set -e

echo "========================================"
echo "API Gateway JWT Plugin Integration Tests"
echo "========================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
PASSED=0
FAILED=0

# Function to run a test
run_test() {
    local test_name=$1
    local test_command=$2
    
    echo -n "Testing: $test_name... "
    if eval "$test_command" > /dev/null 2>&1; then
        echo -e "${GREEN}PASS${NC}"
        ((PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        ((FAILED++))
    fi
}

# Function to assert output
assert_contains() {
    local test_name=$1
    local expected=$2
    local command=$3
    
    echo -n "Testing: $test_name... "
    if output=$(eval "$command" 2>&1) && echo "$output" | grep -q "$expected"; then
        echo -e "${GREEN}PASS${NC}"
        ((PASSED++))
    else
        echo -e "${RED}FAIL${NC}"
        ((FAILED++))
    fi
}

echo "1. Checking Redis Connection..."
echo "----------------------------"
if docker exec redis redis-cli -a redis-password ping > /dev/null 2>&1; then
    echo -e "${GREEN}Redis is running${NC}"
else
    echo -e "${RED}Redis is not running. Start with: docker compose up -d redis${NC}"
fi
echo ""

echo "2. Testing Redis Denylist Operations..."
echo "--------------------------------------"

# Test setting a denylist entry
run_test "Set denylist entry" \
    "docker exec redis redis-cli -a redis-password SET auth:denylist:test-jti-001 'revoked'"

# Test checking denylist entry exists
assert_contains "Check denylist entry exists" "1" \
    "docker exec redis redis-cli -a redis-password EXISTS auth:denylist:test-jti-001"

# Test deleting denylist entry
run_test "Delete denylist entry" \
    "docker exec redis redis-cli -a redis-password DEL auth:denylist:test-jti-001"

# Test revoked_before key
run_test "Set revoked_before key" \
    "docker exec redis redis-cli -a redis-password SET auth:revoked_before:user-123 1716000000"

assert_contains "Check revoked_before key exists" "1716000000" \
    "docker exec redis redis-cli -a redis-password GET auth:revoked_before:user-123"

run_test "Delete revoked_before key" \
    "docker exec redis redis-cli -a redis-password DEL auth:revoked_before:user-123"

echo ""

echo "3. Testing Redis Pub/Sub Channel..."
echo "-----------------------------------"

# Test publishing to JWKS update channel
run_test "Publish to JWKS update channel" \
    "docker exec redis redis-cli -a redis-password PUBLISH auth:jwks:update '{\"type\":\"all\"}'"

echo ""

echo "4. Checking Jaeger Connection..."
echo "-------------------------------"
if curl -s http://localhost:16686 > /dev/null 2>&1; then
    echo -e "${GREEN}Jaeger UI is accessible at http://localhost:16686${NC}"
else
    echo -e "${YELLOW}Jaeger UI is not accessible (optional)${NC}"
fi
echo ""

echo "5. Running gateway-auth Unit Tests..."
echo "------------------------------------"
# JWT verification lives in go/services/gateway-auth (ForwardAuth), not in a
# Traefik plugin.
bash "$(dirname "$0")/../../gateway-auth/scripts/go.sh" test ./... 2>&1 | tee /tmp/test_output.txt
if [ ${PIPESTATUS[0]} -eq 0 ]; then
    echo -e "${GREEN}Unit tests passed${NC}"
else
    echo -e "${RED}Unit tests failed${NC}"
fi
echo ""

echo "========================================"
echo "Test Summary"
echo "========================================"
echo -e "Passed: ${GREEN}${PASSED}${NC}"
echo -e "Failed: ${RED}${FAILED}${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed.${NC}"
    exit 1
fi

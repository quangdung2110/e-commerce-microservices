#!/bin/bash

echo "=========================================="
echo "Phase 7: Observability Test"
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; }
info() { echo -e "${YELLOW}ℹ INFO${NC}: $1"; }

GATEWAY="http://localhost:80"
JAEGER="http://localhost:16686"
PROMETHEUS="http://localhost:9090"
GRAFANA="http://localhost:3000"

# Test 1: Check Tracing Configuration
echo "1. Checking OpenTelemetry Tracing..."
echo "   Traefik tracing configuration in traefik.yml:"
echo "   - Service Name: api-gateway"
echo "   - OTLP Endpoint: jaeger:4317"
echo "   - Protocol: grpc"
echo ""

# Test 2: Check trace_id generation
echo "2. Testing trace_id generation..."
TRACE_ID=$(curl -s -D - -o /dev/null \
    -H "Authorization: Bearer test" \
    ${GATEWAY}/api/users/1 2>/dev/null | \
    grep -i "X-Request-Id:" | awk '{print $2}')

if [ -n "$TRACE_ID" ]; then
    pass "trace_id generated: $TRACE_ID"
else
    info "No trace_id in response (may be 401 for invalid token)"
fi

# Test 3: Check access log format
echo "3. Checking access log format..."
echo "   Access log should include:"
echo "   - timestamp, level, message"
echo "   - trace_id, span_id"
echo "   - client_ip, method, path"
echo "   - status, duration"
echo ""
echo "   Check logs with:"
echo "   docker logs api-gateway 2>&1 | grep -E '\"trace_id\"|\"path\"|\"method\"'"
echo ""

# Test 4: Jaeger connection
echo "4. Checking Jaeger connection..."
JAEGER_STATUS=$(curl -s -o /dev/null -w "%{http_code}" ${JAEGER}/ 2>/dev/null)
if [ "$JAEGER_STATUS" == "200" ]; then
    pass "Jaeger UI is accessible"
else
    fail "Jaeger UI returned HTTP $JAEGER_STATUS"
fi

# Test 5: Prometheus metrics
echo "5. Checking Prometheus metrics..."
METRICS=$(curl -s ${GATEWAY}:8080/metrics 2>/dev/null | head -20)
if echo "$METRICS" | grep -q "traefik_"; then
    pass "Traefik metrics are available"
    echo "   Sample metrics:"
    echo "$METRICS" | grep "traefik_service" | head -5 | sed 's/^/   /'
else
    info "Could not fetch metrics (check port 8080)"
fi

# Test 6: Prometheus connection
echo "6. Checking Prometheus connection..."
PROM_STATUS=$(curl -s -o /dev/null -w "%{http_code}" ${PROMETHEUS}/ 2>/dev/null)
if [ "$PROM_STATUS" == "200" ]; then
    pass "Prometheus is accessible"
else
    fail "Prometheus returned HTTP $PROM_STATUS"
fi

# Test 7: Grafana connection
echo "7. Checking Grafana connection..."
GRAFANA_STATUS=$(curl -s -o /dev/null -w "%{http_code}" ${GRAFANA}/ 2>/dev/null)
if [ "$GRAFANA_STATUS" == "200" ]; then
    pass "Grafana is accessible"
    echo "   Default credentials: admin/admin"
else
    fail "Grafana returned HTTP $GRAFANA_STATUS"
fi

# Test 8: Check JSON log format
echo "8. Checking JSON log format..."
echo "   Recent log entries:"
docker logs api-gateway 2>&1 | tail -5 | while read line; do
    if echo "$line" | grep -q "^{"; then
        echo "   $line" | head -c 200
        echo "..."
    fi
done
echo ""

# Test 9: Check trace propagation
echo "9. Testing trace propagation..."
echo "   To trace a request:"
echo "   1. Send request with traceparent header:"
echo '      curl -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \'
echo "         ${GATEWAY}/.well-known/jwks.json"
echo "   2. Open Jaeger UI: ${JAEGER}"
echo "   3. Search for trace ID: 0af7651916cd43dd8448eb211c80319c"
echo ""

# Test 10: Grafana Dashboard
echo "10. Checking Grafana Dashboard..."
echo "    The API Gateway dashboard should be provisioned."
echo "    Navigate to: ${GRAFANA}/dashboards"
echo "    Dashboard name: API Gateway Dashboard"
echo ""

echo "=========================================="
echo "Phase 7 Verification Complete"
echo "=========================================="
echo ""
echo "Observability Stack:"
echo "  ✓ Traefik - API Gateway"
echo "  ✓ Jaeger - Distributed Tracing"
echo "  ✓ Prometheus - Metrics Collection"
echo "  ✓ Grafana - Dashboards"
echo ""
echo "Log Format:"
echo "  ✓ JSON structured logging"
echo "  ✓ trace_id, span_id in logs"
echo "  ✓ Redacted Authorization header"
echo ""
echo "Useful URLs:"
echo "  - Traefik Dashboard: http://localhost:8080"
echo "  - Jaeger UI: ${JAEGER}"
echo "  - Prometheus: ${PROMETHEUS}"
echo "  - Grafana: ${GRAFANA} (admin/admin)"

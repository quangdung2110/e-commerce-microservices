# =============================================================================
# Integration Test Script for API Gateway JWT Plugin
# =============================================================================
# This script tests the JWT authentication plugin without requiring
# the full Traefik setup.
# =============================================================================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "API Gateway JWT Plugin Integration Tests" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$ErrorActionPreference = "Continue"
$PASSED = 0
$FAILED = 0

# Helper function to run tests
function Run-Test {
    param (
        [string]$TestName,
        [string]$Command
    )
    Write-Host -NoNewline "Testing: $TestName... "
    $result = Invoke-Expression $Command 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "PASS" -ForegroundColor Green
        $script:PASSED++
    } else {
        Write-Host "FAIL" -ForegroundColor Red
        $FAILED++
    }
}

# Helper function to assert output contains value
function Assert-Contains {
    param (
        [string]$TestName,
        [string]$Expected,
        [string]$Command
    )
    Write-Host -NoNewline "Testing: $TestName... "
    $result = Invoke-Expression $Command 2>&1 | Out-String
    if ($result -match $Expected) {
        Write-Host "PASS" -ForegroundColor Green
        $script:PASSED++
    } else {
        Write-Host "FAIL" -ForegroundColor Red
        $script:FAILED++
    }
}

Write-Host "1. Checking Redis Connection..." -ForegroundColor Yellow
Write-Host "----------------------------"
$redisTest = docker exec redis redis-cli -a redis-password ping 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "Redis is running" -ForegroundColor Green
} else {
    Write-Host "Redis is not running. Start with: docker compose up -d redis" -ForegroundColor Red
}
Write-Host ""

Write-Host "2. Testing Redis Denylist Operations..." -ForegroundColor Yellow
Write-Host "--------------------------------------"

Run-Test "Set denylist entry" "docker exec redis redis-cli -a redis-password SET auth:denylist:test-jti-001 revoked 2>`$null"
Assert-Contains "Check denylist entry exists" "1" "docker exec redis redis-cli -a redis-password EXISTS auth:denylist:test-jti-001 2>`$null"
Run-Test "Delete denylist entry" "docker exec redis redis-cli -a redis-password DEL auth:denylist:test-jti-001 2>`$null"

Write-Host ""
Write-Host "3. Testing Redis Revoked Before..." -ForegroundColor Yellow
Write-Host "----------------------------------"

Run-Test "Set revoked_before key" "docker exec redis redis-cli -a redis-password SET auth:revoked_before:user-123 1716000000 2>`$null"
Assert-Contains "Check revoked_before key" "1716000000" "docker exec redis redis-cli -a redis-password GET auth:revoked_before:user-123 2>`$null"
Run-Test "Delete revoked_before key" "docker exec redis redis-cli -a redis-password DEL auth:revoked_before:user-123 2>`$null"

Write-Host ""
Write-Host "4. Testing Redis Pub/Sub Channel..." -ForegroundColor Yellow
Write-Host "-----------------------------------"

Run-Test "Publish to JWKS update channel" "docker exec redis redis-cli -a redis-password PUBLISH auth:jwks:update '{\'type\':\'all\'}' 2>`$null"

Write-Host ""
Write-Host "5. Checking Jaeger Connection..." -ForegroundColor Yellow
Write-Host "-------------------------------"

try {
    $response = Invoke-WebRequest -Uri "http://localhost:16686" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    Write-Host "Jaeger UI is accessible at http://localhost:16686" -ForegroundColor Green
} catch {
    Write-Host "Jaeger UI is not accessible (optional)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "6. Running gateway-auth Unit Tests..." -ForegroundColor Yellow
Write-Host "------------------------------------"

# JWT verification lives in go/services/gateway-auth (ForwardAuth), not in a Traefik plugin.
$gatewayAuth = (Resolve-Path (Join-Path $PSScriptRoot "..\..\gateway-auth")).Path
docker run --rm -v "${gatewayAuth}:/app" -w /app golang:1.25 go test ./...
if ($LASTEXITCODE -eq 0) {
    Write-Host "Unit tests passed" -ForegroundColor Green
} else {
    Write-Host "Unit tests failed" -ForegroundColor Red
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Passed: $PASSED" -ForegroundColor Green
Write-Host "Failed: $FAILED" -ForegroundColor Red
Write-Host ""

if ($FAILED -eq 0) {
    Write-Host "All tests passed!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "Some tests failed." -ForegroundColor Red
    exit 1
}

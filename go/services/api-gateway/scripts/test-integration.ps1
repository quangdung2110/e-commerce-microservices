# =============================================================================
# Integration Test Script for API Gateway + Auth Service
# =============================================================================
# This script tests the interaction between API Gateway and Auth Service.
# Prerequisites:
#   - Docker and Docker Compose installed
#   - Run from: go/services/api-gateway/
# =============================================================================

param(
    [switch]$SkipBuild,
    [switch]$KeepRunning,
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"
# Get the parent directory (go/services/api-gateway)
$PROJECT_ROOT = Split-Path -Parent $PSScriptRoot
$COMPOSE_FILE = Join-Path $PROJECT_ROOT "docker-compose.integrated.yml"

# Colors
function Write-Status { param($Message, $Color = "White")
    $colors = @{
        "Red" = [ConsoleColor]::Red
        "Green" = [ConsoleColor]::Green
        "Yellow" = [ConsoleColor]::Yellow
        "Cyan" = [ConsoleColor]::Cyan
        "White" = [ConsoleColor]::White
    }
    Write-Host $Message -ForegroundColor $colors[$Color]
}

Write-Status "`n========================================" "Cyan"
Write-Status "  API Gateway + Auth Service Test" "Cyan"
Write-Status "========================================`n" "Cyan"

# Check Docker
Write-Status "Checking Docker..." "Yellow"
try {
    $dockerVersion = docker --version 2>&1
    Write-Status "  $dockerVersion" "White"
} catch {
    Write-Status "  ERROR: Docker is not installed or not running!" "Red"
    exit 1
}

# Stop any existing containers
Write-Status "`nStopping existing containers..." "Yellow"
docker compose -f "$COMPOSE_FILE" down 2>&1 | Out-Null

# Build auth-service binary locally first
Write-Status "`nBuilding auth-service binary..." "Yellow"
$authServicePath = Join-Path $PROJECT_ROOT "..\auth-service"
Push-Location $authServicePath
try {
    & go build -ldflags="-w -s" -o auth-service ./cmd/server 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Status "  FAIL: Failed to build auth-service binary!" "Red"
        exit 1
    }
    Write-Status "  Auth-service binary built successfully!" "Green"
} finally {
    Pop-Location
}

# Binary already built in auth-service directory
Write-Status "  Binary exists at auth-service directory" "Green"

# Build images if not skipping
if (-not $SkipBuild) {
    Write-Status "`nBuilding Docker images..." "Yellow"
    # Build from auth-service directory (has context)
    Push-Location $authServicePath
    try {
        docker compose build
        if ($LASTEXITCODE -ne 0) {
            Write-Status "  ERROR: Build failed!" "Red"
            exit 1
        }
        Write-Status "  Build complete!" "Green"
    } finally {
        Pop-Location
    }
}

# Start services
Write-Status "`nStarting services..." "Yellow"
docker compose -f "$COMPOSE_FILE" up -d

# Wait for services to be ready
Write-Status "`nWaiting for services to be healthy..." "Yellow"

$maxWait = 120
$waited = 0
$interval = 5

while ($waited -lt $maxWait) {
    $authHealth = docker inspect auth-service --format='{{.State.Health.Status}}' 2>$null
    $traefikHealth = docker inspect api-gateway --format='{{.State.Health.Status}}' 2>$null

    if ($Verbose) {
        Write-Host "  Auth Service: $authHealth, Traefik: $traefikHealth ($waited/$maxWait s)" -ForegroundColor Gray
    }

    if ($authHealth -eq "healthy" -or $waited -gt 60) { break }
    Start-Sleep -Seconds $interval
    $waited += $interval
}

Write-Status "`n========================================" "Cyan"
Write-Status "  TEST RESULTS" "Cyan"
Write-Status "========================================`n" "Cyan"

$allPassed = $true

# Test 1: Health Check
Write-Status "[TEST 1] Health Check - Auth Service" "Yellow"
try {
    $health = Invoke-RestMethod -Uri "http://localhost:8081/health" -Method GET -TimeoutSec 5
    if ($health.status -eq "healthy") {
        Write-Status "  PASS: Auth Service is healthy" "Green"
    } else {
        Write-Status "  FAIL: Auth Service status: $($health.status)" "Red"
        $allPassed = $false
    }
} catch {
    Write-Status "  FAIL: Cannot reach auth service - $_" "Red"
    $allPassed = $false
}

# Test 2: JWKS Endpoint (Direct)
Write-Status "`n[TEST 2] JWKS Endpoint (Direct - Auth Service)" "Yellow"
try {
    $jwks = Invoke-RestMethod -Uri "http://localhost:8081/.well-known/jwks.json" -Method GET -TimeoutSec 5
    if ($jwks.keys -and $jwks.keys.Count -gt 0) {
        Write-Status "  PASS: JWKS endpoint returns $($jwks.keys.Count) key(s)" "Green"
        if ($Verbose) {
            Write-Host "  Keys: " -NoNewline
            $jwks.keys | ForEach-Object { Write-Host "$($_.kid) " -NoNewline }
            Write-Host ""
        }
    } else {
        Write-Status "  FAIL: JWKS has no keys" "Red"
        $allPassed = $false
    }
} catch {
    Write-Status "  FAIL: Cannot reach JWKS endpoint - $_" "Red"
    $allPassed = $false
}

# Test 3: JWKS Endpoint (Via Gateway)
Write-Status "`n[TEST 3] JWKS Endpoint (Via API Gateway)" "Yellow"
try {
    $gwJwks = Invoke-RestMethod -Uri "http://localhost/.well-known/jwks.json" -Method GET -TimeoutSec 5
    if ($gwJwks.keys -and $gwJwks.keys.Count -gt 0) {
        Write-Status "  PASS: Gateway routes to JWKS correctly" "Green"
    } else {
        Write-Status "  FAIL: Gateway JWKS has no keys" "Red"
        $allPassed = $false
    }
} catch {
    Write-Status "  FAIL: Gateway routing failed - $_" "Red"
    $allPassed = $false
}

# Test 4: Auth API Endpoint (Via Gateway)
Write-Status "`n[TEST 4] Auth API Endpoint (Via Gateway)" "Yellow"
try {
    $response = Invoke-RestMethod -Uri "http://localhost/api/v1/auth/login" -Method POST `
        -ContentType "application/json" `
        -Body '{"email":"test@example.com","password":"password123"}' `
        -TimeoutSec 5 `
        -ErrorAction SilentlyContinue

    if ($response) {
        Write-Status "  PASS: Auth API reachable via gateway" "Green"
    } else {
        Write-Status "  INFO: Auth API returned no data (expected for invalid credentials)" "Yellow"
    }
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -eq 400 -or $statusCode -eq 401) {
        Write-Status "  PASS: Auth API reachable (got HTTP $statusCode - expected)" "Green"
    } else {
        Write-Status "  FAIL: Auth API failed with HTTP $statusCode - $_" "Red"
        $allPassed = $false
    }
}

# Test 5: Gateway Health Check
Write-Status "`n[TEST 5] API Gateway Health Check" "Yellow"
try {
    $gwHealth = Invoke-RestMethod -Uri "http://localhost:8080/ping" -Method GET -TimeoutSec 5
    Write-Status "  PASS: Gateway is responding" "Green"
} catch {
    Write-Status "  FAIL: Gateway not responding - $_" "Red"
    $allPassed = $false
}

# Test 6: Gateway Dashboard
Write-Status "`n[TEST 6] Traefik Dashboard" "Yellow"
try {
    $dashboard = Invoke-WebRequest -Uri "http://localhost:8080/api/overview" -Method GET -TimeoutSec 5
    if ($dashboard.StatusCode -eq 200) {
        Write-Status "  PASS: Dashboard accessible" "Green"
    } else {
        Write-Status "  FAIL: Dashboard returned $($dashboard.StatusCode)" "Red"
        $allPassed = $false
    }
} catch {
    Write-Status "  FAIL: Dashboard not accessible - $_" "Red"
    $allPassed = $false
}

# Summary
Write-Status "`n========================================" "Cyan"
if ($allPassed) {
    Write-Status "  ALL TESTS PASSED!" "Green"
} else {
    Write-Status "  SOME TESTS FAILED!" "Red"
}
Write-Status "========================================`n" "Cyan"

# Container logs
Write-Status "Container Status:" "Yellow"
docker compose -f "$COMPOSE_FILE" ps

if (-not $KeepRunning) {
    Write-Status "`nStopping services..." "Yellow"
    docker compose -f "$COMPOSE_FILE" down
    Write-Status "Done.`n" "Green"
} else {
    Write-Status "`nServices are still running." "Yellow"
    Write-Status "To stop: docker compose -f "$COMPOSE_FILE" down" "White"
    Write-Status "To view logs: docker compose -f `"$COMPOSE_FILE`" logs -f`n" "White"
}

exit $(if ($allPassed) { 0 } else { 1 })

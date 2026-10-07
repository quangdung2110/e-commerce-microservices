[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")).Path
$composePath = Join-Path $repoRoot "docker-compose.yml"
$dynamicPath = Join-Path $repoRoot "go\services\api-gateway\config\dynamic.yml"
$controllerPath = Join-Path $repoRoot "java\services\product-service\src\main\java\com\platform\productservice\web\api"

$compose = Get-Content -Raw -LiteralPath $composePath
$dynamic = Get-Content -Raw -LiteralPath $dynamicPath

$ruleMatch = [regex]::Match(
    $compose,
    '(?m)^\s*- "traefik\.http\.routers\.product-service\.rule=(?<rule>.+)"$'
)
if (-not $ruleMatch.Success) {
    throw "Product Service router rule is missing from docker-compose.yml"
}

$rule = $ruleMatch.Groups['rule'].Value
$publicPrefixes = @(
    '/api/v1/products',
    '/api/v1/categories',
    '/api/v1/attributes'
)

foreach ($prefix in $publicPrefixes) {
    if (-not $rule.Contains("Path(``$prefix``)")) {
        throw "Router does not match the collection endpoint $prefix"
    }
    if (-not $rule.Contains("PathPrefix(``$prefix/``)")) {
        throw "Router does not match nested endpoints under $prefix/"
    }
}

if ($rule.Contains('/internal/')) {
    throw "Internal Product Service endpoints must not be exposed by the gateway"
}

$controllerMappings = Get-ChildItem -LiteralPath $controllerPath -Filter '*Controller.java' |
    ForEach-Object { Get-Content -Raw -LiteralPath $_.FullName } |
    ForEach-Object {
        [regex]::Matches($_, '@RequestMapping\("(?<path>/api/v1/[^"{]+)') |
            ForEach-Object { $_.Groups['path'].Value.TrimEnd('/') }
    } |
    Sort-Object -Unique

foreach ($mapping in $controllerMappings) {
    $covered = $false
    foreach ($prefix in $publicPrefixes) {
        if ($mapping -eq $prefix -or $mapping.StartsWith("$prefix/")) {
            $covered = $true
            break
        }
    }
    if (-not $covered) {
        throw "Controller mapping $mapping is not covered by the Product Service router"
    }
}

$requiredLabels = @(
    'traefik.http.routers.product-service.entrypoints=web',
    'traefik.http.routers.product-service.service=product-service',
    'traefik.http.services.product-service.loadbalancer.server.port=8081',
    'traefik.http.services.product-service.loadbalancer.healthcheck.path=/actuator/health',
    'traefik.http.services.product-service.loadbalancer.serverstransport=business-api@file'
)
foreach ($label in $requiredLabels) {
    if (-not $compose.Contains($label)) {
        throw "Missing Product Service gateway label: $label"
    }
}

$requiredMiddlewares = @(
    'strip-identity-headers',
    'dev-admin-auth',
    'rate-limit-api',
    'compress'
)
foreach ($middleware in $requiredMiddlewares) {
    if ($dynamic -notmatch "(?m)^    $([regex]::Escape($middleware)):") {
        throw "Middleware $middleware is referenced by the Product Service router but not defined"
    }
}

Write-Host "Product Service gateway routing is valid."
Write-Host "Covered controller bases: $($controllerMappings -join ', ')"
Write-Host "Internal /internal/v1/** endpoints remain unexposed."

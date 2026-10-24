param(
    [string[]]$Services = @('product-service','promotion-service','inventory-service','order-service','payment-service','search-service','review-service','product-detail-bff','checkout-service')
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$caches = @{
    'product-service'='product-mvn-repo'; 'promotion-service'='promotion-mvn-repo';
    'inventory-service'='inventory-mvn-repo'; 'order-service'='order-mvn-repo';
    'payment-service'='payment-mvn-repo'; 'search-service'='search-mvn-repo';
    'review-service'='review-mvn-repo'; 'product-detail-bff'='pdp-bff-mvn-repo';
    'checkout-service'='checkout-mvn-repo'
}
Push-Location $repo
try {
    $compose = docker compose --profile payment --profile notification config --format json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve Compose project' }
    foreach ($service in $Services) {
        if (-not $caches.ContainsKey($service)) { throw "Unknown Java service: $service" }
        Write-Output "Packaging $service"
        docker run --rm -v "${repo}:/repo" -v "$($caches[$service]):/root/.m2" `
            -e 'MAVEN_OPTS=-Xmx512m -XX:ActiveProcessorCount=2' `
            -w "/repo/java/services/$service" maven:3.9-eclipse-temurin-21 mvn -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "Maven failed: $service" }
        $source = Join-Path $repo "java/services/$service"
        $artifacts = @(Get-ChildItem (Join-Path $source 'target') -Filter '*.jar' | Where-Object Name -NotMatch '-(sources|javadoc|tests)\.jar$')
        if ($artifacts.Count -ne 1) { throw "Expected exactly one application JAR: $service" }
        # Reuse the repository's exact runtime stage; only replace its builder-stage JAR source.
        $dockerfile = Get-Content (Join-Path $source 'Dockerfile') -Raw
        $fromStages = [regex]::Matches($dockerfile, '(?m)^FROM ')
        $runtime = $dockerfile.Substring($fromStages[$fromStages.Count - 1].Index)
        $jarCopy = '(?m)^COPY --from=\S+\s+\S+\.jar\s+(\S+)\s*$'
        if ([regex]::Matches($runtime, $jarCopy).Count -ne 1) { throw "Unsupported runtime COPY: $service" }
        $runtime = [regex]::Replace($runtime, $jarCopy, 'COPY artifact.jar $1')
        $context = Join-Path $source 'target/observability-image'
        New-Item -ItemType Directory -Force $context | Out-Null
        Copy-Item -LiteralPath $artifacts[0].FullName -Destination (Join-Path $context 'artifact.jar') -Force
        [System.IO.File]::WriteAllText((Join-Path $context 'Dockerfile'), $runtime)
        $image = "$($compose.name)-${service}:latest"
        docker build --tag $image $context
        if ($LASTEXITCODE -ne 0) { throw "Image build failed: $service" }
        Write-Output "Built $image"
    }
} finally { Pop-Location }

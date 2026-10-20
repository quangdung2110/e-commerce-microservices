param(
    [int]$Seconds = 15,
    [ValidateSet("legacy", "adr028", "parallel")][string]$Variant = "parallel",
    [ValidateSet("full", "mixed", "paired")][string]$Profile = "full",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = "mvn.cmd"
)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
if (-not $JavaHome) {
    $bundled = Get-ChildItem (Join-Path $repo ".tools/jdk21") -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundled) { $JavaHome = $bundled.FullName }
}
if (-not (Get-Command $Maven -ErrorAction SilentlyContinue)) {
    $bundledMaven = Join-Path $repo ".tools/apache-maven-3.9.16/bin/mvn.cmd"
    if (Test-Path -LiteralPath $bundledMaven) { $Maven = $bundledMaven }
}
if (-not $JavaHome) { throw "Set JAVA_HOME to a Java 21 JDK." }
if ($Seconds -lt 5) { throw "Seconds must be >= 5." }
$env:JAVA_HOME = $JavaHome
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$project = "order-dispatch-bench-$stamp"
$compose = Join-Path $repo "loadtest/order-dispatch-bench/compose.yaml"
$output = Join-Path $repo "loadtest/order-dispatch-bench/results/$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$env:ORDER_DISPATCH_BENCH = "true"
$env:ORDER_BENCH_SECONDS = "$Seconds"
$env:ORDER_BENCH_VARIANT = $Variant
$env:ORDER_BENCH_PROFILE = $Profile
$env:ORDER_BENCH_OUTPUT = $output
$dbPort = if ($env:ORDER_BENCH_DB_PORT) { $env:ORDER_BENCH_DB_PORT } else { "25432" }
$temporalPort = if ($env:ORDER_BENCH_TEMPORAL_PORT) { $env:ORDER_BENCH_TEMPORAL_PORT } else { "27233" }
$env:ORDER_BENCH_DB_URL = "jdbc:postgresql://localhost:$dbPort/order_dispatch_bench"
$env:ORDER_BENCH_TEMPORAL_TARGET = "localhost:$temporalPort"
try {
    git -C $repo rev-parse HEAD | Set-Content (Join-Path $output "git-head.txt")
    git -C $repo status --short | Set-Content (Join-Path $output "git-status.txt")
    Get-FileHash (Join-Path $repo "java/services/order-service/src/main/java/com/ecommerce/order/worker/WorkflowDispatcher.java"),
        (Join-Path $repo "java/services/order-service/src/main/java/com/ecommerce/order/store/WorkflowOutboxStore.java"),
        (Join-Path $repo "java/services/order-service/src/main/java/com/ecommerce/order/worker/TemporalWorkflowGateway.java"),
        (Join-Path $repo "java/services/order-service/src/main/java/com/ecommerce/order/config/OrderProperties.java"),
        (Join-Path $repo "java/services/order-service/src/test/java/com/ecommerce/order/benchmark/baseline/LegacyWorkflowDispatcher.java"),
        (Join-Path $repo "java/services/order-service/src/test/java/com/ecommerce/order/benchmark/baseline/Adr028WorkflowDispatcher.java"),
        (Join-Path $repo "java/services/order-service/src/test/java/com/ecommerce/order/benchmark/baseline/LegacyWorkflowOutboxStore.java"),
        (Join-Path $repo "java/services/order-service/src/test/java/com/ecommerce/order/benchmark/WorkflowDispatcherBenchmarkTest.java") |
        Select-Object Path,Hash | ConvertTo-Json | Set-Content (Join-Path $output "source-hashes.json")
    docker ps --format '{{.Names}} {{.Image}}' | Set-Content (Join-Path $output "shared-containers-before.txt")
    docker info --format '{{.NCPU}} CPUs; {{.MemTotal}} bytes RAM' | Set-Content (Join-Path $output "docker-resources.txt")
    docker compose -p $project -f $compose up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) { throw "Benchmark infrastructure failed to start." }
    docker compose -p $project -f $compose images --format json | Set-Content (Join-Path $output "images.json")
    $monitor = Start-Job -ArgumentList $output,$project -ScriptBlock {
        param($directory,$composeProject)
        while ($true) {
            $containers = @(docker ps --filter "label=com.docker.compose.project=$composeProject" --format '{{.ID}}')
            if ($containers.Count -gt 0) {
                $now = (Get-Date).ToUniversalTime().ToString("o")
                docker stats --no-stream --format '{{json .}}' @containers | ForEach-Object {
                    $sample = $_ | ConvertFrom-Json
                    $sample | Add-Member -NotePropertyName utc -NotePropertyValue $now
                    $sample | ConvertTo-Json -Compress | Add-Content (Join-Path $directory "docker-stats.jsonl")
                }
            }
            Start-Sleep -Seconds 3
        }
    }
    # Windows PowerShell 5.1 turns merged native stderr (JVM warnings) into terminating errors under
    # "Stop"; the exit code below is the real verdict.
    $ErrorActionPreference = "Continue"
    & $Maven -B -f (Join-Path $repo "java/services/order-service/pom.xml") test `
        "-Dtest=WorkflowDispatcherBenchmarkTest" "-DargLine=-Xms256m -Xmx1024m" 2>&1 |
        ForEach-Object { "$_" } | Tee-Object -FilePath (Join-Path $output "maven.log")
    $mavenExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($mavenExit -ne 0) { throw "Benchmark failed; inspect $output/maven.log" }
    Copy-Item (Join-Path $repo "java/services/order-service/target/surefire-reports/*WorkflowDispatcherBenchmarkTest*") $output
    Write-Output "RESULTS: $output"
} finally {
    if ($monitor) { Stop-Job $monitor; Remove-Job $monitor }
    docker compose -p $project -f $compose logs --no-color 2>&1 | Set-Content (Join-Path $output "containers.log")
    docker compose -p $project -f $compose down -v --remove-orphans
    docker ps --format '{{.Names}} {{.Image}}' | Set-Content (Join-Path $output "shared-containers-after.txt")
    Remove-Item Env:ORDER_DISPATCH_BENCH -ErrorAction SilentlyContinue
    Remove-Item Env:ORDER_BENCH_VARIANT -ErrorAction SilentlyContinue
    Remove-Item Env:ORDER_BENCH_PROFILE -ErrorAction SilentlyContinue
}

param(
    [Parameter(Mandatory=$true)][string]$Jar,
    [Parameter(Mandatory=$true)][string]$Fixture,
    [Parameter(Mandatory=$true)][string]$Label,
    [switch]$ForceCards,
    [switch]$Lifecycle,
    [int[]]$Sizes = @(10000,60000),
    [int]$Rate = 30,
    [string]$Duration = '45s'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$project = 'search-read-model-bench'
$network = "${project}_default"
$compose = Join-Path $repo 'loadtest/search-bench/compose-read-model.yaml'
$result = Join-Path $repo "loadtest/search-bench/results/required-20261019/$Label"
New-Item -ItemType Directory -Force $result | Out-Null
Copy-Item -LiteralPath (Resolve-Path $Jar).Path -Destination "$result/search.jar"
$env:SEARCH_JAR = "$result/search.jar"
$env:SEARCH_FIXTURE = (Resolve-Path $Fixture).Path
$env:BENCH_CONFIG = if ($ForceCards) { '{"search":{"product-index":{"enabled":true}}}' } else { '{}' }
function Dc { & docker compose -p $project -f $compose @args; if ($LASTEXITCODE) { throw "compose failed: $args" } }
function RunPython([string]$file, [string[]]$params) {
    & docker run --rm --network $network -v "${repo}/loadtest/search-bench:/scripts:ro" -v "${out}:/out" -v "${repo}/java/services/search-service/src/main/resources/search/schema:/schema:ro" python:3.12-alpine python -I "/scripts/$file" @params
    if ($LASTEXITCODE) { throw "python failed: $file" }
}
function WaitSearch {
    for ($i=0; $i -lt 150; $i++) {
        & docker compose -p $project -f $compose exec -T search wget -qO- http://localhost:8080/api/v2/search/categories 2>$null | Out-Null
        if (!$LASTEXITCODE) { return }
        Start-Sleep -Seconds 2
    }
    throw 'Search did not start'
}
function WaitCards {
    $manifest = Get-Content "$out/manifest.json" -Raw | ConvertFrom-Json
    $query = "http://localhost:8080/api/v2/search/products?size=1&category=$($manifest.params.leaf_category)"
    for ($i=0; $i -lt 150; $i++) {
        & docker compose -p $project -f $compose exec -T search wget -qO- $query 2>$null | Out-Null
        $metrics = & docker compose -p $project -f $compose exec -T search wget -qO- http://localhost:8080/actuator/prometheus 2>$null
        if ($metrics | Select-String '^search_product_cards_ready 1') {
            Write-Output 'projection ready before warmup'
            return
        }
        Start-Sleep -Seconds 2
    }
    throw 'projection not ready within 300s'
}
function Load([string]$name, [string]$mix, [string]$duration) {
    $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    & docker run --rm --network $network -e "CASE=$name" -e "MIX=$mix" -e "RATE=$Rate" -e "DURATION=$duration" -e "EPOCH_MS=$epoch" -v "${repo}/loadtest/k6:/scripts:ro" -v "${out}:/out" grafana/k6:0.54.0 run --quiet --out "csv=/out/$name.csv.gz" /scripts/search-read-model.js *> "$out/$name-k6.log"
    if ($LASTEXITCODE) { throw "k6 failed: $name" }
}
@{label=$Label; jar_sha256=(Get-FileHash "$result/search.jar").Hash; fixture_sha256=(Get-FileHash $env:SEARCH_FIXTURE).Hash;
    git_head=(& git -C $repo rev-parse HEAD); config=$env:BENCH_CONFIG; sizes=$Sizes; rate=$Rate; duration=$Duration;
    harness_sha256=(Get-FileHash $PSCommandPath).Hash; load_script_sha256=(Get-FileHash "$repo/loadtest/k6/search-read-model.js").Hash;
    cutover_script_sha256=(Get-FileHash "$repo/loadtest/search-bench/cutover.py").Hash;
    compose_sha256=(Get-FileHash $compose).Hash;
    started_utc=[DateTime]::UtcNow.ToString('o'); docker=(& docker info --format '{{.NCPU}} {{.MemTotal}}');
    other_containers=(& docker ps --format '{{.Names}} {{.Image}}') } | ConvertTo-Json -Depth 5 | Set-Content "$result/environment.json"
try {
    Dc up -d --build --wait elasticsearch
    & docker inspect --format '{{.Config.Image}} {{.Image}} {{.HostConfig.NanoCpus}}' "$project-elasticsearch-1" > "$result/images.txt"
    foreach ($size in $Sizes) {
        $out = "$result/$size"
        New-Item -ItemType Directory -Force $out | Out-Null
        Dc stop search
        RunPython 'cutover.py' @('reset')
        RunPython 'seed.py' @('--es','http://elasticsearch:9200','--products',"$size",'--schema-dir','/schema','--out','/out')
        Dc up -d --force-recreate search
        WaitSearch
        if ($ForceCards -or $Lifecycle) { WaitCards }
        $stats = Start-Job -ArgumentList "$out/docker-stats.jsonl" -ScriptBlock {
            param($dest)
            while ($true) {
                $at = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                docker stats --no-stream --format '{{json .}}' | ForEach-Object { @{at_ms=$at; stats=($_ | ConvertFrom-Json)} | ConvertTo-Json -Compress | Add-Content $dest }
                Start-Sleep -Seconds 2
            }
        }
        try {
            Load 'warmup' 'home' '30s'
            Load 'home' 'home' $Duration
            Load 'browse' 'browse' $Duration
            if ($Lifecycle) {
                RunPython 'cutover.py' @('prepare')
                & docker run -d --name "$project-trigger" --network $network -v "${repo}/loadtest/search-bench:/scripts:ro" -v "${out}:/out" python:3.12-alpine python -I /scripts/cutover.py trigger | Out-Null
                if ($LASTEXITCODE) { throw 'trigger container failed' }
                Load 'cutover-home' 'home' '90s'
                & docker wait "$project-trigger" | Set-Content "$out/trigger-exit.txt"
                & docker logs "$project-trigger" *> "$out/trigger.log"
                & docker rm "$project-trigger" | Out-Null
                if ((Get-Content "$out/trigger-exit.txt") -ne '0') { throw 'cutover failed; see trigger.log' }
            }
            & docker compose -p $project -f $compose exec -T search wget -qO- http://localhost:8080/actuator/prometheus > "$out/metrics.prom"
            & docker compose -p $project -f $compose logs --no-color search > "$out/search.log"
            Write-Output "completed $Label $size"
        } finally { Stop-Job $stats; Remove-Job $stats -Force }
    }
} finally {
    Dc down -v --remove-orphans
    Remove-Item -LiteralPath "$result/search.jar"
}

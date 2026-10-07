#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('payment', 'order', 'inventory')]
    [string[]]$Services = @('payment', 'order', 'inventory'),
    [uri]$ConnectUrl = 'http://localhost:8084',
    [string]$DatabaseHost = 'postgres',
    [ValidateRange(1, 65535)][int]$DatabasePort = 5432,
    [string]$DatabaseUsername = 'postgres',
    [string]$DatabasePassword = 'postgres',
    [ValidateRange(1, 600)][int]$TimeoutSeconds = 90,
    [switch]$ValidateOnly,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
if ($ValidateOnly -and $CheckOnly) { throw 'Choose either ValidateOnly or CheckOnly.' }
if ($ConnectUrl.Scheme -notin @('http', 'https') -or $ConnectUrl.UserInfo -or $ConnectUrl.Query -or $ConnectUrl.Fragment) {
    throw 'ConnectUrl must be an HTTP(S) URL without credentials, query or fragment.'
}
if (-not $Services.Count -or [string]::IsNullOrWhiteSpace($DatabaseHost) -or [string]::IsNullOrWhiteSpace($DatabaseUsername)) {
    throw 'Services, DatabaseHost and DatabaseUsername must be non-empty.'
}
$repo = Split-Path $PSScriptRoot -Parent
$baseUrl = $ConnectUrl.AbsoluteUri.TrimEnd('/')
foreach ($service in ($Services | ForEach-Object { $_.ToLowerInvariant() } | Select-Object -Unique)) {
    $path = Join-Path $repo "java/services/$service-service/src/main/resources/kafka/debezium-connector-$service-outbox.json"
    $connector = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
    if ($connector.name -ne "$service-outbox-connector" -or -not $connector.config) {
        throw "Unexpected connector artifact for $service."
    }
    $config = $connector.config
    # Resolve only DB fields. EventRouter expressions such as ${routedByValue}
    # must remain literal. DB_NAME must not accidentally select one DB for all services.
    $config['database.hostname'] = $DatabaseHost
    $config['database.port'] = [string]$DatabasePort
    $config['database.user'] = $DatabaseUsername
    $config['database.password'] = $DatabasePassword
    $config['database.dbname'] = "${service}_service"
    $name = $connector.name
    if ($ValidateOnly) {
        Write-Output "$name`: template valid; database=${service}_service (no request sent)."
        continue
    }
    if (-not $CheckOnly) {
        try {
            $null = Invoke-RestMethod -Method Put -Uri "$baseUrl/connectors/$name/config" -ContentType 'application/json' -Body ($config | ConvertTo-Json -Depth 30 -Compress) -TimeoutSec 10
        } catch {
            # Connect errors may echo the submitted config, including DB credentials.
            throw "Registration failed for $name. Inspect Kafka Connect logs locally; config is not printed."
        }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $status = $null
        try {
            $status = Invoke-RestMethod -Method Get -Uri "$baseUrl/connectors/$name/status" -TimeoutSec 5
        } catch { }
        if ($status) {
            $tasks = @($status.tasks)
            if ($status.connector.state -eq 'FAILED' -or @($tasks | Where-Object state -eq 'FAILED').Count) {
                throw "$name has a FAILED connector/task. Inspect Kafka Connect logs locally."
            }
            if ($status.connector.state -eq 'RUNNING' -and $tasks.Count -gt 0 -and @($tasks | Where-Object state -ne 'RUNNING').Count -eq 0) {
                Write-Output "$name`: connector and $($tasks.Count) task(s) RUNNING."
                break
            }
        }
        if ([DateTime]::UtcNow -ge $deadline) { throw "Timed out waiting for all tasks of $name to be RUNNING." }
        Start-Sleep -Seconds 2
    } while ($true)
}

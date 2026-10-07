#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$runner = Join-Path (Split-Path $PSScriptRoot -Parent) 'register-checkout-connectors.ps1'
$requests = [System.Collections.Generic.List[object]]::new()
$taskState = 'RUNNING'
function Invoke-RestMethod {
    param($Method, $Uri, $ContentType, $Body, $TimeoutSec)
    $requests.Add(@{ Method = $Method; Uri = $Uri; Body = $Body })
    if ($Method -eq 'Get') {
        return @{ connector = @{ state = 'RUNNING' }; tasks = @(@{ id = 0; state = $taskState }) }
    }
}
function Assert-True($condition, $message) { if (-not $condition) { throw $message } }
try {
    $null = & $runner -DatabasePassword 'test-secret-do-not-print'
    $puts = @($requests | Where-Object Method -eq 'Put')
    Assert-True ($puts.Count -eq 3) 'Expected all three connectors.'
    foreach ($service in @('payment', 'order', 'inventory')) {
        $request = $puts | Where-Object Uri -eq "http://localhost:8084/connectors/$service-outbox-connector/config"
        $body = $request.Body | ConvertFrom-Json -AsHashtable
        Assert-True ($body['database.dbname'] -eq "${service}_service") 'Database isolation was lost.'
        Assert-True ($body['database.password'] -eq 'test-secret-do-not-print') 'Credential override was lost.'
        Assert-True ($body['database.hostname'] -eq 'postgres') 'DB placeholder was not resolved.'
        $template = Get-Content (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) "java/services/$service-service/src/main/resources/kafka/debezium-connector-$service-outbox.json") -Raw | ConvertFrom-Json -AsHashtable
        foreach ($key in $template.config.Keys | Where-Object { $_ -notlike 'database.*' }) {
            Assert-True ($body[$key] -ceq $template.config[$key]) "Routing/CDC field changed: $key"
        }
    }
    $requests.Clear()
    $null = & $runner -Services PAYMENT, payment
    $puts = @($requests | Where-Object Method -eq 'Put')
    Assert-True ($puts.Count -eq 1) 'Service aliases must normalize before deduplication.'
    $normalized = $puts[0].Body | ConvertFrom-Json -AsHashtable
    Assert-True ($normalized['database.dbname'] -ceq 'payment_service') 'Uppercase service selected the wrong database.'
    $requests.Clear()
    $null = & $runner -ValidateOnly
    Assert-True ($requests.Count -eq 0) 'ValidateOnly sent a request.'
    $null = & $runner -CheckOnly -Services payment
    Assert-True (@($requests | Where-Object Method -eq 'Put').Count -eq 0) 'CheckOnly mutated a connector.'
    $taskState = 'FAILED'
    $failed = $false
    try { $null = & $runner -Services payment -CheckOnly } catch { $failed = $_.Exception.Message -match 'FAILED' }
    Assert-True $failed 'A RUNNING connector with a FAILED task must fail validation.'
    Write-Output 'PASS: isolated databases, routing preserved, validation/check modes, FAILED task rejection.'
} finally {
    Remove-Item Function:Invoke-RestMethod
}

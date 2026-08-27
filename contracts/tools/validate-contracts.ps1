[CmdletBinding()]
param(
    [switch]$StrictTools
)

$ErrorActionPreference = 'Stop'
$contractRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $contractRoot '..')).Path
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Add-ContractError {
    param([string]$Message)
    $script:errors.Add($Message)
}

function Add-ContractWarning {
    param([string]$Message)
    $script:warnings.Add($Message)
}

function Get-RelativeDisplayPath {
    param([string]$Path)
    return [System.IO.Path]::GetRelativePath($repoRoot, $Path).Replace('\', '/')
}

function Invoke-OptionalTool {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        $message = "Optional tool '$Name' is not installed."
        if ($StrictTools) {
            Add-ContractError $message
        } else {
            Add-ContractWarning $message
        }
        return
    }

    try {
        & $Action
        if ($LASTEXITCODE -ne 0) {
            Add-ContractError "Tool '$Name' exited with code $LASTEXITCODE."
        }
    } catch {
        Add-ContractError "Tool '$Name' failed: $($_.Exception.Message)"
    }
}

Write-Host 'Validating JSON files...'
Get-ChildItem $contractRoot -Recurse -File -Filter '*.json' | ForEach-Object {
    try {
        $null = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json -Depth 100
    } catch {
        Add-ContractError "Invalid JSON in $(Get-RelativeDisplayPath $_.FullName): $($_.Exception.Message)"
    }
}

Write-Host 'Checking OpenAPI and AsyncAPI markers...'
Get-ChildItem (Join-Path $contractRoot 'http') -Recurse -File -Filter '*.yaml' | ForEach-Object {
    $content = Get-Content -LiteralPath $_.FullName -Raw
    if ($content -notmatch '(?m)^openapi:\s+3\.1\.') {
        Add-ContractError "OpenAPI spec must use 3.1.x: $(Get-RelativeDisplayPath $_.FullName)"
    }
    if ($content -notmatch '(?m)^x-adoption-status:\s+(proposed|implemented|verified)\s*$') {
        Add-ContractError "OpenAPI spec is missing x-adoption-status: $(Get-RelativeDisplayPath $_.FullName)"
    }
}

Get-ChildItem (Join-Path $contractRoot 'events') -Recurse -File -Filter 'asyncapi.yaml' | ForEach-Object {
    $content = Get-Content -LiteralPath $_.FullName -Raw
    if ($content -notmatch '(?m)^asyncapi:\s+3\.[01]\.\d+\s*$') {
        Add-ContractError "AsyncAPI spec must use 3.0.x or 3.1.x: $(Get-RelativeDisplayPath $_.FullName)"
    }
    if ($content -notmatch '(?m)^x-adoption-status:\s+(proposed|implemented|verified)\s*$') {
        Add-ContractError "AsyncAPI spec is missing x-adoption-status: $(Get-RelativeDisplayPath $_.FullName)"
    }
}

Write-Host 'Checking Protobuf conventions...'
$protoRoots = @(
    (Join-Path $contractRoot 'grpc'),
    (Join-Path $contractRoot 'shared/proto')
)
foreach ($protoRoot in $protoRoots) {
    Get-ChildItem $protoRoot -Recurse -File -Filter '*.proto' | ForEach-Object {
        $content = Get-Content -LiteralPath $_.FullName -Raw
        if ($content -notmatch '(?m)^syntax\s*=\s*"proto3";') {
            Add-ContractError "Proto must declare proto3: $(Get-RelativeDisplayPath $_.FullName)"
        }
        if ($content -notmatch '(?m)^package\s+ecommerce\.[a-z0-9_.]+\.v[1-9][0-9]*;') {
            Add-ContractError "Proto package must be versioned: $(Get-RelativeDisplayPath $_.FullName)"
        }
        if ($content -notmatch '(?m)^option\s+go_package\s*=') {
            Add-ContractError "Proto is missing go_package: $(Get-RelativeDisplayPath $_.FullName)"
        }
        if ($content -notmatch '(?m)^option\s+java_package\s*=') {
            Add-ContractError "Proto is missing java_package: $(Get-RelativeDisplayPath $_.FullName)"
        }
    }
}

Write-Host 'Checking catalogs...'
$servicesFile = Join-Path $contractRoot 'catalog/services.yaml'
$interactionsFile = Join-Path $contractRoot 'catalog/interactions.yaml'
$servicesContent = Get-Content -LiteralPath $servicesFile -Raw
$interactionContent = Get-Content -LiteralPath $interactionsFile -Raw

$serviceIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
[regex]::Matches($servicesContent, '(?m)^\s{2}- id:\s*([a-z0-9-]+)\s*$') | ForEach-Object {
    if (-not $serviceIds.Add($_.Groups[1].Value)) {
        Add-ContractError "Duplicate service id: $($_.Groups[1].Value)"
    }
}

[regex]::Matches($interactionContent, '(?m)^\s{4}(?:provider):\s*([a-z0-9-]+)\s*$') | ForEach-Object {
    $id = $_.Groups[1].Value
    if (-not $serviceIds.Contains($id)) {
        Add-ContractError "Interaction references unknown provider: $id"
    }
}

[regex]::Matches($interactionContent, '(?m)^\s{4}consumers:\s*\[([^\]]*)\]\s*$') | ForEach-Object {
    $_.Groups[1].Value.Split(',') | ForEach-Object {
        $id = $_.Trim()
        if ($id -and -not $serviceIds.Contains($id)) {
            Add-ContractError "Interaction references unknown consumer: $id"
        }
    }
}

[regex]::Matches($interactionContent, '(?m)^\s{4}contract:\s*([^\r\n]+)\s*$') | ForEach-Object {
    $contract = $_.Groups[1].Value.Trim().Trim('"', "'")
    if ($contract -ne 'null') {
        $target = Join-Path $contractRoot $contract
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            Add-ContractError "Catalog contract does not exist: $contract"
        }
    }
}

[regex]::Matches($servicesContent, '(?m)^\s{4}docs:\s*([^\r\n]+)\s*$') | ForEach-Object {
    $doc = $_.Groups[1].Value.Trim().Trim('"', "'")
    $target = Join-Path $repoRoot $doc
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        Add-ContractError "Catalog documentation does not exist: $doc"
    }
}

Write-Host 'Checking CloudEvents examples...'
$requiredCloudEventFields = @(
    'specversion', 'id', 'type', 'source', 'subject', 'time',
    'datacontenttype', 'dataschema', 'data'
)
Get-ChildItem (Join-Path $contractRoot 'examples/events') -File -Filter '*.json' | ForEach-Object {
    try {
        $event = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json -Depth 100
        foreach ($field in $requiredCloudEventFields) {
            if ($null -eq $event.PSObject.Properties[$field]) {
                Add-ContractError "CloudEvent example is missing '$field': $(Get-RelativeDisplayPath $_.FullName)"
            }
        }
        if ($event.specversion -ne '1.0') {
            Add-ContractError "CloudEvent specversion must be 1.0: $(Get-RelativeDisplayPath $_.FullName)"
        }
        if ($event.type -notmatch '^com\.ecommerce\..+\.v[1-9][0-9]*$') {
            Add-ContractError "CloudEvent type must be versioned: $(Get-RelativeDisplayPath $_.FullName)"
        }
        if ($event.type -eq 'com.ecommerce.cart.item-added.v1') {
            $requiredDataFields = @(
                'cart_id', 'user_id', 'sku_id', 'product_id', 'quantity',
                'unit_price_at_add', 'product_name', 'is_available',
                'added_at', 'updated_at'
            )
            foreach ($field in $requiredDataFields) {
                if ($null -eq $event.data.PSObject.Properties[$field]) {
                    Add-ContractError "Cart item event data is missing '$field': $(Get-RelativeDisplayPath $_.FullName)"
                }
            }
            $moneyJson = $event.data.unit_price_at_add | ConvertTo-Json -Depth 10 -Compress
            $moneySchema = Join-Path $contractRoot 'shared/json-schema/v1/money.schema.json'
            if (-not (Test-Json -Json $moneyJson -SchemaFile $moneySchema -ErrorAction SilentlyContinue)) {
                Add-ContractError "Cart item event contains invalid Money: $(Get-RelativeDisplayPath $_.FullName)"
            }
            $eventSchema = Join-Path $contractRoot 'events/cart/v1/schemas/cart-item-added.schema.json'
            $eventJson = Get-Content -LiteralPath $_.FullName -Raw
            if (-not (Test-Json -Json $eventJson -SchemaFile $eventSchema -ErrorAction SilentlyContinue)) {
                Add-ContractError "Cart item event does not match its JSON Schema: $(Get-RelativeDisplayPath $_.FullName)"
            }
        }
    } catch {
        Add-ContractError "Cannot inspect CloudEvent example $(Get-RelativeDisplayPath $_.FullName): $($_.Exception.Message)"
    }
}

Write-Host 'Checking Markdown links in contracts...'
$linkPattern = '\[[^\]]+\]\(([^)]+)\)'
Get-ChildItem $contractRoot -Recurse -File -Filter '*.md' | ForEach-Object {
    $file = $_
    $content = Get-Content -LiteralPath $file.FullName -Raw
    [regex]::Matches($content, $linkPattern) | ForEach-Object {
        $link = $_.Groups[1].Value.Trim()
        if ($link -match '^(https?://|mailto:|#)') { return }
        $pathPart = ($link -split '#', 2)[0]
        if ([string]::IsNullOrWhiteSpace($pathPart)) { return }
        $target = Join-Path $file.DirectoryName ([uri]::UnescapeDataString($pathPart))
        if (-not (Test-Path -LiteralPath $target)) {
            Add-ContractError "Broken Markdown link '$link' in $(Get-RelativeDisplayPath $file.FullName)"
        }
    }
}

Write-Host 'Running optional semantic linters...'
Invoke-OptionalTool -Name 'buf' -Action {
    Push-Location $contractRoot
    try { & buf lint } finally { Pop-Location }
}
Invoke-OptionalTool -Name 'redocly' -Action {
    & redocly lint (Join-Path $contractRoot 'http/cart/v1/openapi.yaml')
}
Invoke-OptionalTool -Name 'asyncapi' -Action {
    & asyncapi validate (Join-Path $contractRoot 'events/cart/v1/asyncapi.yaml')
}

foreach ($warning in $warnings) {
    Write-Warning $warning
}

if ($errors.Count -gt 0) {
    Write-Host "Contract validation failed with $($errors.Count) error(s):" -ForegroundColor Red
    foreach ($validationError in $errors) {
        Write-Host "  - $validationError" -ForegroundColor Red
    }
    exit 1
}

Write-Host 'Contract validation passed.' -ForegroundColor Green

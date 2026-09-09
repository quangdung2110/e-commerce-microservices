[CmdletBinding()]
param(
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'

$contractRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $contractRoot '..')).Path
$fixturePath = Join-Path $contractRoot 'examples/events/auth-user-deleted.v1.json'
$authCommonSchemaPath = Join-Path $contractRoot 'events/auth/v1/schemas/auth-event-common.schema.json'
$reviewSchemaPath = Join-Path $repoRoot 'docs/review/schema/0001_initial_review_schema.sql'
$testDatabase = 'review_contract_auth_user_deleted'

function Assert-Contract {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw "Contract assertion failed: $Message"
    }
}

function Test-Uuid {
    param([string]$Value)

    $parsed = [guid]::Empty
    return [guid]::TryParse($Value, [ref]$parsed)
}

function Invoke-ComposePsql {
    param(
        [string]$Database,
        [string]$Sql
    )

    $Sql | & docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d $Database
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed for database '$Database' with exit code $LASTEXITCODE."
    }
}

Assert-Contract (Test-Path -LiteralPath $fixturePath -PathType Leaf) "Missing fixture: $fixturePath"
Assert-Contract (Test-Path -LiteralPath $authCommonSchemaPath -PathType Leaf) "Missing Auth common schema: $authCommonSchemaPath"
Assert-Contract (Test-Path -LiteralPath $reviewSchemaPath -PathType Leaf) "Missing Review schema: $reviewSchemaPath"

$authCommonSchema = Get-Content -LiteralPath $authCommonSchemaPath -Raw | ConvertFrom-Json -Depth 100
$idSchema = $authCommonSchema.allOf[1].properties.id
$uuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
Assert-Contract ($null -ne $idSchema) 'Auth common envelope must constrain CloudEvents.id.'
Assert-Contract ($idSchema.type -eq 'string') 'Auth common envelope id must be a string.'
Assert-Contract ($idSchema.format -eq 'uuid') 'Auth common envelope id must use UUID format.'
Assert-Contract ($idSchema.pattern -eq $uuidPattern) 'Auth common envelope id must assert UUID syntax even when format is annotation-only.'

$event = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json -Depth 100
foreach ($field in @('specversion', 'id', 'type', 'source', 'subject', 'time', 'datacontenttype', 'dataschema', 'data')) {
    Assert-Contract ($null -ne $event.PSObject.Properties[$field]) "Fixture is missing CloudEvents field '$field'."
}

Assert-Contract ($event.specversion -eq '1.0') 'Fixture must use CloudEvents 1.0.'
Assert-Contract ($event.type -eq 'com.ecommerce.auth.user-deleted.v1') 'Fixture must be Auth USER_DELETED v1.'
Assert-Contract ($event.source -eq '/services/auth') 'Fixture source must be Auth.'
Assert-Contract ($event.dataschema -eq '/contracts/events/auth/v1/schemas/user-deleted.schema.json') 'Fixture must point to the Auth USER_DELETED schema.'
Assert-Contract (Test-Uuid $event.id) 'Fixture CloudEvents.id must be a UUID.'
Assert-Contract ($event.id -match $idSchema.pattern) 'Fixture CloudEvents.id must satisfy the Auth UUID assertion.'
Assert-Contract ('not-a-uuid' -notmatch $idSchema.pattern) 'Auth UUID assertion must reject a non-UUID event id.'
Assert-Contract (Test-Uuid $event.correlationid) 'Fixture correlationid must be a UUID.'
Assert-Contract (Test-Uuid $event.data.user_id) 'Fixture data.user_id must be a UUID.'
Assert-Contract ($event.subject -eq "accounts/$($event.data.user_id)") 'Fixture subject must identify the event user.'
Assert-Contract ($event.data.reason -in @('GDPR_REQUEST', 'ADMIN_DELETION')) 'Fixture deletion reason is outside the Auth contract.'

$eventId = ([guid]$event.id).ToString()
$userId = ([guid]$event.data.user_id).ToString()
$deletedAt = [DateTimeOffset]::Parse($event.data.deleted_at).ToUniversalTime()
$hardDeleteAfter = [DateTimeOffset]::Parse($event.data.hard_delete_after).ToUniversalTime()
Assert-Contract ($hardDeleteAfter -ge $deletedAt) 'Fixture hard_delete_after must not precede deleted_at.'

Write-Host "PASS static Auth USER_DELETED contract checks: $fixturePath"
if ($ValidateOnly) {
    return
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is required for the persistence phase. Start the compose PostgreSQL service, then rerun this script.'
}

Push-Location $repoRoot
try {
    & docker compose version
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose is unavailable (exit code $LASTEXITCODE)."
    }

    # This test owns only this fixed disposable database. It never touches review_service.
    $databaseCreated = $false
    try {
        Invoke-ComposePsql -Database 'postgres' -Sql "DROP DATABASE IF EXISTS $testDatabase WITH (FORCE);"
        Invoke-ComposePsql -Database 'postgres' -Sql "CREATE DATABASE $testDatabase;"
        $databaseCreated = $true

        $reviewSchemaSql = Get-Content -LiteralPath $reviewSchemaPath -Raw
        Invoke-ComposePsql -Database $testDatabase -Sql $reviewSchemaSql

        $persistenceSql = @"
BEGIN;

INSERT INTO review.review_event_receipts
    (consumer_name, event_id, source_topic, source_partition, source_offset)
VALUES
    ('privacy-sync', '$eventId'::uuid, 't.auth.events', 0, 42);

INSERT INTO review.privacy_erasure_requests
    (source_event_id, subject_user_id, subject_match_key, subject_key_version,
     deletion_reason, deleted_at, hard_delete_after)
VALUES
    ('$eventId'::uuid, '$userId'::uuid,
     public.hmac('review-privacy-guard:v1:$userId',
                 'review-contract-test-privacy-guard-secret', 'sha256'),
     1, '$($event.data.reason)', '$($deletedAt.ToString('o'))'::timestamptz,
     '$($hardDeleteAfter.ToString('o'))'::timestamptz);

DO `$`$
DECLARE
    receipt_count integer;
    request_count integer;
BEGIN
    SELECT count(*) INTO receipt_count
    FROM review.review_event_receipts
    WHERE consumer_name = 'privacy-sync'
      AND event_id = '$eventId'::uuid;

    SELECT count(*) INTO request_count
    FROM review.privacy_erasure_requests
    WHERE source_event_id = '$eventId'::uuid
      AND subject_user_id = '$userId'::uuid
      AND deletion_reason = '$($event.data.reason)'
      AND deleted_at = '$($deletedAt.ToString('o'))'::timestamptz
      AND hard_delete_after = '$($hardDeleteAfter.ToString('o'))'::timestamptz;

    IF receipt_count <> 1 OR request_count <> 1 THEN
        RAISE EXCEPTION
            'Expected one Review receipt and one privacy request for Auth event %, got receipt=% request=%',
            '$eventId', receipt_count, request_count;
    END IF;
END
`$`$;

COMMIT;
"@
        Invoke-ComposePsql -Database $testDatabase -Sql $persistenceSql
        Write-Host "PASS Auth USER_DELETED inserted one Review receipt and privacy request in $testDatabase."
    } finally {
        if ($databaseCreated) {
            Invoke-ComposePsql -Database 'postgres' -Sql "DROP DATABASE IF EXISTS $testDatabase WITH (FORCE);"
        }
    }
} finally {
    Pop-Location
}

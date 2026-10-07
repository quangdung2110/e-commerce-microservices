[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$errors = [System.Collections.Generic.List[string]]::new()
function Fail([string]$message) { $errors.Add($message) }
function Read-Repo([string]$path) { Get-Content -Raw -LiteralPath (Join-Path $repoRoot $path) }
function Assert-Contains([string]$text, [string]$value, [string]$context) {
    if (-not $text.Contains($value)) { Fail "$context is missing: $value" }
}

$events = @(
    @{ File='payment-initiated'; Schema='payment-initiated'; Type='com.ecommerce.payment.transaction.initiated.v2'; Order=$false },
    @{ File='payment-succeeded'; Schema='payment-succeeded'; Type='com.ecommerce.payment.transaction.succeeded.v2'; Order=$true },
    @{ File='payment-failed'; Schema='payment-failed'; Type='com.ecommerce.payment.transaction.failed.v2'; Order=$true },
    @{ File='payment-expired'; Schema='payment-expired'; Type='com.ecommerce.payment.transaction.expired.v2'; Order=$true },
    @{ File='payment-canceled'; Schema='payment-canceled'; Type='com.ecommerce.payment.transaction.canceled.v2'; Order=$true },
    @{ File='payment-refund-initiated'; Schema='refund-initiated'; Type='com.ecommerce.payment.refund.initiated.v2'; Order=$false },
    @{ File='payment-refund-processing'; Schema='refund-processing'; Type='com.ecommerce.payment.refund.processing.v2'; Order=$true },
    @{ File='payment-refund-succeeded'; Schema='refund-succeeded'; Type='com.ecommerce.payment.refund.succeeded.v2'; Order=$true },
    @{ File='payment-refund-failed'; Schema='refund-failed'; Type='com.ecommerce.payment.refund.failed.v2'; Order=$true },
    @{ File='payment-refund-reversed'; Schema='refund-reversed'; Type='com.ecommerce.payment.refund.reversed.v2'; Order=$true },
    @{ File='payment-dispute-opened'; Schema='dispute-opened'; Type='com.ecommerce.payment.dispute.opened.v2'; Order=$true },
    @{ File='payment-dispute-funds-withdrawn'; Schema='dispute-funds-withdrawn'; Type='com.ecommerce.payment.dispute.funds-withdrawn.v2'; Order=$true },
    @{ File='payment-dispute-funds-reinstated'; Schema='dispute-funds-reinstated'; Type='com.ecommerce.payment.dispute.funds-reinstated.v2'; Order=$true },
    @{ File='payment-dispute-won'; Schema='dispute-won'; Type='com.ecommerce.payment.dispute.won.v2'; Order=$true },
    @{ File='payment-dispute-lost'; Schema='dispute-lost'; Type='com.ecommerce.payment.dispute.lost.v2'; Order=$true }
)

$paymentAsyncApi = Read-Repo 'contracts/events/payment/v2/asyncapi.yaml'
$orderAsyncApi = Read-Repo 'contracts/events/order/v1/asyncapi.yaml'
foreach ($event in $events) {
    $schemaPath = "contracts/events/payment/v2/schemas/$($event.Schema).schema.json"
    $examplePath = "contracts/examples/events/$($event.File).v2.json"
    try { $schema = (Read-Repo $schemaPath) | ConvertFrom-Json -Depth 100 }
    catch { Fail "Invalid JSON schema $schemaPath`: $($_.Exception.Message)"; continue }
    try { $example = (Read-Repo $examplePath) | ConvertFrom-Json -Depth 100 }
    catch { Fail "Invalid JSON example $examplePath`: $($_.Exception.Message)"; continue }
    $schemaType = $schema.allOf[1].properties.type.const
    $schemaData = $schema.allOf[1].properties.dataschema.const
    $expectedData = "/contracts/events/payment/v2/schemas/$($event.Schema).schema.json"
    if ($schemaType -ne $event.Type) { Fail "$schemaPath type differs from canonical event type" }
    if ($schemaData -ne $expectedData) { Fail "$schemaPath dataschema differs from canonical path" }
    if ($example.type -ne $event.Type) { Fail "$examplePath type differs from schema" }
    if ($example.dataschema -ne $expectedData) { Fail "$examplePath dataschema differs from schema" }
    if ($example.specversion -ne '1.0' -or $example.source -ne '/services/payment' -or
        $example.data.schema_version -ne 2) { Fail "$examplePath is not a canonical v2 structured CloudEvent" }
    Assert-Contains $paymentAsyncApi "x-event-type: $($event.Type)" 'Payment AsyncAPI'
    Assert-Contains $paymentAsyncApi "./schemas/$($event.Schema).schema.json" 'Payment AsyncAPI'
    Assert-Contains $paymentAsyncApi "../../../examples/events/$($event.File).v2.json" 'Payment AsyncAPI'
    if ($event.Order) { Assert-Contains $orderAsyncApi "- $($event.Type)" 'Order accepted event types' }
}

$orderAccepted = [regex]::Matches($orderAsyncApi, '(?m)^\s+- (com\.ecommerce\.payment\.[^\s]+\.v2)\s*$') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$expectedOrder = $events | Where-Object Order | ForEach-Object Type | Sort-Object
if (@($orderAccepted).Count -ne 13 -or (Compare-Object $expectedOrder $orderAccepted)) {
    Fail 'Order must accept exactly the 13 relevant Payment facts.'
}

$migrationPath = 'java/services/payment-service/src/main/resources/db/migration/V1__payment_baseline.sql'
$migration = Read-Repo $migrationPath
$expectedTables = @('payment_transactions','refund_transactions','payment_disputes',
    'provider_operations','webhook_logs','payment_status_history','outbox_events',
    'payment_idempotency','payment_review_cases','reconciliation_runs',
    'financial_journals','financial_ledger_entries')
$actualTables = [regex]::Matches($migration, '(?im)^CREATE TABLE\s+([a-z_]+)\s*\(') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
if (@($actualTables).Count -ne 12 -or (Compare-Object ($expectedTables | Sort-Object) $actualTables)) {
    Fail 'Baseline migration must create exactly the 12 canonical Payment tables.'
}
@('CONSTRAINT uq_refund_order UNIQUE (order_id)', "'WEBHOOK_REPLAY','RECONCILE','FREEZE'",
  'safe_replay_until TIMESTAMPTZ',
  'CREATE CONSTRAINT TRIGGER ck_financial_journal_balanced_header',
  'CREATE CONSTRAINT TRIGGER ck_financial_journal_balanced_lines',
  'CREATE OR REPLACE PROCEDURE post_financial_journal',
  'CREATE TRIGGER trg_payment_history_append_only',
  'CREATE TRIGGER trg_review_evidence_append_only',
  "lane IN ('OBJECT_STATE','BALANCE_LEDGER','CONTROL')",
  'legal_hold_until TIMESTAMPTZ',
  'financial_export_manifest_id VARCHAR(255)',
  'CREATE UNIQUE INDEX uq_reconciliation_singleton') |
    ForEach-Object { Assert-Contains $migration $_ $migrationPath }

$connectorPath = 'java/services/payment-service/src/main/resources/kafka/debezium-connector-payment-outbox.json'
try { $connector = (Read-Repo $connectorPath) | ConvertFrom-Json -Depth 100 }
catch { Fail "Invalid JSON connector config: $($_.Exception.Message)"; $connector = $null }
if ($null -ne $connector) {
    $config = $connector.config
    if ($config.'table.include.list' -ne 'public.outbox_events' -or
        $config.'transforms.outbox.table.field.event.key' -ne 'partition_key' -or
        $config.'transforms.paymentTopic.replacement' -ne 't.payment.events.v2' -or
        $config.'tombstones.on.delete' -ne 'false' -or $config.'errors.tolerance' -ne 'none') {
        Fail 'Debezium connector differs from the fail-closed Payment outbox contract.'
    }
}

$publicOpenApi = Read-Repo 'contracts/http/payment/v1/openapi.yaml'
$internalOpenApi = Read-Repo 'contracts/http/payment/internal/v1/openapi.yaml'
@('initiatePayment','getPayment','getPaymentClientData','cancelPayment','initiateRefund',
  'getRefund','ingestStripeWebhook') |
    ForEach-Object { Assert-Contains $publicOpenApi "operationId: $_" 'Public Payment OpenAPI' }
@('listPaymentReviewCases','claimPaymentReviewCase','resolvePaymentReviewCase',
  'correctPaymentReviewCase','replayVerifiedPaymentWebhook','startPaymentReconciliation',
  'setPaymentReconciliationFreeze','getPaymentFinancialJournal','getPaymentDispute') |
    ForEach-Object { Assert-Contains $internalOpenApi "operationId: $_" 'Internal Payment OpenAPI' }

try {
    $webhookSchema = (Read-Repo 'contracts/events/payment/internal/v1/schemas/stripe-webhook-received.schema.json') | ConvertFrom-Json -Depth 100
    $webhookExample = (Read-Repo 'contracts/examples/events/payment-stripe-webhook-received.v1.json') | ConvertFrom-Json -Depth 100
    foreach ($field in @('stripe_event_id','event_type','event_created_at','verified_at',
                          'signing_secret_id','raw_body_base64','raw_body_sha256','stripe_signature')) {
        if ($field -notin $webhookSchema.allOf[1].properties.data.required) { Fail "Webhook schema does not require $field" }
        if ($null -eq $webhookExample.data.$field) { Fail "Webhook example does not contain $field" }
    }
} catch { Fail "Invalid verified-webhook schema/example: $($_.Exception.Message)" }

if ($errors.Count -gt 0) {
    Write-Host "PAYMENT GATE 2 FAILED ($($errors.Count) error(s))" -ForegroundColor Red
    $errors | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host 'PAYMENT GATE 2 PASSED' -ForegroundColor Green
Write-Host '  - 15 financial event schemas/examples/AsyncAPI bindings are synchronized'
Write-Host '  - Order accepts exactly 13 relevant Payment facts'
Write-Host '  - Public/internal HTTP and verified-webhook contracts are present'
Write-Host '  - 12-table PostgreSQL baseline and safety triggers are present'
Write-Host '  - Debezium outbox is fail-closed and targets t.payment.events.v2'

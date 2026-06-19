$root = "C:\Users\USER\E-Commerce Platform\docs"
$dirs = @("checkout","order","payment","inventory","promotion")
$files = @()
foreach ($d in $dirs) {
    $files += Get-ChildItem -Path "$root\$d" -Recurse -Filter "*.md"
}

$report = @()

foreach ($f in $files) {
    $content = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
    $findings = @()

    # Check 1: user_id BIGINT (should be 0)
    $count = ([regex]::Matches($content, 'user_id["\s:]+BIGINT')).Count
    if ($count -gt 0) { $findings += "user_id:BIGINT=$count" }

    # Check 2: courier_service_type (deprecated, should be 0)
    $count = ([regex]::Matches($content, 'courier_service_type')).Count
    if ($count -gt 0) { $findings += "courier_service_type=$count" }

    # Check 3: deleted_owner_type = admin (lowercase, should be 0)
    $count = ([regex]::Matches($content, 'deleted_owner_type\s*[=:]\s*["'']?admin["'']?(\s|$|,)')).Count
    if ($count -gt 0) { $findings += "deleted_owner_type=admin=$count" }

    # Check 4: SHOP in refund context (should be 0)
    $count = ([regex]::Matches($content, 'hoàn.*SHOP|SHOP.*refund')).Count
    if ($count -gt 0) { $findings += "refund_SHOP=$count" }

    # Check 5: duplicate REFUND_REVERSED heading
    $count = ([regex]::Matches($content, 'Provider-dependent\)\s*\(Optional, Provider-dependent\)')).Count
    if ($count -gt 0) { $findings += "dup_REFUND_REVERSED=$count" }

    # Check 6: user_id: 999 (legacy BIGINT example)
    $count = ([regex]::Matches($content, 'user_id["\s:]+999')).Count
    if ($count -gt 0) { $findings += "user_id:999=$count" }

    # Check 7 (Phase 14): FREE_SHIPPING voucher type (should be 0)
    $count = ([regex]::Matches($content, 'discount_type["\s:]+["'']?FREE_SHIPPING["'']?')).Count
    if ($count -gt 0) { $findings += "FREE_SHIPPING=$count" }

    # Check 8 (Phase 14): SHIPMENT_DISCOUNT promotion type (should be 0)
    $count = ([regex]::Matches($content, 'promotion_type["\s:]+["'']?SHIPPING_DISCOUNT["'']?')).Count
    if ($count -gt 0) { $findings += "SHIPPING_DISCOUNT=$count" }

    if ($findings.Count -gt 0) {
        $report += [PSCustomObject]@{File = $f.Name; Findings = ($findings -join ", ")}
    }
}

if ($report.Count -eq 0) {
    Write-Host "ALL CHECKS PASSED - No inconsistency found across 5 service doc folders (Phase 14: Shipping removed)" -ForegroundColor Green
} else {
    Write-Host "Found $($report.Count) files with potential inconsistencies:" -ForegroundColor Yellow
    $report | Format-Table -AutoSize
}

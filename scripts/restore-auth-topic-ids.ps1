#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RecoveryDir)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$allowedRoot = [IO.Path]::GetFullPath((Join-Path $repo '.tools/kafka-recovery')) + [IO.Path]::DirectorySeparatorChar
$backup = [IO.Path]::GetFullPath($RecoveryDir)
if (-not $backup.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'RecoveryDir must be a backup directory under .tools/kafka-recovery.'
}
# Only the audited single-broker root development stack; never resets logs/offsets.
$running = & docker inspect kafka --format '{{.State.Running}}'
if ($LASTEXITCODE -ne 0 -or "$running".Trim() -ne 'false') { throw 'Stop Kafka and its clients before restoring metadata.' }
$checksums = Get-Content (Join-Path $backup 'archive-checksums.json') -Raw | ConvertFrom-Json
foreach ($name in @('kafka-data.tar', 'zookeeper.tar')) {
    $expected = @($checksums | Where-Object { [IO.Path]::GetFileName($_.Path) -eq $name })
    $actual = Get-FileHash -Algorithm SHA256 (Join-Path $backup $name)
    if ($expected.Count -ne 1 -or $actual.Hash -cne $expected[0].Hash) { throw "Missing or invalid archive: $name" }
}
function Get-TopicMetadata([string]$topic) {
    $output = & docker run --rm --network ecommerce-network --entrypoint zookeeper-shell confluentinc/cp-kafka:7.5.0 zookeeper:2181 get "/brokers/topics/$topic"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read ZooKeeper metadata.' }
    $lines = @($output | Where-Object { $_ -match '^\{"partitions"' })
    if ($lines.Count -ne 1) { throw 'Unexpected ZooKeeper response.' }
    return ($lines[0] | ConvertFrom-Json -AsHashtable)
}
$audit = Get-Content (Join-Path $backup 'topic-audit.json') -Raw | ConvertFrom-Json -AsHashtable
$operations = @()
foreach ($topic in @('t.auth.events', 't.auth.notification.events')) {
    $row = $audit[$topic]
    $diskIds = @($row.DiskIds.Values | Select-Object -Unique)
    $before = Get-Content (Join-Path $backup "$topic.before.json") -Raw | ConvertFrom-Json -AsHashtable
    if ($row.Partitions -ne 1 -or $row.DiskIds.Count -ne 1 -or $diskIds.Count -ne 1 -or $before.topic_id -cne $row.BrokerId -or $before.version -ne 3 -or $before.partitions.Count -ne 1 -or @($before.partitions['0']).Count -ne 1 -or $before.partitions['0'][0] -ne 1) {
        throw "Audit is not a consistent single-broker history for $topic."
    }
    $current = Get-TopicMetadata $topic
    $after = $before.Clone()
    $after.topic_id = $diskIds[0]
    if ($current.topic_id -ceq $diskIds[0]) {
        if (($current | ConvertTo-Json -Depth 20 -Compress) -cne ($after | ConvertTo-Json -Depth 20 -Compress)) {
            throw "Assignment changed after restoration for $topic."
        }
        Write-Output "$topic already restored."
        continue
    }
    if (($current | ConvertTo-Json -Depth 20 -Compress) -cne ($before | ConvertTo-Json -Depth 20 -Compress)) {
        throw "Metadata changed since backup for $topic; refusing restoration."
    }
    $operations += @{ topic = $topic; before = $before; after = $after }
}
if (-not $operations.Count) { Write-Output 'No metadata changes needed.'; return }
$restore = @()
$rollback = @()
foreach ($op in $operations) {
    $restore += "set /brokers/topics/$($op.topic) $($op.after | ConvertTo-Json -Depth 20 -Compress)"
    $rollback += "set /brokers/topics/$($op.topic) $($op.before | ConvertTo-Json -Depth 20 -Compress)"
}
$restore += 'quit'
$rollback += 'quit'
$restore | Set-Content (Join-Path $backup 'restore-commands.txt') -Encoding utf8
$rollback | Set-Content (Join-Path $backup 'rollback-commands.txt') -Encoding utf8
try {
    $output = Get-Content (Join-Path $backup 'restore-commands.txt') -Raw | & docker run --rm -i --network ecommerce-network --entrypoint zookeeper-shell confluentinc/cp-kafka:7.5.0 zookeeper:2181
    if ($LASTEXITCODE -ne 0 -or ($output -match 'Node does not exist|KeeperErrorCode|Exception')) { throw 'ZooKeeper set failed.' }
    foreach ($op in $operations) {
        $current = Get-TopicMetadata $op.topic
        if (($current | ConvertTo-Json -Depth 20 -Compress) -cne ($op.after | ConvertTo-Json -Depth 20 -Compress)) { throw 'Restored metadata does not match audit.' }
        Write-Output "$($op.topic): restored disk Topic ID; assignment and logs preserved."
    }
} catch {
    $null = Get-Content (Join-Path $backup 'rollback-commands.txt') -Raw | & docker run --rm -i --network ecommerce-network --entrypoint zookeeper-shell confluentinc/cp-kafka:7.5.0 zookeeper:2181
    throw 'Restoration failed; rollback requested. Keep broker stopped and inspect backup/metadata before proceeding.'
}

#requires -Version 7.0
[CmdletBinding()]
param([string]$Container = 'kafka')
$ErrorActionPreference = 'Stop'
# Root-stack diagnostic only; no event publication, offset reset or topic deletion.
$description = & docker exec $Container kafka-topics --bootstrap-server kafka:9092 --command-config /etc/kafka/admin.properties --describe --topic t.auth.events 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Cannot describe t.auth.events. Check broker readiness/topic existence.' }
$text = $description -join "`n"
$match = [regex]::Match($text, 'TopicId:\s*([A-Za-z0-9_-]+)')
if (-not $match.Success) { throw 'Cannot determine broker Topic ID for t.auth.events.' }
$topicId = $match.Groups[1].Value
$partitions = [regex]::Matches($text, 'Partition:\s*(\d+)')
if (-not $partitions.Count) { throw 'No partitions found for t.auth.events.' }
foreach ($partition in $partitions) {
    $id = $partition.Groups[1].Value
    $metadata = & docker exec $Container cat "/var/lib/kafka/data/t.auth.events-$id/partition.metadata" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Cannot read local partition metadata for t.auth.events/$id on root broker." }
    $disk = [regex]::Match(($metadata -join "`n"), 'topic_id:\s*([A-Za-z0-9_-]+)')
    if (-not $disk.Success -or $disk.Groups[1].Value -cne $topicId) {
        throw "Topic ID mismatch for t.auth.events/$id. Preserve data; restore matching ZooKeeper metadata or approve an archived dev reset. Do not reset offsets/delete volumes."
    }
}
Write-Output 'PASS: t.auth.events broker and disk Topic IDs match. Verify consumer lag/bootstrap separately.'

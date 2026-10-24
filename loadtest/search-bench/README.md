# Search read model benchmark

`scripts/ci/search-read-model-bench.ps1` measures the current default read policy and the
production `FullReindexJob` CUTOVER stage against a historical jar, in a dedicated compose
project/network/volume (`search-read-model-bench`). Never seed the shared cluster.

Requirements: Docker Desktop, PowerShell, Java 21 and Maven to build the service/fixture.
The fixture source is `src/test/java/com/ecommerce/search/benchmark/ReadModelBenchMain.java`.
It is packaged SEPARATELY and never enters the production service jar. Its HTTP controller
exists only in that fixture under the `read-model-benchmark` profile.

```powershell
# Build and preserve the baseline BEFORE editing production source. Keep its hash/source manifest.
$env:JAVA_HOME = 'C:/path/to/jdk21'
mvn -f java/services/search-service/pom.xml -DskipTests package
Copy-Item java/services/search-service/target/search-service-1.0.0-SNAPSHOT.jar baseline.jar
# Package the fixture from test-classes once; use identical fixture bytes for both jars.
jar cf fixture.jar -C java/services/search-service/target/test-classes com/ecommerce/search/benchmark
./scripts/ci/search-read-model-bench.ps1 -Jar baseline.jar -Fixture fixture.jar -Label baseline-default
./scripts/ci/search-read-model-bench.ps1 -Jar baseline.jar -Fixture fixture.jar -Label baseline-cards-ready -ForceCards -Lifecycle
# After changes, tests, build:
./scripts/ci/search-read-model-bench.ps1 -Jar new.jar -Fixture fixture.jar -Label required-default -Lifecycle
# Supplemental lower-rate 60k run to put the physical swap inside the active load window:
./scripts/ci/search-read-model-bench.ps1 -Jar new.jar -Fixture fixture.jar -Label required-cutover-10 -Lifecycle -Sizes @(60000) -Rate 10
```

Defaults: 10,000 and 60,000 Products (seed 20261016, 1–5 SKUs), ES 4 CPU / 1 GB heap,
Search 2 CPU / 512 MB heap, 30 arrivals/s. Each home arrival sends the two storefront
queries concurrently; browse rotates five queries. Warmup 30s, measured steady cases 45s,
cutover load 90s, max 256 k6 VUs, client timeout/graceful stop 30s. Summaries, raw compressed
k6 CSV, Docker resource samples, query counters and jar/fixture hashes are retained.
`ForceCards` controls the old pilot. It waits for readiness BEFORE warmup so a bootstrap
load storm is not misreported as steady-state performance. `Lifecycle` also waits for cards.
Baseline default intentionally does not: that artifact defaults to grouping.

Cutover setup clones the already seeded SKU and Category generations using ES `_reindex`
OUTSIDE the measured load. At load t≈10s, the fixture resumes the real production job from
its durable CUTOVER checkpoint. In the new jar this includes building/refreshing cards and
the five-alias swap; in the historical jar it swaps four aliases and the live projector
rebuilds afterwards. Observe until cards matches the new generation, up to 140s.
The fixture does not measure upstream export, Kafka catch-up, catalog verification, or
financial services; `tail_replay=INCOMPLETE` is expected because no Inventory snapshot
session exists. Functional full-reindex tests cover the complete pipeline separately.

The runner removes only its own compose project/volume and temporary jar copy. Evidence
stays in `loadtest/search-bench/results/required-20261019/<label>` (git-ignored). Copy compact
results plus raw CSV/logs to `docs/performance` for a reviewed report. To repeat on another
date, choose a distinct label and record the actual timestamps from `environment.json`.

The older `search-bench.sh` and `compare.py` compare grouping/cards using an ADR-027 jar.
They cannot use the new jar as their grouping side: ADR-029 intentionally rejects new
requests without a ready projection. Use a historical baseline URL for semantic comparisons.

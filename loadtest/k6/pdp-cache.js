// PDP read benchmark for the catalog cache (docs/performance/pdp-catalog-cache-benchmark-2026-10-17.md).
//
// Open model: requests arrive at RATE per second whatever the latency (constant-arrival-rate), so a slow system
// shows up as latency, dropped iterations and errors instead of quietly lowering the load as a closed loop would.
// Products come from loadtest/pdp-bench/seed/product.sql (slug bench-p-000001..N). Popularity follows DIST:
//   zipf     rank i is chosen with probability proportional to 1/i^ZIPF_S (storefront-like: a head of hot
//            products and a long tail);
//   uniform  every product equally often (worst case for a cache: the long tail only).
//
//   BASE_URL   BFF origin (default http://product-detail-bff:8080)
//   RATE       requests per second (default 200)    DURATION  e.g. 60s (default 60s)
//   PRODUCTS   catalog size (default 10000)         DIST      zipf | uniform (default zipf)
//   ZIPF_S     Zipf exponent (default 1.0)          OUT       summary file (default /out/summary.json)
//   TARGET     bff (default) | product: product calls Product's buyer-read directly, as the BFF does; used to warm
//              Product up without the BFF circuit breaker and to measure Product on its own
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://product-detail-bff:8080';
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '60s';
const PRODUCTS = parseInt(__ENV.PRODUCTS || '10000', 10);
const DIST = __ENV.DIST || 'zipf';
const ZIPF_S = parseFloat(__ENV.ZIPF_S || '1.0');
const OUT = __ENV.OUT || '/out/summary.json';
const TARGET = __ENV.TARGET || 'bff';
const PRODUCT_URL = __ENV.PRODUCT_URL || 'http://product-service:8081';

const statuses = new Counter('pdp_status');

// Cumulative distribution of the Zipf ranks, built once per VU (10 000 doubles).
const cdf = (() => {
  if (DIST !== 'zipf') return null;
  const values = new Float64Array(PRODUCTS);
  let total = 0;
  for (let i = 0; i < PRODUCTS; i++) {
    total += 1 / Math.pow(i + 1, ZIPF_S);
    values[i] = total;
  }
  for (let i = 0; i < PRODUCTS; i++) values[i] /= total;
  return values;
})();

function pick() {
  const u = Math.random();
  if (!cdf) return 1 + Math.floor(u * PRODUCTS);
  let lo = 0;
  let hi = PRODUCTS - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (cdf[mid] < u) lo = mid + 1; else hi = mid;
  }
  return lo + 1;
}

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Always-true thresholds: they only make k6 keep a per-status sub-metric for the summary (0 = no response).
  thresholds: Object.fromEntries(['0', '200', '404', '429', '500', '503', '504']
    .map((status) => [`pdp_status{status:${status}}`, ['count>=0']])),
  scenarios: {
    pdp: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, Math.ceil(RATE / 10)),
      maxVUs: Math.max(200, RATE),
    },
  },
};

export default function () {
  const slug = 'bench-p-' + String(pick()).padStart(6, '0');
  const response = TARGET === 'product'
    ? http.get(`${PRODUCT_URL}/internal/v1/product-details/${slug}`, {
      headers: { 'X-Service-Identity': 'product-detail-bff' }, tags: { name: 'product' }, timeout: '5s',
    })
    : http.get(`${BASE_URL}/api/v1/storefront/products/${slug}`, { tags: { name: 'pdp' }, timeout: '5s' });
  statuses.add(1, { status: String(response.status) });
  check(response, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const metric = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const byStatus = {};
  for (const [name, value] of Object.entries(data.metrics)) {
    const match = name.match(/^pdp_status\{status:(\d+)\}$/);
    if (match) byStatus[match[1]] = value.values.count;
  }
  const summary = {
    target: TARGET,
    rate: RATE,
    duration: DURATION,
    products: PRODUCTS,
    dist: DIST,
    zipf_s: DIST === 'zipf' ? ZIPF_S : null,
    requests: metric('http_reqs').count || 0,
    achieved_rps: metric('http_reqs').rate || 0,
    dropped_iterations: metric('dropped_iterations').count || 0,
    failed_rate: metric('http_req_failed').rate || 0,
    statuses: byStatus,
    latency_ms: metric('http_req_duration'),
    max_vus: metric('vus_max').max || null,
  };
  return {
    [OUT]: JSON.stringify(summary, null, 2),
    stdout: `target=${TARGET} rate=${RATE} dist=${DIST} reqs=${summary.requests} rps=${summary.achieved_rps.toFixed(1)} ` +
      `dropped=${summary.dropped_iterations} failed=${(summary.failed_rate * 100).toFixed(2)}% ` +
      `p50=${(summary.latency_ms.med || 0).toFixed(1)} p95=${(summary.latency_ms['p(95)'] || 0).toFixed(1)} ` +
      `p99=${(summary.latency_ms['p(99)'] || 0).toFixed(1)} statuses=${JSON.stringify(byStatus)}\n`,
  };
}

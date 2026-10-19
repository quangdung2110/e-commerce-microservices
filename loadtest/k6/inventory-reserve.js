// Inventory Reserve hot-SKU benchmark (docs/performance/inventory-reserve-hot-sku-baseline-2026-10-19.md).
//
// Open model: Reserve commands arrive at RATE per second whatever the latency (constant-arrival-rate), as orders do
// in a flash sale, so a saturated hot row shows up as latency, 503 INVENTORY_BUSY and dropped arrivals instead of a
// closed loop quietly lowering the load. Every iteration is a new one-line order of quantity 1 sent the way Order's
// Temporal saga sends ReserveOrder (contracts/http/inventory/workflow/v1): a fresh Idempotency-Key, correlation and
// causation IDs, X-Service-Identity order-service. Nothing is retried here; Order's own retries would add load on top.
// Stock comes from loadtest/inventory-bench/seed/inventory.sql.
//
//   BASE_URL   Inventory origin (default http://inventory-service:8080)
//   RATE       Reserve commands per second (default 100)   DURATION  e.g. 60s (default 60s)
//   HOT_SHARE  share of orders for BENCH-HOT, 0..1 (default 1); the rest pick a cold SKU uniformly
//   COLD_SKUS  number of cold SKUs in the seed (default 2000)
//   RUN_TAG    unique prefix for order IDs of this step (required across steps of one seed)
//   MAX_VUS    cap on concurrent requests (default 2000)    OUT  summary file (default /out/summary.json)
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://inventory-service:8080';
const RATE = parseInt(__ENV.RATE || '100', 10);
const DURATION = __ENV.DURATION || '60s';
const HOT_SHARE = parseFloat(__ENV.HOT_SHARE || '1');
const COLD_SKUS = parseInt(__ENV.COLD_SKUS || '2000', 10);
const RUN_TAG = __ENV.RUN_TAG || `run${Date.now()}`;
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000', 10);
const OUT = __ENV.OUT || '/out/summary.json';

const CLASSES = ['hot', 'cold'];
// reserved: 200 RESERVED; rejected: 200 REJECT_ALL; busy/unavailable: 503 by code; timeout: no response.
const RESULTS = ['reserved', 'rejected', 'busy', 'unavailable', 'timeout', 'other'];

const outcomes = new Counter('reserve_outcome');
const latency = {};
for (const c of CLASSES) {
  latency[c] = { all: new Trend(`reserve_ms_${c}`, true), reserved: new Trend(`reserve_ms_${c}_reserved`, true) };
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Always-true thresholds: they only make k6 keep a sub-metric per class/result for the summary.
  thresholds: Object.fromEntries(CLASSES.flatMap((c) => RESULTS.map((r) => [
    `reserve_outcome{class:${c},result:${r}}`, ['count>=0']]))),
  scenarios: {
    reserve: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(MAX_VUS, Math.max(50, RATE)),
      maxVUs: MAX_VUS,
    },
  },
};

function classify(response) {
  if (response.status === 0) return 'timeout';
  let body = null;
  try { body = response.json(); } catch (e) { body = null; }
  if (response.status === 200 && body) {
    if (body.outcome === 'RESERVED') return 'reserved';
    if (body.outcome === 'REJECT_ALL') return 'rejected';
  }
  if (response.status === 503 && body) {
    if (body.code === 'INVENTORY_BUSY') return 'busy';
    if (body.code === 'INVENTORY_UNAVAILABLE') return 'unavailable';
  }
  return 'other';
}

export default function () {
  const hot = Math.random() < HOT_SHARE;
  const cls = hot ? 'hot' : 'cold';
  const sku = hot ? 'BENCH-HOT' : 'BENCH-COLD-' + String(1 + Math.floor(Math.random() * COLD_SKUS)).padStart(4, '0');
  const orderId = `${RUN_TAG}-${__VU}-${__ITER}`;
  const body = JSON.stringify({
    order_id: orderId,
    allocation_plan_id: `plan-${orderId}`,
    lines: [{ order_line_id: `${orderId}-l1`, sku_id: sku, quantity: 1, warehouse_id: 'BENCH-WH-1' }],
  });
  const response = http.post(`${BASE_URL}/internal/inventory/v1/reservations`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Service-Identity': 'order-service',
      'Idempotency-Key': `reserve-${orderId}`,
      'X-Correlation-ID': `corr-${orderId}`,
      'X-Causation-ID': `cause-${orderId}`,
    },
    tags: { name: 'reserve', class: cls },
    // Order's activity StartToClose timeout.
    timeout: '30s',
  });
  const result = classify(response);
  outcomes.add(1, { class: cls, result });
  if (response.status !== 0) {
    latency[cls].all.add(response.timings.duration);
    if (result === 'reserved') latency[cls].reserved.add(response.timings.duration);
  }
}

export function handleSummary(data) {
  const metric = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const outcomesBy = {};
  for (const c of CLASSES) {
    outcomesBy[c] = {};
    for (const r of RESULTS) {
      const m = data.metrics[`reserve_outcome{class:${c},result:${r}}`];
      outcomesBy[c][r] = m ? m.values.count : 0;
    }
  }
  const latencyBy = {};
  for (const c of CLASSES) {
    latencyBy[c] = { all: metric(`reserve_ms_${c}`), reserved: metric(`reserve_ms_${c}_reserved`) };
  }
  const seconds = parseInt(DURATION, 10) * (DURATION.endsWith('m') ? 60 : 1);
  const reserved = outcomesBy.hot.reserved + outcomesBy.cold.reserved;
  const summary = {
    rate: RATE,
    duration: DURATION,
    hot_share: HOT_SHARE,
    cold_skus: COLD_SKUS,
    run_tag: RUN_TAG,
    requests: metric('http_reqs').count || 0,
    achieved_rps: metric('http_reqs').rate || 0,
    reserved_per_s: reserved / seconds,
    dropped_iterations: metric('dropped_iterations').count || 0,
    outcomes: outcomesBy,
    latency_ms: latencyBy,
    max_vus: metric('vus_max').max || null,
  };
  const fmt = (v) => (v === undefined ? '-' : v.toFixed(1));
  const line = (c) => `${c}: p50=${fmt(latencyBy[c].all.med)} p95=${fmt(latencyBy[c].all['p(95)'])} ` +
    `p99=${fmt(latencyBy[c].all['p(99)'])} ${JSON.stringify(outcomesBy[c])}`;
  return {
    [OUT]: JSON.stringify(summary, null, 2),
    stdout: `rate=${RATE} hot_share=${HOT_SHARE} reqs=${summary.requests} reserved/s=${summary.reserved_per_s.toFixed(1)} ` +
      `dropped=${summary.dropped_iterations} | ${line('hot')} | ${line('cold')}\n`,
  };
}

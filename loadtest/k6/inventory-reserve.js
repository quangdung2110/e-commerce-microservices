// Inventory Reserve hot-SKU benchmark (docs/performance/inventory-reserve-hot-sku-baseline-2026-10-19.md).
//
// Open model: commands arrive at RATE per second whatever the latency (constant-arrival-rate), as orders do in a
// flash sale, so a saturated hot row shows up as latency, 503 INVENTORY_BUSY and dropped arrivals instead of a closed
// loop quietly lowering the load. Every Reserve is a new one-line order of quantity 1 sent the way Order's Temporal
// saga sends ReserveOrder (contracts/http/inventory/workflow/v1): a fresh Idempotency-Key, correlation and causation
// IDs, X-Service-Identity order-service. Nothing is retried here; Order's own retries would add load on top.
// Stock comes from loadtest/inventory-bench/seed/inventory.sql.
//
// MODE=reserve-commit adds the second half of each order's life on the same rows: a second scenario at the same RATE
// starts COMMIT_DELAY later and finishes order n of the Reserve scenario with CommitPayment, or with ReleaseReservation
// (PAYMENT_FAILED) for a RELEASE_SHARE of orders. Order IDs and SKUs are derived from the iteration number, so both
// scenarios agree without sharing state. Commit and Release lock the same stock_position row as Reserve.
//
//   BASE_URL      Inventory origin (default http://inventory-service:8080)
//   RATE          orders per second (default 100)          DURATION  per scenario, e.g. 60s (default 60s)
//   MODE          reserve (default) | reserve-commit
//   COMMIT_DELAY  seconds between an order's Reserve and its Commit/Release arrival (default 15)
//   RELEASE_SHARE share of orders released instead of committed, 0..1 (default 0)
//   HOT_SHARE     share of orders for BENCH-HOT, 0..1 (default 1); the rest spread over cold SKUs
//   COLD_SKUS     number of cold SKUs in the seed (default 2000)
//   RUN_TAG       unique prefix for order IDs of this step (required across steps of one seed)
//   MAX_VUS       cap on concurrent requests per scenario (default 2000). Order sends at most 64 calls in flight to
//                 Inventory per replica (bulkhead, Order ADR-024): MAX_VUS=32 per scenario models one replica; an
//                 arrival with no free VU is dropped, as Order's BULKHEAD_FULL defers the call to a later saga replay
//   TIMEOUT       client timeout per request (default 30s; Order's Inventory read timeout is 8s)
//   OUT           summary file (default /out/summary.json)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://inventory-service:8080';
const RATE = parseInt(__ENV.RATE || '100', 10);
const DURATION = __ENV.DURATION || '60s';
const MODE = __ENV.MODE || 'reserve';
const COMMIT_DELAY = parseInt(__ENV.COMMIT_DELAY || '15', 10);
const RELEASE_SHARE = parseFloat(__ENV.RELEASE_SHARE || '0');
const HOT_SHARE = parseFloat(__ENV.HOT_SHARE || '1');
const COLD_SKUS = parseInt(__ENV.COLD_SKUS || '2000', 10);
const RUN_TAG = __ENV.RUN_TAG || `run${Date.now()}`;
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000', 10);
const TIMEOUT = __ENV.TIMEOUT || '30s';
const OUT = __ENV.OUT || '/out/summary.json';

const CLASSES = ['hot', 'cold'];
// reserved: 200 RESERVED; rejected: 200 REJECT_ALL; busy/unavailable: 503 by code; timeout: no response.
const RESULTS = ['reserved', 'rejected', 'busy', 'unavailable', 'timeout', 'other'];
// committed/released: 200 COMMITTED/RELEASED; not_reserved: 409 (the Reserve failed or had not finished yet).
const COMMIT_RESULTS = ['committed', 'released', 'not_reserved', 'busy', 'unavailable', 'timeout', 'other'];

const outcomes = new Counter('reserve_outcome');
const commitOutcomes = new Counter('commit_outcome');
const latency = {};
for (const c of CLASSES) {
  latency[c] = {
    all: new Trend(`reserve_ms_${c}`, true),
    reserved: new Trend(`reserve_ms_${c}_reserved`, true),
    commit: new Trend(`commit_ms_${c}`, true),
  };
}

const scenarios = {
  reserve: {
    executor: 'constant-arrival-rate',
    exec: 'reserve',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.min(MAX_VUS, Math.max(50, RATE)),
    maxVUs: MAX_VUS,
  },
};
if (MODE === 'reserve-commit') {
  scenarios.commit = { ...scenarios.reserve, exec: 'finish', startTime: `${COMMIT_DELAY}s` };
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Always-true thresholds: they only make k6 keep a sub-metric per class/result for the summary.
  thresholds: Object.fromEntries([
    ...CLASSES.flatMap((c) => RESULTS.map((r) => [`reserve_outcome{class:${c},result:${r}}`, ['count>=0']])),
    ...CLASSES.flatMap((c) => COMMIT_RESULTS.map((r) => [`commit_outcome{class:${c},result:${r}}`, ['count>=0']])),
    ...['reserve', 'commit'].map((s) => [`dropped_iterations{scenario:${s}}`, ['count>=0']]),
  ]),
  scenarios,
};

// Deterministic per-order choices (Knuth multiplicative hash of the iteration number).
function mix(n, salt) {
  return (Math.imul(n + salt, 2654435761) >>> 0);
}

function order(n) {
  const hot = (mix(n, 0) % 10000) / 10000 < HOT_SHARE;
  const sku = hot ? 'BENCH-HOT' : 'BENCH-COLD-' + String(1 + (mix(n, 7919) % COLD_SKUS)).padStart(4, '0');
  return { id: `${RUN_TAG}-${n}`, cls: hot ? 'hot' : 'cold', sku, release: (mix(n, 104729) % 10000) / 10000 < RELEASE_SHARE };
}

function headers(id, step) {
  return {
    'Content-Type': 'application/json',
    'X-Service-Identity': 'order-service',
    'Idempotency-Key': `${step}-${id}`,
    'X-Correlation-ID': `corr-${id}`,
    'X-Causation-ID': `cause-${step}-${id}`,
  };
}

function parse(response) {
  try { return response.json(); } catch (e) { return null; }
}

function classifyReserve(response) {
  if (response.status === 0) return 'timeout';
  const body = parse(response);
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

function classifyFinish(response) {
  if (response.status === 0) return 'timeout';
  const body = parse(response);
  if (response.status === 200 && body) {
    if (body.outcome === 'COMMITTED') return 'committed';
    if (body.outcome === 'RELEASED') return 'released';
  }
  if (response.status === 409 && body && body.code === 'RESERVATION_NOT_RESERVED') return 'not_reserved';
  if (response.status === 503 && body) {
    if (body.code === 'INVENTORY_BUSY') return 'busy';
    if (body.code === 'INVENTORY_UNAVAILABLE') return 'unavailable';
  }
  return 'other';
}

export function reserve() {
  const o = order(exec.scenario.iterationInTest);
  const body = JSON.stringify({
    order_id: o.id,
    allocation_plan_id: `plan-${o.id}`,
    lines: [{ order_line_id: `${o.id}-l1`, sku_id: o.sku, quantity: 1, warehouse_id: 'BENCH-WH-1' }],
  });
  const response = http.post(`${BASE_URL}/internal/inventory/v1/reservations`, body, {
    headers: headers(o.id, 'reserve'), tags: { name: 'reserve', class: o.cls }, timeout: TIMEOUT,
  });
  const result = classifyReserve(response);
  outcomes.add(1, { class: o.cls, result });
  if (response.status !== 0) {
    latency[o.cls].all.add(response.timings.duration);
    if (result === 'reserved') latency[o.cls].reserved.add(response.timings.duration);
  }
}

export function finish() {
  const o = order(exec.scenario.iterationInTest);
  const url = `${BASE_URL}/internal/inventory/v1/reservations/${o.id}/${o.release ? 'release' : 'commit'}`;
  const body = JSON.stringify(o.release ? { reason: 'PAYMENT_FAILED' } : { payment_reference: `pay-${o.id}` });
  const response = http.post(url, body, {
    headers: headers(o.id, o.release ? 'release' : 'commit'), tags: { name: 'finish', class: o.cls }, timeout: TIMEOUT,
  });
  commitOutcomes.add(1, { class: o.cls, result: classifyFinish(response) });
  if (response.status !== 0) latency[o.cls].commit.add(response.timings.duration);
}

export default reserve;

export function handleSummary(data) {
  const metric = (name) => (data.metrics[name] ? data.metrics[name].values : {});
  const counts = (prefix, results) => {
    const by = {};
    for (const c of CLASSES) {
      by[c] = {};
      for (const r of results) {
        const m = data.metrics[`${prefix}{class:${c},result:${r}}`];
        by[c][r] = m ? m.values.count : 0;
      }
    }
    return by;
  };
  const outcomesBy = counts('reserve_outcome', RESULTS);
  const latencyBy = {};
  const commitLatencyBy = {};
  for (const c of CLASSES) {
    latencyBy[c] = { all: metric(`reserve_ms_${c}`), reserved: metric(`reserve_ms_${c}_reserved`) };
    commitLatencyBy[c] = metric(`commit_ms_${c}`);
  }
  const seconds = parseInt(DURATION, 10) * (DURATION.endsWith('m') ? 60 : 1);
  const reserved = outcomesBy.hot.reserved + outcomesBy.cold.reserved;
  const summary = {
    mode: MODE,
    rate: RATE,
    duration: DURATION,
    hot_share: HOT_SHARE,
    cold_skus: COLD_SKUS,
    run_tag: RUN_TAG,
    requests: metric('http_reqs').count || 0,
    achieved_rps: metric('http_reqs').rate || 0,
    reserved_per_s: reserved / seconds,
    // Reserve arrivals k6 could not start; Commit/Release arrivals are counted apart below.
    dropped_iterations: metric('dropped_iterations{scenario:reserve}').count || 0,
    outcomes: outcomesBy,
    latency_ms: latencyBy,
    max_vus: metric('vus_max').max || null,
  };
  let commitLine = '';
  if (MODE === 'reserve-commit') {
    const commitBy = counts('commit_outcome', COMMIT_RESULTS);
    const finished = CLASSES.reduce((s, c) => s + commitBy[c].committed + commitBy[c].released, 0);
    Object.assign(summary, {
      commit_delay_s: COMMIT_DELAY,
      release_share: RELEASE_SHARE,
      commit_outcomes: commitBy,
      commit_latency_ms: commitLatencyBy,
      commit_dropped_iterations: metric('dropped_iterations{scenario:commit}').count || 0,
      finished_per_s: finished / seconds,
    });
    const h = commitLatencyBy.hot;
    commitLine = ` | finished/s=${summary.finished_per_s.toFixed(1)} commit hot: p50=${(h.med || 0).toFixed(1)} ` +
      `p95=${(h['p(95)'] || 0).toFixed(1)} p99=${(h['p(99)'] || 0).toFixed(1)} ${JSON.stringify(commitBy.hot)}`;
  }
  const fmt = (v) => (v === undefined ? '-' : v.toFixed(1));
  const line = (c) => `${c}: p50=${fmt(latencyBy[c].all.med)} p95=${fmt(latencyBy[c].all['p(95)'])} ` +
    `p99=${fmt(latencyBy[c].all['p(99)'])} ${JSON.stringify(outcomesBy[c])}`;
  return {
    [OUT]: JSON.stringify(summary, null, 2),
    stdout: `rate=${RATE} hot_share=${HOT_SHARE} reqs=${summary.requests} reserved/s=${summary.reserved_per_s.toFixed(1)} ` +
      `dropped=${summary.dropped_iterations} | ${line('hot')} | ${line('cold')}${commitLine}\n`,
  };
}

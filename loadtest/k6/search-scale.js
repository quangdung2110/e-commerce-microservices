// Scale benchmark of the Search read API against a seeded synthetic catalog (docs/performance/search-scale-benchmark.md).
// Driven by scripts/ci/search-bench.sh; reads the manifest that loadtest/search-bench/seed.py wrote for the current
// catalog size.
//
//   MODE=latency                one VU runs every scenario in turn on every implementation in IMPLS, ROUNDS times after
//                               WARMUP rounds: the cost of one request, the implementations measured side by side
//   MODE=throughput MIX=browse  VUS VUs for DURATION on category/search browsing against SEARCH_URL
//   MODE=throughput MIX=home    VUS VUs for DURATION, each iteration one home page view (its two product searches in parallel)
//
// Environment: IMPLS (latency; "name=url,name=url", default "search=$SEARCH_URL"), SEARCH_URL and IMPL (throughput; default
// http://search-service:8080 and "search"), ES_URL (default http://elasticsearch:9200), OUT_DIR (default /out), MODE, MIX,
// ROUNDS, WARMUP, VUS, DURATION, LABEL (catalog size label for the output file).
// Read-only: it only issues searches and opens/closes Elasticsearch point-in-time contexts.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const SEARCH_URL = __ENV.SEARCH_URL || 'http://search-service:8080';
const ES_URL = __ENV.ES_URL || 'http://elasticsearch:9200';
const OUT_DIR = __ENV.OUT_DIR || '/out';
const MODE = __ENV.MODE || 'latency';
const MIX_NAME = __ENV.MIX || 'browse';
const ROUNDS = parseInt(__ENV.ROUNDS || '20', 10);
const WARMUP = parseInt(__ENV.WARMUP || '3', 10);
const VUS = parseInt(__ENV.VUS || '16', 10);
const DURATION = __ENV.DURATION || '45s';
const LABEL = __ENV.LABEL || 'run';

// Implementations under test: latency interleaves all of them, throughput drives one.
const IMPLS = MODE === 'throughput'
  ? [{ name: __ENV.IMPL || 'search', url: SEARCH_URL }]
  : (__ENV.IMPLS || `search=${SEARCH_URL}`).split(',').map((pair) => {
    const [name, url] = pair.split('=');
    return { name, url };
  });

const manifest = JSON.parse(open(`${OUT_DIR}/manifest.json`));
const p = manifest.params;
const expected = manifest.expected_total_products;

// name -> query string; size=20 is added unless the scenario sets its own. The browsing scenarios keep the default
// in_stock=true like the category and search pages; the home ones send exactly what frontend/src/app/(site)/page.tsx sends.
const SCENARIOS = {
  all: '',
  root_category: `category=${p.root_category}`,
  root_price_asc: `category=${p.root_category}&sort=price_asc`,
  leaf_category: `category=${p.leaf_category}`,
  q_broad: `q=${encodeURIComponent(p.q_broad)}`,
  q_narrow: `q=${encodeURIComponent(p.q_narrow)}`,
  filtered: `category=${p.root_category}&filter=${encodeURIComponent(p.filter)}&price_max=${p.price_max}`,
  home_newest: 'sort=newest&size=8&in_stock=false',
  home_promotion: 'has_promotion=true&sort=newest&size=8&in_stock=false',
};
// Scenario whose total_products the seed computed; the full-text ones depend on the analyzer, so they are reported only.
const EXPECTED_KEY = { all: 'all', root_category: 'root_category', root_price_asc: 'root_category',
  leaf_category: 'leaf_category', filtered: 'filtered', home_newest: 'home_newest', home_promotion: 'home_promotion' };
// MIX=browse weights: browsing a category dominates. Full-catalog listings (all, home) are left out so the run measures
// browsing alone; MIX=home measures the home page on its own.
const BROWSE_MIX = [['leaf_category', 30], ['root_category', 20], ['q_broad', 15], ['q_narrow', 15], ['filtered', 15], ['root_page2', 5]];

// home_page is the wait of one home page view: the slower of its two parallel product searches.
const SEARCH_NAMES = [...Object.keys(SCENARIOS), 'root_page2', 'facet_root', 'home_page'];
const KEYS = [...IMPLS.flatMap((impl) => SEARCH_NAMES.map((name) => key(impl.name, name))), key('es', 'floor_root')];
const latency = Object.fromEntries(KEYS.map((k) => [k, new Trend(`lat_${k}`, true)]));
const failures = Object.fromEntries(KEYS.map((k) => [k, new Rate(`fail_${k}`)]));
// total_products as answered; handleSummary runs outside the VUs, so it can only see metrics.
const totals = Object.fromEntries(KEYS.map((k) => [k, new Trend(`total_${k}`)]));
const totalMismatch = new Counter('total_products_mismatch');
const reportedErrors = new Set();

export const options = MODE === 'throughput'
  ? { scenarios: { mix: { executor: 'constant-vus', vus: VUS, duration: DURATION, gracefulStop: '30s' } } }
  : { scenarios: { sequential: { executor: 'per-vu-iterations', vus: 1, iterations: WARMUP + ROUNDS, maxDuration: '120m' } } };
options.summaryTrendStats = ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'];
options.discardResponseBodies = false;

function key(impl, name) {
  return `${impl}__${name}`;
}

function reportError(k, response) {
  let code = 'none';
  try { code = response.json('code') || 'none'; } catch (e) { /* not JSON */ }
  const marker = `${k} ${response.status} ${code}`;
  if (!reportedErrors.has(marker)) {
    reportedErrors.add(marker); // once per VU and kind; the fail_ rate carries the counts
    console.warn(`search-scale error: scenario=${k} status=${response.status} code=${code}`);
  }
}

function record(k, response, measure) {
  const ok = response.status === 200;
  if (measure) {
    latency[k].add(response.timings.duration);
    failures[k].add(!ok);
  }
  if (!ok) reportError(k, response);
  return ok;
}

function searchRequest(impl, name, query) {
  const withSize = /(^|&)size=/.test(query) ? query : `size=20${query ? '&' + query : ''}`;
  return ['GET', `${impl.url}/api/v2/search/products?${withSize}`, null,
    { tags: { scenario: name, impl: impl.name }, timeout: '30s' }];
}

// Records total_products and checks it against the seed's number; returns the body of a 200.
function checkTotals(k, name, response, measure) {
  if (response.status !== 200) return null;
  const body = response.json();
  if (measure) totals[k].add(body.total_products);
  const expectedKey = EXPECTED_KEY[name];
  if (expectedKey !== undefined && measure) {
    const matches = check(body, { [`${k} total_products`]: (b) => b.total_products === expected[expectedKey] });
    if (!matches) totalMismatch.add(1, { scenario: k });
  }
  return body;
}

function search(impl, name, query, measure) {
  const [, url, , params] = searchRequest(impl, name, query);
  const response = http.get(url, params);
  const k = key(impl.name, name);
  record(k, response, measure);
  return checkTotals(k, name, response, measure);
}

// Page 2 of the root category: the cursor carries the snapshot; the grouping rescans it, the projection seeks into it.
function page2(impl, measure) {
  const first = search(impl, 'root_category', SCENARIOS.root_category, false);
  if (!first || !first.next_cursor) return;
  search(impl, 'root_page2', `${SCENARIOS.root_category}&cursor=${encodeURIComponent(first.next_cursor)}`, measure);
}

// The facet panel of the root category page: one aggregation over the whole branch (CategoryFacetService).
function facets(impl, measure) {
  const response = http.get(`${impl.url}/api/v2/search/categories/${p.root_category_id}/facets`,
    { tags: { scenario: 'facet_root', impl: impl.name }, timeout: '30s' });
  record(key(impl.name, 'facet_root'), response, measure);
}

// One home page view as the Next.js server renders it: both product searches at once (Promise.allSettled). The page
// waits for the slower one; a failure of either shows an error block on the page.
function homePage(impl, measure) {
  const responses = http.batch([searchRequest(impl, 'home_newest', SCENARIOS.home_newest),
    searchRequest(impl, 'home_promotion', SCENARIOS.home_promotion)]);
  const ok = responses.every((response) => response.status === 200);
  const k = key(impl.name, 'home_page');
  if (measure) {
    latency[k].add(Math.max(...responses.map((response) => response.timings.duration)));
    failures[k].add(!ok);
  }
  ['home_newest', 'home_promotion'].forEach((name, i) => {
    const member = key(impl.name, name);
    // MODE=latency already measures each search on its own; under load, the per-search numbers come from here.
    if (MODE === 'throughput') record(member, responses[i], measure);
    else if (responses[i].status !== 200) reportError(member, responses[i]);
    checkTotals(member, name, responses[i], measure);
  });
}

// Reference only, not equivalent semantics: what Elasticsearch needs to return 20 SKU documents for the same
// filters, natively sorted and counted. It is the floor a product-level index would aim at, not a Search result.
function esFloor(measure) {
  const body = JSON.stringify({
    size: 20, track_total_hits: true,
    query: { bool: { filter: [
      { term: { is_deleted: false } }, { term: { product_status: 'ACTIVE' } }, { term: { sku_status: 'ACTIVE' } },
      { term: { is_in_stock: true } }, { term: { category_ancestor_ids: p.root_category_id } },
    ] } },
    sort: [{ selling_price_vnd: 'asc' }, { sku_id: 'asc' }],
  });
  const response = http.post(`${ES_URL}/products/_search`, body,
    { headers: { 'Content-Type': 'application/json' }, tags: { scenario: 'es_floor_root' }, timeout: '30s' });
  record(key('es', 'floor_root'), response, measure);
}

function run(impl, name, measure) {
  if (name === 'root_page2') return page2(impl, measure);
  if (name === 'facet_root') return facets(impl, measure);
  if (name === 'home_page') return homePage(impl, measure);
  return search(impl, name, SCENARIOS[name], measure);
}

function pickWeighted(mix) {
  const total = mix.reduce((sum, [, weight]) => sum + weight, 0);
  let roll = Math.random() * total;
  for (const [name, weight] of mix) {
    roll -= weight;
    if (roll < 0) return name;
  }
  return mix[0][0];
}

export default function () {
  if (MODE === 'throughput') {
    if (MIX_NAME === 'home') homePage(IMPLS[0], true);
    else run(IMPLS[0], pickWeighted(BROWSE_MIX), true);
    return;
  }
  const measure = __ITER >= WARMUP;
  for (const name of SEARCH_NAMES) {
    for (const impl of IMPLS) run(impl, name, measure); // side by side, so both see the same machine state
  }
  esFloor(measure);
}

export function handleSummary(data) {
  const metric = (name) => data.metrics[name] && data.metrics[name].values;
  const scenarios = {};
  for (const k of KEYS) {
    const lat = metric(`lat_${k}`);
    if (!lat || !lat.count) continue;
    const fail = metric(`fail_${k}`);
    const total = metric(`total_${k}`);
    scenarios[k] = {
      count: lat.count, med_ms: lat.med, p95_ms: lat['p(95)'], p99_ms: lat['p(99)'], max_ms: lat.max,
      fail_rate: fail ? fail.rate : 0, total_products: total && total.count ? total.max : null,
    };
  }
  const runName = MODE === 'throughput' ? `${MODE}-${MIX_NAME}-${IMPLS[0].name}` : MODE;
  const reqs = metric('http_reqs');
  const iterations = metric('iterations');
  const result = {
    label: LABEL, mode: MODE, mix: MODE === 'throughput' ? MIX_NAME : null, impls: IMPLS.map((impl) => impl.name),
    products: manifest.products, sku_documents: manifest.sku_documents, expected_total_products: expected,
    http_reqs: reqs ? reqs.count : 0, req_per_s: reqs ? reqs.rate : 0, iterations_per_s: iterations ? iterations.rate : 0,
    total_products_mismatches: metric('total_products_mismatch') ? metric('total_products_mismatch').count : 0,
    scenarios,
  };
  const lines = [`${LABEL} ${runName}: products=${manifest.products} skus=${manifest.sku_documents} `
    + `req/s=${result.req_per_s.toFixed(1)} iterations/s=${result.iterations_per_s.toFixed(1)} mismatches=${result.total_products_mismatches}`];
  for (const [k, s] of Object.entries(scenarios)) {
    lines.push(`  ${k.padEnd(28)} n=${String(s.count).padStart(5)} med=${s.med_ms.toFixed(0).padStart(6)} `
      + `p95=${s.p95_ms.toFixed(0).padStart(6)} p99=${s.p99_ms.toFixed(0).padStart(6)} ms fail=${(s.fail_rate * 100).toFixed(1)}% `
      + `total=${s.total_products}`);
  }
  return {
    stdout: lines.join('\n') + '\n',
    [`${OUT_DIR}/k6-${LABEL}-${runName}.json`]: JSON.stringify(result, null, 2),
  };
}

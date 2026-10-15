// Load test of the anonymous storefront read paths through the API gateway (docs/performance/load-test.md).
//
//   scripts/ci/loadtest.sh                 # runs this against the compose stack
//
// The paths are the hot ones of docs/gateway and docs/product-detail-bff: product detail (the BFF composing Product, Inventory and
// Review under per-dependency budgets), search and the category tree (Elasticsearch). They are read-only and anonymous, so the
// test needs no accounts and changes no data.
//
// Environment: BASE_URL (default http://api-gateway:80), SLUGS (comma separated product slugs; default discovered), VUS, DURATION.
// PDP_URL and SEARCH_URL default to BASE_URL; point them at product-detail-bff:8080 and search-service:8080 to measure the
// services themselves, because the gateway's rate limiter (429 above roughly 200 requests/s from one client) caps what a single
// load generator can push through it.
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://api-gateway:80';
const VUS = parseInt(__ENV.VUS || '20', 10);
const DURATION = __ENV.DURATION || '60s';
const PDP_URL = __ENV.PDP_URL || BASE_URL; // an empty value from the runner falls back too
const SEARCH_URL = __ENV.SEARCH_URL || BASE_URL;

const degraded = new Rate('pdp_degraded');       // 200 whose Review or Inventory section is not AVAILABLE
const unavailable = new Rate('pdp_unavailable'); // 503 from the BFF
const retryAfterOn503 = new Rate('retry_after_on_503');
const retryAfterOn429 = new Rate('retry_after_on_429');
const searchLatency = new Trend('search_ms', true);
const detailLatency = new Trend('detail_ms', true);
const categoriesLatency = new Trend('categories_ms', true);
const errors5xx = new Counter('server_errors_other_than_503');

export const options = {
  scenarios: {
    mixed_reads: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '10s',
    },
  },
  // The thresholds are the budgets the design states, not aspirations: a gateway timeout is 10 s for these routes and the BFF
  // cuts its slowest dependency at 400 ms (Product Detail BFF ADR-004), so p99 of a full page must stay well under a second on a
  // healthy local stack. They are starting values; docs/performance/load-test.md records what was measured.
  thresholds: {
    'http_req_failed{kind:detail}': ['rate<0.01'],
    'http_req_failed{kind:search}': ['rate<0.01'],
    'http_req_failed{kind:categories}': ['rate<0.01'],
    detail_ms: ['p(95)<800', 'p(99)<1500'],
    search_ms: ['p(95)<800', 'p(99)<1500'],
    categories_ms: ['p(95)<500', 'p(99)<1000'],
    server_errors_other_than_503: ['count==0'],
    // Empty thresholds only make k6 print these sub-metrics: how many answers of each status the run got.
    'http_reqs{status:200}': [],
    'http_reqs{status:404}': [],
    'http_reqs{status:429}': [],
    'http_reqs{status:500}': [],
    'http_reqs{status:502}': [],
    'http_reqs{status:503}': [],
    'http_reqs{status:504}': [],
    'http_reqs{status:0}': [],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const configured = (__ENV.SLUGS || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (configured.length > 0) return { slugs: configured };
  // Discover real slugs from the search index, so the test works on whatever catalog the stack holds.
  const res = http.get(`${SEARCH_URL}/api/v2/search/products?limit=20`);
  let slugs = [];
  try {
    slugs = res.json("items").map((item) => item.product_slug).filter(Boolean);
  } catch (e) { /* fall through */ }
  if (slugs.length === 0) throw new Error(`no product slugs found at ${BASE_URL}; set SLUGS`);
  return { slugs };
}

const terms = ['dien thoai', 'laptop', 'tai nghe', 'ao', 'giay', 'may tinh', 'sach', 'dong ho'];

export default function (data) {
  const roll = Math.random();
  if (roll < 0.6) {
    group('product detail', () => {
      const slug = data.slugs[Math.floor(Math.random() * data.slugs.length)];
      const res = http.get(`${PDP_URL}/api/v1/storefront/products/${slug}`, { tags: { kind: 'detail' } });
      detailLatency.add(res.timings.duration);
      unavailable.add(res.status === 503);
      if (res.status === 429) retryAfterOn429.add(res.headers['Retry-After'] !== undefined);
      if (res.status === 503) retryAfterOn503.add(res.headers['Retry-After'] !== undefined);
      if (res.status >= 500 && res.status !== 503) errors5xx.add(1);
      check(res, { 'detail is 200 or a declared 503/404': (r) => [200, 404, 503].includes(r.status) });
      if (res.status === 200) {
        let body;
        try { body = res.json(); } catch (e) { body = {}; }
        const review = body.review_section ? body.review_section.status : 'AVAILABLE';
        const inventory = body.inventory_section ? body.inventory_section.status : 'AVAILABLE';
        degraded.add(review !== 'AVAILABLE' || inventory !== 'AVAILABLE');
      }
    });
  } else if (roll < 0.9) {
    group('search', () => {
      const q = terms[Math.floor(Math.random() * terms.length)];
      const res = http.get(`${SEARCH_URL}/api/v2/search/products?q=${encodeURIComponent(q)}&limit=20`, { tags: { kind: 'search' } });
      searchLatency.add(res.timings.duration);
      if (res.status >= 500 && res.status !== 503) errors5xx.add(1);
      check(res, { 'search is 200': (r) => r.status === 200 });
    });
  } else {
    group('categories', () => {
      const res = http.get(`${SEARCH_URL}/api/v2/search/categories`, { tags: { kind: 'categories' } });
      categoriesLatency.add(res.timings.duration);
      if (res.status >= 500 && res.status !== 503) errors5xx.add(1);
      check(res, { 'categories is 200': (r) => r.status === 200 });
    });
  }
  sleep(Math.random() * 0.2);
}

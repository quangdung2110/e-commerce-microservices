import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BASE_URL;
const slug = __ENV.FIXTURE_SLUG;
const rate = Number(__ENV.REQUEST_RATE);
const seconds = Number(__ENV.DURATION_SECONDS);
const maxVUs = Number(__ENV.MAX_VUS);
const fixture = __ENV.ISOLATED_FIXTURE === 'true';
if ((!base.startsWith('https://') && !(fixture && base === 'http://fixture:8080')) || !/^[a-z0-9][a-z0-9-]{0,119}$/.test(slug)) {
  throw new Error('Invalid bounded read-only test target');
}
if (!Number.isInteger(rate) || rate < 1 || rate > 20 || seconds < 10 || seconds > 900 || maxVUs < 1 || maxVUs > 20) {
  throw new Error('Load exceeds the reviewed staging safety envelope');
}

export const options = {
  scenarios: { catalog: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${seconds}s`,
    preAllocatedVUs: maxVUs, maxVUs, gracefulStop: '10s' } },
  thresholds: { http_req_failed: [`rate<=${__ENV.MAX_ERROR_RATE}`], checks: ['rate==1'],
    http_req_duration: [`p(95)<=${__ENV.MAX_P95_MS}`], dropped_iterations: ['count==0'] },
  maxRedirects: 0,
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
};

export default function () {
  const response = http.get(`${base}/api/v1/storefront/products/${slug}`, {
    redirects: 0, timeout: '3s', tags: { name: 'GET public product detail' },
    headers: { Accept: 'application/json', 'X-Request-Id': `ops-read-${__VU}-${__ITER}` },
  });
  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'product fixture returned': (r) => {
      try { const body = r.json(); return typeof body.product_id === 'string' && body.slug === slug; }
      catch (_) { return false; }
    },
  });
}

export function handleSummary(data) {
  const metric = (name, field) => data.metrics[name]?.values[field] ?? 0;
  const report = { requests: metric('http_reqs', 'count'), iterations: metric('iterations', 'count'),
    p95_ms: metric('http_req_duration', 'p(95)'), error_rate: metric('http_req_failed', 'rate'),
    check_rate: metric('checks', 'rate'), dropped_iterations: metric('dropped_iterations', 'count') };
  return { stdout: `OPERATIONS_PERFORMANCE=${JSON.stringify(report)}\n` };
}

// Open-arrival load: identical for both jars. Lifecycle uses the production CUTOVER stage
// via a separately packaged test fixture; this script sends ONLY public reads.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
const manifest = JSON.parse(open('/out/manifest.json'));
const failures = new Rate('read_failure');
const duration = new Trend('read_duration', true);
const good = new Counter('successful_reads');
const goodViews = new Counter('successful_views');
const goodDuration = new Trend('successful_view_duration', true);
const mismatches = new Counter('total_mismatch');
const phaseFailures = new Rate('phase_failure');
const rate = Number(__ENV.RATE || 30);
const mix = __ENV.MIX || 'home';
export const options = {
  scenarios: { reads: { executor: 'constant-arrival-rate', rate, timeUnit: '1s',
    duration: __ENV.DURATION || '45s', preAllocatedVUs: 128, maxVUs: 256, gracefulStop: '30s' } },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};
const queries = [
  `category=${manifest.params.leaf_category}`, `category=${manifest.params.root_category}`,
  `q=${encodeURIComponent(manifest.params.q_broad)}`, `q=${encodeURIComponent(manifest.params.q_narrow)}`,
  `category=${manifest.params.root_category}&filter=${encodeURIComponent(manifest.params.filter)}&price_max=${manifest.params.price_max}`,
];
export default function () {
  const requests = mix === 'browse'
    ? [`size=20&${queries[exec.scenario.iterationInTest % queries.length]}`]
    : ['sort=newest&size=8&in_stock=false', 'has_promotion=true&sort=newest&size=8&in_stock=false'];
  const started = Date.now();
  const responses = http.batch(requests.map(q => ['GET', `http://search:8080/api/v2/search/products?${q}`,
    null, { timeout: '30s' }]));
  const phase = String(Math.floor((Date.now() - Number(__ENV.EPOCH_MS || Date.now())) / 1000 / 10) * 10);
  let failed = false;
  for (let i = 0; i < responses.length; i++) {
    const r = responses[i];
    failed = failed || r.status !== 200;
    if (r.status === 200) {
      good.add(1);
      if (mix !== 'browse') {
        const key = i === 0 ? 'home_newest' : 'home_promotion';
        if (r.json('total_products') !== manifest.expected_total_products[key]) mismatches.add(1);
      }
    }
  }
  failures.add(failed);
  duration.add(Date.now() - started);
  if (!failed) { goodViews.add(1); goodDuration.add(Date.now() - started); }
  phaseFailures.add(failed, { phase });
}
export function handleSummary(data) {
  return { [`/out/${__ENV.CASE}.json`]: JSON.stringify(data, null, 2) };
}

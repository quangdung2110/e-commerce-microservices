import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const errorRate = new Rate('errors');

// --- Test Scenarios ---

export const options = {
  scenarios: {
    // Normal traffic: gradual ramp up and down
    normal_traffic: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m', target: 50 },
        { duration: '30s', target: 0 },
      ],
    },
    // Flash sale spike: rapid ramp up
    flash_sale: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { duration: '10s', target: 100 },
        { duration: '30s', target: 100 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<50', 'p(99)<100'],
    http_req_failed: ['rate<0.01'],
    errors: ['rate<0.01'],
  },
};

// --- Test Functions ---

function getRandomUserID() {
  return `user-${Math.floor(Math.random() * 10000)}`;
}

function getRandomSKU() {
  return `sku-${Math.floor(Math.random() * 1000)}`;
}

function getHeaders(userID) {
  return {
    'Content-Type': 'application/json',
    'X-User-Id': userID,
    'X-Request-Id': `req-${Math.random().toString(36).substring(7)}`,
  };
}

export default function () {
  const userID = getRandomUserID();
  const headers = getHeaders(userID);

  // --- GET CART ---
  {
    const res = http.get(`${BASE_URL}/api/v1/carts/mine`, { headers });
    const ok = check(res, {
      'GET /carts/mine status 200': (r) => r.status === 200,
      'GET /carts/mine has items field': (r) => {
        try {
          return JSON.parse(r.body).items !== undefined;
        } catch {
          return false;
        }
      },
      'GET /carts/mine has total_unique_skus': (r) => {
        try {
          return JSON.parse(r.body).total_unique_skus !== undefined;
        } catch {
          return false;
        }
      },
    });
    if (!ok) {
      errorRate.add(1);
    } else {
      errorRate.add(0);
    }
    sleep(Math.random() * 2 + 0.5);
  }

  // --- ADD ITEM ---
  {
    const payload = JSON.stringify({
      sku_id: getRandomSKU(),
      quantity: Math.floor(Math.random() * 5) + 1,
    });
    const res = http.post(`${BASE_URL}/api/v1/carts/items`, payload, { headers });
    const ok = check(res, {
      'POST /carts/items status 200': (r) => r.status === 200,
      'POST /carts/items has success field': (r) => {
        try {
          return JSON.parse(r.body).success !== undefined;
        } catch {
          return false;
        }
      },
      'POST /carts/items has sku_id field': (r) => {
        try {
          return JSON.parse(r.body).sku_id !== undefined;
        } catch {
          return false;
        }
      },
    });
    if (!ok) {
      errorRate.add(1);
    } else {
      errorRate.add(0);
    }
    sleep(Math.random() * 1 + 0.5);
  }

  // --- GET CART AFTER ADD ---
  {
    const res = http.get(`${BASE_URL}/api/v1/carts/mine`, { headers });
    const ok = check(res, {
      'GET /carts/mine after add status 200': (r) => r.status === 200,
    });
    if (!ok) {
      errorRate.add(1);
    } else {
      errorRate.add(0);
    }
    sleep(Math.random() * 2 + 0.5);
  }

  // --- DELETE ITEM (optional, ~30% of requests) ---
  if (Math.random() < 0.3) {
    const payload = JSON.stringify({
      sku_ids: [getRandomSKU()],
    });
    const res = http.del(`${BASE_URL}/api/v1/carts/items`, payload, { headers });
    const ok = check(res, {
      'DELETE /carts/items status 200': (r) => r.status === 200 || r.status === 404,
    });
    if (!ok) {
      errorRate.add(1);
    } else {
      errorRate.add(0);
    }
    sleep(Math.random() * 1 + 0.5);
  }
}

// --- Setup & Teardown ---

export function setup() {
  console.log('Starting load test against: ' + BASE_URL);
  console.log('Test started at: ' + new Date().toISOString());

  // Verify the service is up
  const res = http.get(`${BASE_URL}/health`);
  if (res.status !== 200) {
    throw new Error(`Service health check failed: ${res.status}`);
  }

  return { startTime: Date.now() };
}

export function teardown(data) {
  console.log('Load test completed');
  console.log('Duration: ' + ((Date.now() - data.startTime) / 1000) + 's');
}

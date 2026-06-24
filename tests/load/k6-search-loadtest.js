import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 20 }, // Ramp up to 20 users
    { duration: '20s', target: 20 }, // Stay at 20 users
    { duration: '10s', target: 0 },  // Ramp down to 0 users
  ],
  thresholds: {
    'http_req_duration{scenario:products}': ['p(99)<200'],
    'http_req_duration{scenario:suggest}': ['p(99)<25'],
    'http_req_failed': ['rate<0.01'],
  },
};

const BASE_URL = 'http://host.docker.internal:8081';

export default function () {
  const rand = Math.random();

  if (rand < 0.6) {
    // 60% Search Products
    let keywords = ['iphone', 'samsung', 'macbook', 'dien thoai', 'laptop'];
    let term = keywords[Math.floor(Math.random() * keywords.length)];
    let res = http.get(`${BASE_URL}/api/v1/search/products?q=${term}&size=20`, {
      tags: { scenario: 'products' },
    });
    check(res, { 'products status is 200': (r) => r.status === 200 });

    // Simulate clicking next page using cursor if available
    if (res.status === 200) {
      let body = JSON.parse(res.body);
      if (body.next_cursor) {
        let nextRes = http.get(`${BASE_URL}/api/v1/search/products?q=${term}&size=20&cursor=${encodeURIComponent(body.next_cursor)}`, {
          tags: { scenario: 'products' },
        });
        check(nextRes, { 'products status is 200 (page 2)': (r) => r.status === 200 });
      }
    }
  } else if (rand < 0.9) {
    // 30% Suggest
    let prefixes = ['iph', 'sam', 'mac', 'dien', 'lap'];
    let prefix = prefixes[Math.floor(Math.random() * prefixes.length)];
    let res = http.get(`${BASE_URL}/api/v1/search/suggest?q=${prefix}`, {
      tags: { scenario: 'suggest' },
    });
    check(res, {
      'suggest status is 200': (r) => r.status === 200,
    });
  } else if (rand < 0.95) {
    // 5% Categories
    let res = http.get(`${BASE_URL}/api/v1/search/categories`, {
      tags: { scenario: 'categories' },
    });
    check(res, {
      'categories status is 200': (r) => r.status === 200,
    });
  } else {
    // 5% Facets
    let categories = ['c-smartphone', 'c-laptop', 'c-tablet'];
    let category = categories[Math.floor(Math.random() * categories.length)];
    let res = http.get(`${BASE_URL}/api/v1/search/facets/${category}`, {
      tags: { scenario: 'facets' },
    });
    check(res, {
      'facets status is 200': (r) => r.status === 200,
    });
  }

  sleep(0.5);
}

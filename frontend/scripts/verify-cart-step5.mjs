/** Local smoke test for Step 5. Uses the disposable buyer account from
 * create-dev-account.sh, requires its cart to be empty, and removes only QA SKUs.
 * Run in the Node container on ecommerce-network; never point at production.
 */
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const base = process.env.CART_QA_BASE ?? "http://api-gateway";
const email = process.env.DEV_EMAIL ?? "buyer@technova.test";
const password = process.env.DEV_PASSWORD ?? "Terra-Local-Pass-2026";
let token;
async function request(path, { method = "GET", body, key, authenticated = true } = {}) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(authenticated && token ? { Authorization: `Bearer ${token}` } : {}),
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = response.status === 204 ? null : await response.json();
  return { status: response.status, data, headers: response.headers };
}
const path = "/api/v1/carts/mine";
const writePath = `${path}/items`;
const login = await request("/api/v1/auth/sessions", { method: "POST", authenticated: false, body: { email, password } });
assert.equal(login.status, 200, "local buyer login");
token = login.data.access_token;
const baseline = await request(path);
assert.equal(baseline.status, 200);
assert.equal(baseline.data.items.length, 0, "QA requires an empty disposable buyer cart; existing items are not modified");
assert.equal((await request(path, { authenticated: false })).status, 401);
assert.equal(baseline.headers.get("cache-control"), "no-store");
const search = await request("/api/v2/search/products?in_stock=false&size=4", { authenticated: false });
assert.equal(search.status, 200);
const skus = [...new Set(search.data.items.map((item) => item.sku_id))].slice(0, 2);
assert.equal(skus.length, 2, "seed-dev-catalog must provide two ACTIVE SKUs");
let checks = 0;
const check = (label) => { checks += 1; console.log(`PASS ${label}`); };
try {
  const firstKey = randomUUID();
  const added = await request(writePath, { method: "POST", body: { sku_id: skus[0], delta: 2 }, key: firstKey });
  assert.equal(added.status, 201); assert.equal(added.data.quantity, 2);
  const replay = await request(writePath, { method: "POST", body: { sku_id: skus[0], delta: 2 }, key: firstKey });
  assert.equal(replay.status, 201); assert.deepEqual(replay.data, added.data);
  check("POST replay preserves the original response and applies delta once");
  const conflict = await request(writePath, { method: "POST", body: { sku_id: skus[0], delta: 1 }, key: firstKey });
  assert.equal(conflict.status, 409); assert.equal(conflict.data.code, "IDEMPOTENCY_CONFLICT");
  check("changed payload with the same key returns 409");
  const reduced = await request(writePath, { method: "POST", body: { sku_id: skus[0], delta: -1 }, key: randomUUID() });
  assert.equal(reduced.status, 200); assert.equal(reduced.data.quantity, 1);
  const belowOne = await request(writePath, { method: "POST", body: { sku_id: skus[0], delta: -1 }, key: randomUUID() });
  assert.equal(belowOne.status, 422); assert.equal(belowOne.data.code, "QUANTITY_INVALID");
  check("negative delta reduces quantity; reaching zero requires DELETE");
  const clamped = await request(writePath, { method: "POST", body: { sku_id: skus[0], delta: 100 }, key: randomUUID() });
  assert.equal(clamped.status, 200); assert.equal(clamped.data.quantity, 100);
  check("backend authoritative quantity clamps at 100");
  const second = await request(writePath, { method: "POST", body: { sku_id: skus[1], delta: 1 }, key: randomUUID() });
  assert.equal(second.status, 201);
  const cart = await request(path);
  assert.equal(cart.data.total_units, 101); assert.equal(cart.data.total_unique_skus, 2);
  assert.ok(cart.data.items.every((item) => "amount_minor" in item.unit_price_at_add));
  assert.equal(cart.data.catalog_freshness, "CURRENT");
  check("flat items, Money, total_units and catalog_freshness are live v1 fields");
  const deleteKey = randomUUID();
  const removed = await request(writePath, { method: "DELETE", body: { sku_ids: skus }, key: deleteKey });
  assert.equal(removed.status, 200); assert.equal(removed.data.deleted_count, 2);
  const deleteReplay = await request(writePath, { method: "DELETE", body: { sku_ids: skus }, key: deleteKey });
  assert.equal(deleteReplay.status, 200); assert.deepEqual(deleteReplay.data, removed.data);
  assert.equal((await request(path)).data.total_units, 0);
  check("batch DELETE and replay preserve the result; the cart is empty");
} finally {
  const cleanup = await request(writePath, { method: "DELETE", body: { sku_ids: skus }, key: randomUUID() });
  assert.equal(cleanup.status, 200, "remove QA SKUs");
}
console.log(`${checks} live checks passed; QA SKUs removed.`);

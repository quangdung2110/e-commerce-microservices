# Cart Service

Go implementation of the Redis-only Cart Service described in
[`docs/cart`](../../../docs/cart/README.md).

Redis is the **single source of truth**. There is no PostgreSQL, no cold store
and no rehydrate path: a cart key that does not exist is an empty cart. Kafka
carries exactly one outbound event, `CART_ABANDONED`, to Notification Service.

## Contracts

| Interaction | Direction | Contract |
|---|---|---|
| Cart HTTP API | Inbound | [`contracts/http/cart/v1/openapi.yaml`](../../../contracts/http/cart/v1/openapi.yaml) |
| Checkout cart snapshot (`GET /internal/v1/carts/{user_id}/checkout-snapshot`) | Inbound, internal | Same OpenAPI, `getCheckoutCartSnapshot`; only `X-Service-Identity: checkout-service`, no Gateway route |
| `com.ecommerce.order.paid.v1` | Inbound Kafka | [Order Events AsyncAPI v1](../../../contracts/events/order/v1/asyncapi.yaml) |
| `com.ecommerce.auth.user-deleted.v1` | Inbound Kafka | [Auth Events AsyncAPI v1](../../../contracts/events/auth/v1/asyncapi.yaml); purges the user's cart ([ADR-014](../../../docs/cart/decisions/ADR-014-purge-cart-on-user-deleted.md)) |
| Product lookup | Outbound gRPC | [`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../../contracts/grpc/ecommerce/product/v1/product_service.proto) |
| `CART_ABANDONED` | Outbound Kafka | [`contracts/events/cart/v1/asyncapi.yaml`](../../../contracts/events/cart/v1/asyncapi.yaml) |

Run Go through the container wrapper (the machine has no Go toolchain): `bash scripts/go.sh vet ./...` and
`bash scripts/go.sh test ./internal/...`.

Neither the Lua scripts nor the gRPC stubs are authored here:

- `internal/infrastructure/cartredis/scripts/*.lua` are copied verbatim from
  [`docs/cart/scripts`](../../../docs/cart/scripts). `make check-lua` fails the
  build on drift; `make sync-lua` re-copies them.
- `internal/infrastructure/product/pb/` is generated from the canonical proto
  by `make generate` (or `scripts/generate-pb.sh`, which runs it in Docker with the pinned protoc and plugins).
  `pb/PROTO_SHA256` records the hash of the source protos; `make check-pb` and
  `TestGeneratedStubsMatchCanonicalProto` fail when a proto changed and the stubs were not regenerated
  ([Cart ADR-015](../../../docs/cart/decisions/ADR-015-proto-stub-drift-detection.md)).

## Layout

```
cmd/server                              wiring and graceful shutdown
internal/domain                         identity, cart rules, errors, events
internal/domain/port                    the interfaces the app service depends on
internal/service                        application service (the use cases)
internal/api                            HTTP adapter: dto, handler, middleware, router
internal/infrastructure/cartredis       keys, Lua scripts, cart store, outbox streams
internal/infrastructure/product         Product gRPC client, breaker, validation cache
internal/infrastructure/kafka           CloudEvent producer, com.ecommerce.order.paid.v1 consumer
internal/infrastructure/observability   logger and metrics
internal/workers/{marker,relay,trimmer,orderpaid,userdeleted}
```

## The parts worth knowing before changing anything

**All cart writes go through one of four Lua scripts.** A script is the only
transaction boundary Cart has. `upsert_item.lua` updates the item, the meta
hash, the scheduler ZSET, both TTLs and the idempotency record together;
`mark_abandoned.lua` flips the status and writes the outbox entry together;
`purge_cart.lua` deletes a deleted account's items, meta and scheduler member
together. Do not add a bare `HSET` to a cart key.

**Every key one script touches shares a `{bNN}` hash tag.** `bucket =
xxh3_64(canonical_user_id) mod 64`. The bucket count is a data contract, not a
tunable: the service refuses to start if `cart.bucket_count` disagrees with the
compiled value. Changing it needs a new namespace and a migration runbook.

**The API carries a delta, not an absolute quantity.** Redis serialises deltas,
so two clicks that arrive out of order both land. An absolute quantity would
let the older request overwrite the newer total.

**`PRODUCT_SNAPSHOT_REQUIRED` is internal.** The application service calls the
upsert script without a snapshot first. Only when the script asks for one does
it call Product and retry with the same fingerprint. A fresh validation window
therefore costs zero Product calls, however fast the user clicks.

**Product failures behave differently on reads and writes.** A read degrades:
`200` with `catalog_freshness=DEGRADED` and `current_price: null`. A write is
rejected: an unverified SKU does not enter a cart.

**The relay acks only after Kafka acknowledges.** A crash in between replays
the entry, which is safe because the CloudEvent id is derived deterministically
from `outbox_key + stream_id` and Notification deduplicates on it. `XACK` does
not free memory, so the trimmer runs separately and never trims past the oldest
pending entry.

**`com.ecommerce.order.paid.v1` does not touch activity.** Paying for part of a cart is not
browsing, so the abandonment deadline must not move. It also needs no
idempotency record: `HDEL` per SKU is already idempotent, and the path emits no
event.

**`com.ecommerce.auth.user-deleted.v1` purges the whole cart.** The consumer
group `cart-auth-consumer` ignores every other event on `t.auth.events`. The
purge is idempotent, so redelivery needs no record; a Redis failure returns an
error and Kafka redelivers. Pending `CART_ABANDONED` outbox entries are left to
the relay.

## HTTP surface

| Route | Notes |
|---|---|
| `GET /api/v1/carts/mine` | `Cache-Control: no-store`. Never extends a TTL. |
| `POST /api/v1/carts/mine/items` | `Idempotency-Key` required. `201` on create, `200` on update. |
| `DELETE /api/v1/carts/mine/items` | `Idempotency-Key` required. Up to 100 SKUs. |
| `GET /internal/v1/carts/{user_id}/checkout-snapshot` | In-network only (no Gateway route); `X-Service-Identity` on the `cart.internal_callers` allowlist. Not rate limited. |
| `GET /health/live` | Process only. |
| `GET /health/ready` | Redis, the synchronous source of truth for all HTTP reads. |
| `GET /metrics` | Prometheus. |

Identity comes from the `X-User-Id` header the API Gateway injects after
authentication; Traefik strips any client-supplied copy first. Cart derives the
user token, the cart id and the bucket itself and never accepts a `cart_id`
from a client.

## Configuration

`configs/application.yaml`, overridable with `CART_`-prefixed environment
variables where a config path's dots become underscores
(`CART_REDIS_ADDRS`, `CART_PRODUCT_ADDRESS`, `CART_CART_ABANDONED_AFTER`).

The startup validation refuses two mistakes outright: a `bucket_count` that
disagrees with the namespace, and an `active_ttl` that does not exceed
`abandoned_after`. The second one matters because a cart that expires before
the marker reaches it never emits `CART_ABANDONED` and nothing reports the
loss.

It also requires `media.cdn_base_url` and `media.placeholder_url`
(`CART_MEDIA_CDN_BASE_URL`, `CART_MEDIA_PLACEHOLDER_URL`): Product gRPC returns
the image storage key, and Cart stores that key unchanged and builds the public
URL only when it renders `GET /api/v1/carts/mine` (`internal/api/media`). Both
must be absolute URLs, https unless `media.require_https` is `false` (local
docker-compose only), and equal to the Product Detail BFF and Search values.

Production Redis must run with AOF, a replica, TLS and **`maxmemory-policy
noeviction`**. Under `allkeys-lru`, Redis silently discards live carts under
memory pressure and there is no store to recover them from. Product and Kafka
remain dependency-health signals and alert sources, but do not make an
otherwise healthy Cart pod unready: Product reads degrade and Kafka only
delays outbox relay.

## Development

Root stack: `docker compose up -d --build cart-service` from the repository root.
Cart has no published host port; use the authenticated gateway at
`http://localhost:8088/api/v1/carts/mine`. Host port 8085 belongs to Promotion.
Checkout keeps `http://cart-service:8080` within the network. The standalone
service compose is a separate development stack.

```bash
make deps && make test
```

Tests need no service containers: the Lua scripts run against an in-process
Redis (miniredis), which is how the script-level assertions — atomic abandoned
transitions, stale-deadline races, duplicate idempotency keys, crash before
`XACK`, `XAUTOCLAIM` recovery, safe trimming — stay in the unit test suite.

```bash
make docker-up                  # redis, kafka, product-service
make run                        # cart-service against them
make check-lua                  # fail on Lua drift
make generate                   # regenerate the Product gRPC stubs
```

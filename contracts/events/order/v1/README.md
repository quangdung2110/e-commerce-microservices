# Order Events v1 — Review subset

This provider-owned contract specifies only `ORDER_COMPLETED` on `t.order.events`.
It exists because Review must receive an immutable item snapshot to grant purchase
entitlements without synchronously calling Order, Product or Catalog.

`items[]` is mandatory. `variant_name` is nullable for historical orders, but new
Order producers must populate it whenever the original item snapshot is available.
Each `(order_id, product_id, sku_id)` grants at most one Review eligibility; quantity
does not create multiple entitlements. `items[]` is bounded at 200 entries so the
transaction a consumer opens per event is finite. Producers must group by
`(product_id, sku_id)` and send one entry per pair; JSON Schema cannot express
uniqueness over a subset of an object's keys, so this is a producer obligation the
schema states rather than enforces. A consumer that receives a duplicate anyway
deduplicates on that key and keeps the first occurrence in array order, so the
`variant_name` it snapshots is defined rather than arbitrary.

`order_id` is an opaque string owned by Order. Consumers compare it for equality and
must not parse it, sort by it, or derive anything from it; its internal form stays
Order's decision. It is deliberately not a JSON number: JSON numbers are IEEE-754
doubles in JavaScript, so an int64 above 2^53-1 is silently rounded and the identifier
is corrupted with no error. `subject` carries the same value as `orders/{order_id}`.

Inventory currently emits a different value for the same order (`"order-10001"` where
Order emits `"10001"`). Both are opaque strings, so neither contract is invalid, but
the two streams cannot be correlated until one owner adopts the other's form.

Other existing Order events are not defined by this focused contract.

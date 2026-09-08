# Order Events v1 — Review subset

This provider-owned contract specifies only `ORDER_COMPLETED` on `t.order.events`.
It exists because Review must receive an immutable item snapshot to grant purchase
entitlements without synchronously calling Order, Product or Catalog.

`items[]` is mandatory. `variant_name` is nullable for historical orders, but new
Order producers must populate it whenever the original item snapshot is available.
Each `(order_id, product_id, sku_id)` grants at most one Review eligibility; quantity
does not create multiple entitlements.

Other existing Order events are not defined by this focused contract.

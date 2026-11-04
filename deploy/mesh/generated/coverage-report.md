# AuthorizationPolicy coverage report

Generated with the manifests; do not edit. Each row of runtime-contract section 5.2 becomes one rule. A rule marked
**broader** would allow more paths than the contract states. URI templates preserve segment boundaries.
Authorization uses authenticated principals, listener ports and paths; attribution headers cannot grant access.
Metrics grants are generated separately from topology: Prometheus, GET, exact metrics path and HTTP port only.

| Destination | Principals | Methods | Contract path | Istio pattern | Match |
|---|---|---|---|---|---|
| auth-service | edge/traefik | any | `/api/v1/auth/**` | `/api/v1/auth` | exact |
| auth-service | edge/traefik | any | `/api/v1/auth/**` | `/api/v1/auth/*` | exact |
| auth-service | edge/traefik, edge/gateway-auth | GET | `/.well-known/**` | `/.well-known` | exact |
| auth-service | edge/traefik, edge/gateway-auth | GET | `/.well-known/**` | `/.well-known/*` | exact |
| auth-service | ecommerce/notification-service | POST | `/internal/v1/auth/notification-deliveries/*/content` | `/internal/v1/auth/notification-deliveries/{*}/content` | exact |
| user-service | edge/traefik | any | `/api/v1/users/**` | `/api/v1/users` | exact |
| user-service | edge/traefik | any | `/api/v1/users/**` | `/api/v1/users/*` | exact |
| user-service | ecommerce/checkout-service | GET | `/internal/v1/users/*/addresses/*/checkout-snapshot` | `/internal/v1/users/{*}/addresses/{*}/checkout-snapshot` | exact |
| cart-service | edge/traefik | any | `/api/v1/carts/**` | `/api/v1/carts` | exact |
| cart-service | edge/traefik | any | `/api/v1/carts/**` | `/api/v1/carts/*` | exact |
| cart-service | ecommerce/checkout-service | GET | `/internal/v1/carts/*/checkout-snapshot` | `/internal/v1/carts/{*}/checkout-snapshot` | exact |
| product-service | edge/traefik | any | `/api/v1/products/**` | `/api/v1/products` | exact |
| product-service | edge/traefik | any | `/api/v1/products/**` | `/api/v1/products/*` | exact |
| product-service | edge/traefik | any | `/api/v1/categories/**` | `/api/v1/categories` | exact |
| product-service | edge/traefik | any | `/api/v1/categories/**` | `/api/v1/categories/*` | exact |
| product-service | edge/traefik | any | `/api/v1/attributes/**` | `/api/v1/attributes` | exact |
| product-service | edge/traefik | any | `/api/v1/attributes/**` | `/api/v1/attributes/*` | exact |
| product-service | ecommerce/search-service | any | `/internal/v1/products/export` | `/internal/v1/products/export` | exact |
| product-service | ecommerce/search-service | any | `/internal/v1/categories/export` | `/internal/v1/categories/export` | exact |
| product-service | ecommerce/search-service, ecommerce/promotion-service | GET | `/internal/v1/products/*/snapshot` | `/internal/v1/products/{*}/snapshot` | exact |
| product-service | ecommerce/product-detail-bff | GET | `/internal/v1/product-details/*` | `/internal/v1/product-details/{*}` | exact |
| product-service (grpc) | ecommerce/cart-service, ecommerce/checkout-service | POST | `/ecommerce.product.v1.ProductService/*` | `/ecommerce.product.v1.ProductService/{*}` | exact |
| promotion-service | edge/traefik | any | `/api/v1/me/**` | `/api/v1/me` | exact |
| promotion-service | edge/traefik | any | `/api/v1/me/**` | `/api/v1/me/*` | exact |
| promotion-service | edge/traefik | any | `/api/v1/promotion-discovery` | `/api/v1/promotion-discovery` | exact |
| promotion-service | edge/traefik | any | `/api/v1/admin/**` | `/api/v1/admin` | exact |
| promotion-service | edge/traefik | any | `/api/v1/admin/**` | `/api/v1/admin/*` | exact |
| promotion-service | ecommerce/checkout-service | POST | `/internal/v1/promotion-discount-calculations` | `/internal/v1/promotion-discount-calculations` | exact |
| promotion-service | ecommerce/checkout-service | POST | `/internal/v1/promotion-voucher-options` | `/internal/v1/promotion-voucher-options` | exact |
| promotion-service | ecommerce/order-service | POST | `/internal/v1/promotion-allocation/reservations` | `/internal/v1/promotion-allocation/reservations` | exact |
| promotion-service | ecommerce/order-service | POST | `/internal/v1/promotion-allocation/cancellations` | `/internal/v1/promotion-allocation/cancellations` | exact |
| promotion-service | ecommerce/order-service | POST | `/internal/v1/promotion-allocation/confirmations` | `/internal/v1/promotion-allocation/confirmations` | exact |
| promotion-service | ecommerce/promotion-legacy-migrator | POST | `/internal/v1/promotion-allocation/technical-cancellations` | `/internal/v1/promotion-allocation/technical-cancellations` | exact |
| inventory-service | edge/traefik | any | `/api/v1/admin/inventory/**` | `/api/v1/admin/inventory` | exact |
| inventory-service | edge/traefik | any | `/api/v1/admin/inventory/**` | `/api/v1/admin/inventory/*` | exact |
| inventory-service | ecommerce/checkout-service | POST | `/internal/inventory/v1/allocation-previews` | `/internal/inventory/v1/allocation-previews` | exact |
| inventory-service | ecommerce/order-service | any | `/internal/inventory/v1/reservations/**` | `/internal/inventory/v1/reservations` | exact |
| inventory-service | ecommerce/order-service | any | `/internal/inventory/v1/reservations/**` | `/internal/inventory/v1/reservations/*` | exact |
| inventory-service | ecommerce/order-service | any | `/internal/inventory/v1/refund-scopes/**` | `/internal/inventory/v1/refund-scopes` | exact |
| inventory-service | ecommerce/order-service | any | `/internal/inventory/v1/refund-scopes/**` | `/internal/inventory/v1/refund-scopes/*` | exact |
| inventory-service | ecommerce/order-service | any | `/internal/inventory/v1/commands/*` | `/internal/inventory/v1/commands/{*}` | exact |
| inventory-service | ecommerce/product-detail-bff | POST | `/internal/inventory/v1/sku-availabilities:batch-get` | `/internal/inventory/v1/sku-availabilities:batch-get` | exact |
| inventory-service | ecommerce/search-service | any | `/exports/inventory/v1/**` | `/exports/inventory/v1` | exact |
| inventory-service | ecommerce/search-service | any | `/exports/inventory/v1/**` | `/exports/inventory/v1/*` | exact |
| review-service | edge/traefik | any | `/api/v1/reviews/**` | `/api/v1/reviews` | exact |
| review-service | edge/traefik | any | `/api/v1/reviews/**` | `/api/v1/reviews/*` | exact |
| review-service | edge/traefik | any | `/api/v1/admin/reviews/**` | `/api/v1/admin/reviews` | exact |
| review-service | edge/traefik | any | `/api/v1/admin/reviews/**` | `/api/v1/admin/reviews/*` | exact |
| review-service | ecommerce/product-detail-bff | GET | `/internal/v1/reviews/products/*/pdp-summary` | `/internal/v1/reviews/products/{*}/pdp-summary` | exact |
| review-service | ecommerce/search-service | any | `/internal/v1/reviews/rating-aggregate-exports**` | `/internal/v1/reviews/rating-aggregate-exports*` | exact |
| search-service | edge/traefik | any | `/api/v2/search/**` | `/api/v2/search` | exact |
| search-service | edge/traefik | any | `/api/v2/search/**` | `/api/v2/search/*` | exact |
| product-detail-bff | edge/traefik | GET | `/api/v1/storefront/products/**` | `/api/v1/storefront/products` | exact |
| product-detail-bff | edge/traefik | GET | `/api/v1/storefront/products/**` | `/api/v1/storefront/products/*` | exact |
| checkout-service | edge/traefik | POST | `/api/v1/checkout/preview` | `/api/v1/checkout/preview` | exact |
| checkout-service | edge/traefik | POST | `/api/v1/checkout/submit` | `/api/v1/checkout/submit` | exact |
| checkout-service | edge/traefik | GET | `/api/v1/checkout/submissions/*` | `/api/v1/checkout/submissions/{*}` | exact |
| checkout-service | edge/traefik | GET | `/api/v1/checkout/previews/*/voucher-options` | `/api/v1/checkout/previews/{*}/voucher-options` | exact |
| order-service | edge/traefik | any | `/api/v1/orders/**` | `/api/v1/orders` | exact |
| order-service | edge/traefik | any | `/api/v1/orders/**` | `/api/v1/orders/*` | exact |
| order-service | edge/traefik | any | `/api/v1/admin/orders/**` | `/api/v1/admin/orders` | exact |
| order-service | edge/traefik | any | `/api/v1/admin/orders/**` | `/api/v1/admin/orders/*` | exact |
| order-service | edge/traefik | any | `/api/v1/order-operations/**` | `/api/v1/order-operations` | exact |
| order-service | edge/traefik | any | `/api/v1/order-operations/**` | `/api/v1/order-operations/*` | exact |
| order-service | edge/traefik | any | `/api/v1/admin/order-operations/**` | `/api/v1/admin/order-operations` | exact |
| order-service | edge/traefik | any | `/api/v1/admin/order-operations/**` | `/api/v1/admin/order-operations/*` | exact |
| order-service | ecommerce/checkout-service | POST | `/internal/v1/checkout-order-submissions` | `/internal/v1/checkout-order-submissions` | exact |
| order-service | ecommerce/checkout-service | GET | `/internal/v1/checkout-order-submissions/*` | `/internal/v1/checkout-order-submissions/{*}` | exact |
| order-service | ecommerce/finance-ops, ecommerce/accounting-ops | GET | `/internal/v1/orders/*/financial-reconciliation-case` | `/internal/v1/orders/{*}/financial-reconciliation-case` | exact |
| order-service | ecommerce/finance-ops, ecommerce/accounting-ops | POST | `/internal/v1/orders/*/financial-reconciliation-decisions` | `/internal/v1/orders/{*}/financial-reconciliation-decisions` | exact |
| payment-service | edge/traefik | POST | `/v1/payments/webhook/stripe` | `/v1/payments/webhook/stripe` | exact |
| payment-service | ecommerce/order-service | POST | `/v1/payments/initiate` | `/v1/payments/initiate` | exact |
| payment-service | ecommerce/order-service | POST | `/v1/payments/{transactionId}/cancel` | `/v1/payments/{*}/cancel` | exact |
| payment-service | ecommerce/order-service | POST | `/v1/payments/{transactionId}/refund` | `/v1/payments/{*}/refund` | exact |
| auth-service | ecommerce/notification-service | GET | `/internal/v1/auth/users/*/notification-contact` | `/internal/v1/auth/users/{*}/notification-contact` | exact |
| notification-service | edge/traefik | any | `/api/v1/notifications/**` | `/api/v1/notifications` | exact |
| notification-service | edge/traefik | any | `/api/v1/notifications/**` | `/api/v1/notifications/*` | exact |
| notification-service | ecommerce/order-service, ecommerce/finance-ops | GET | `/internal/v1/notifications/requests/*` | `/internal/v1/notifications/requests/{*}` | exact |
| notification-service | ecommerce/order-service, ecommerce/finance-ops | GET | `/internal/v1/notifications/deliveries` | `/internal/v1/notifications/deliveries` | exact |
| notification-service | ecommerce/notification-ops | any | `/internal/v1/notifications/ops/**` | `/internal/v1/notifications/ops` | exact |
| notification-service | ecommerce/notification-ops | any | `/internal/v1/notifications/ops/**` | `/internal/v1/notifications/ops/*` | exact |
| payment-service | ecommerce/order-service | GET | `/v1/payments/{transactionId}` | `/v1/payments/{*}` | exact |
| payment-service | ecommerce/order-service | GET | `/v1/payments/{transactionId}/client-data` | `/v1/payments/{*}/client-data` | exact |
| payment-service | ecommerce/order-service | GET | `/v1/refunds/{refundTransactionId}` | `/v1/refunds/{*}` | exact |
| payment-service | ecommerce/finance-ops, ecommerce/accounting-ops | any | `/internal/v1/payment/**` | `/internal/v1/payment` | exact |
| payment-service | ecommerce/finance-ops, ecommerce/accounting-ops | any | `/internal/v1/payment/**` | `/internal/v1/payment/*` | exact |
| chat-service | edge/traefik | GET | `/ws/**` | `/ws` | exact |
| chat-service | edge/traefik | GET | `/ws/**` | `/ws/*` | exact |

88 patterns, 0 broader than the contract path.

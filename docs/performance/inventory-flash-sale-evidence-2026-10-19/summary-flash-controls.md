# Inventory Reserve benchmark — 20261019-flash-controls

## flash-60s-flash

| Orders/s | RESERVED/s | Finished/s | SLO | Reserve hot p50 / p95 / p99 ms | Commit hot p50 / p95 / p99 ms | Busy | Unavail. | Timeout | Not reserved | Dropped res / fin | Lock stmt mean ms | Lock waiters mean/max | Hikari pending mean/max | CPU inv / pg % | Correct |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 50.0 | 50.0 | yes | 7.0 / 12.0 / 15.4 | 7.8 / 12.4 / 16.2 | 0 | 0 | 0 | 0 | 0 / 0 | 1.4 | 0.5 / 1 | 0.0 / 0 | 32 / 29 | yes |
| 100 | 94.9 | 93.2 | no | 2 150.3 / 5 240.5 / 5 828.6 | 2 252.4 / 5 330.4 / 5 832.4 | 0 | 1 | 0 | 1 | 308 / 406 | 110.4 | 19.0 / 23 | 84.2 / 176 | 61 / 114 | yes |

## reserve-120s-hot

| Offered/s | RESERVED/s | SLO | Hot p50 / p95 / p99 ms | Cold p50 / p95 / p99 ms | Busy | Unavail. | Timeout | Dropped | Lock stmt mean ms | Lock waiters mean/max | Hikari pending mean/max | CPU inv / pg % | Correct |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 250 | 202.7 | no | 9 309.2 / 11 866.5 / 12 313.9 | — | 0 | 0 | 0 | 5675 | 120.4 | 22.5 / 23 | 140.2 / 176 | 66 / 110 | yes |


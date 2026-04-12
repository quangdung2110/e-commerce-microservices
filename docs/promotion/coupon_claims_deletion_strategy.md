# Coupon Claims Deletion Strategy

## 1. Goal
Keep `coupon_claims` small and query-efficient without breaking audit requirements for active campaigns.

## 2. Deletion Policy
- Apply hard deletion only for campaigns that are ended and out of refund window.
- Suggested retention window: 30 days after campaign end.

## 3. Eligibility Query
```sql
SELECT cc.id
FROM coupon_claims cc
JOIN promotions p ON p.id = cc.promotion_id
WHERE p.end_time < NOW() - INTERVAL '30 days';
```

## 4. Batch Delete
```sql
DELETE FROM coupon_claims
WHERE id IN (
  SELECT cc.id
  FROM coupon_claims cc
  JOIN promotions p ON p.id = cc.promotion_id
  WHERE p.end_time < NOW() - INTERVAL '30 days'
  ORDER BY cc.id
  LIMIT 10000
);
```

## 5. Safety Rules
1. Run during off-peak hours.
2. Batch delete with limited chunk size.
3. Record metrics: rows scanned, rows deleted, execution time.
4. Stop job if replication lag exceeds threshold.

## 6. Non-Goals
- No cold archive is required for `coupon_claims`.
- Do not delete data for active campaigns or campaigns still inside refund window.
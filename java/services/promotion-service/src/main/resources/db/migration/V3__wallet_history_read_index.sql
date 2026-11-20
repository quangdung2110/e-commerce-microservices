-- Owner-scoped keyset listing, including used and expired history.
CREATE INDEX idx_wallets_user_history ON user_coupon_wallets (user_id, id DESC);

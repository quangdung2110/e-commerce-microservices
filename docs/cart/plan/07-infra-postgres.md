# Cart Service — Module 07: Infrastructure — PostgreSQL

**Document:** 07 | **Module:** Infrastructure — PostgreSQL Connection, Repositories, Migrations  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Module 02 (domain models), Module 03 (domain ports)

---

## 1. Mục tiêu Module

Implement infrastructure adapters cho PostgreSQL Cold Store. Module này implement `CartRepository` interface (Module 03) bằng `pgx` + `GORM`. Chứa connection pool management, repository implementations, và database migrations.

---

## 2. Database Schema

### 2.1. Migration File (`internal/infrastructure/postgres/migrations/001_initial_schema.up.sql`)

```sql
-- Cart Service Initial Schema
-- Ref: cart_db_schema.md

-- Table: carts (master record)
CREATE TABLE IF NOT EXISTS carts (
    cart_id                      VARCHAR(255) PRIMARY KEY,
    user_id                      VARCHAR(255) NOT NULL,
    status                       VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    last_event_stream_id         VARCHAR(64) NULL,
    abandoned_event_published_at TIMESTAMPTZ NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_carts_user_id ON carts(user_id);
CREATE INDEX IF NOT EXISTS idx_carts_status ON carts(status);
CREATE INDEX IF NOT EXISTS idx_carts_updated_at ON carts(updated_at);
CREATE INDEX IF NOT EXISTS idx_carts_status_updated ON carts(status, updated_at);

-- Table: cart_items
CREATE TABLE IF NOT EXISTS cart_items (
    item_id              VARCHAR(255) PRIMARY KEY,  -- Format: {cart_id}:{sku_id}
    cart_id              VARCHAR(255) NOT NULL,
    product_id           VARCHAR(255) NOT NULL,
    sku_id               VARCHAR(255) NOT NULL,
    quantity             INT NOT NULL DEFAULT 0,
    unit_price_at_add    DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    product_snapshot     JSONB NOT NULL,
    redis_stream_id      VARCHAR(64) NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id ON cart_items(cart_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_product_id ON cart_items(product_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_sku_id ON cart_items(sku_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_redis_stream_id ON cart_items(redis_stream_id);

-- Note: No FK constraint on cart_id (application-level reference only)
-- Note: No CASCADE DELETE (must delete cart_items manually before carts)
```

### 2.2. Rollback Migration (`001_initial_schema.down.sql`)

```sql
DROP TABLE IF EXISTS cart_items;
DROP TABLE IF EXISTS carts;
```

---

## 3. Connection Management

### 3.1. Connection Setup (`internal/infrastructure/postgres/connection.go`)

```go
package postgres

import (
    "context"
    "fmt"
    "time"

    "github.com/jackc/pgx/v5/pgxpool"
    "gorm.io/driver/postgres"
    "gorm.io/gorm"
    "gorm.io/gorm/logger"
)

// Config holds PostgreSQL connection configuration.
type Config struct {
    Host            string
    Port            int
    User            string
    Password        string
    Name            string
    MaxOpenConns    int
    MaxIdleConns    int
    ConnMaxLifetime time.Duration
    SSLMode         string
}

// NewPgXPool creates a pgx connection pool.
func NewPgXPool(ctx context.Context, cfg Config) (*pgxpool.Pool, error) {
    dsn := fmt.Sprintf(
        "postgres://%s:%s@%s:%d/%s?sslmode=%s",
        cfg.User, cfg.Password, cfg.Host, cfg.Port, cfg.Name, cfg.SSLMode,
    )

    poolConfig, err := pgxpool.ParseConfig(dsn)
    if err != nil {
        return nil, fmt.Errorf("parse config: %w", err)
    }

    poolConfig.MaxConns = int32(cfg.MaxOpenConns)
    poolConfig.MinConns = int32(cfg.MaxIdleConns)
    poolConfig.MaxConnLifetime = cfg.ConnMaxLifetime

    pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
    if err != nil {
        return nil, fmt.Errorf("create pool: %w", err)
    }

    // Verify connectivity
    if err := pool.Ping(ctx); err != nil {
        pool.Close()
        return nil, fmt.Errorf("pgx ping: %w", err)
    }

    return pool, nil
}

// NewGORM creates a GORM DB instance backed by pgx.
func NewGORM(ctx context.Context, cfg Config) (*gorm.DB, error) {
    dsn := fmt.Sprintf(
        "host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
        cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.Name, cfg.SSLMode,
    )

    db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{
        Logger: logger.Default.LogMode(logger.Silent),
    })
    if err != nil {
        return nil, fmt.Errorf("open GORM: %w", err)
    }

    sqlDB, err := db.DB()
    if err != nil {
        return nil, fmt.Errorf("get underlying DB: %w", err)
    }

    sqlDB.SetMaxOpenConns(cfg.MaxOpenConns)
    sqlDB.SetMaxIdleConns(cfg.MaxIdleConns)
    sqlDB.SetConnMaxLifetime(cfg.ConnMaxLifetime)

    return db, nil
}

// HealthChecker interface for health checks.
type HealthChecker interface {
    Ping(ctx context.Context) error
}
```

---

## 4. GORM Models

### 4.1. Database Models (`internal/infrastructure/postgres/models.go`)

```go
package postgres

import (
    "database/sql"
    "encoding/json"
    "time"

    "cart-service/internal/domain/entity"
)

// CartModel is the GORM model for the carts table.
type CartModel struct {
    CartID                     string       `gorm:"primaryKey;column:cart_id;type:varchar(255)"`
    UserID                     string       `gorm:"column:user_id;type:varchar(255);index"`
    Status                     string       `gorm:"column:status;type:varchar(50);index"`
    LastEventStreamID          sql.NullString `gorm:"column:last_event_stream_id;type:varchar(64)"`
    AbandonedEventPublishedAt  sql.NullTime  `gorm:"column:abandoned_event_published_at"`
    CreatedAt                  time.Time     `gorm:"column:created_at"`
    UpdatedAt                  time.Time     `gorm:"column:updated_at;index"`
}

// TableName specifies the table name.
func (CartModel) TableName() string { return "carts" }

// ToEntity converts the GORM model to domain entity.
func (m *CartModel) ToEntity() *entity.Cart {
    cart := &entity.Cart{}
    // Use reflection-free manual mapping
    cart = entity.RestoreCart(
        m.CartID, m.UserID,
        entity.CartStatus(m.Status),
        m.LastEventStreamID.String,
        m.CreatedAt, m.UpdatedAt,
    )
    if m.AbandonedEventPublishedAt.Valid {
        t := m.AbandonedEventPublishedAt.Time
        cart.AbandonedEventPubAt = &t
    }
    return cart
}

// CartItemModel is the GORM model for the cart_items table.
type CartItemModel struct {
    ItemID          string `gorm:"primaryKey;column:item_id;type:varchar(255)"`
    CartID          string `gorm:"column:cart_id;type:varchar(255);index"`
    ProductID       string `gorm:"column:product_id;type:varchar(255);index"`
    SkuID           string `gorm:"column:sku_id;type:varchar(255);index"`
    Quantity        int    `gorm:"column:quantity"`
    UnitPriceAtAdd  float64 `gorm:"column:unit_price_at_add;type:decimal(15,2)"`
    ProductSnapshot []byte  `gorm:"column:product_snapshot;type:jsonb"`
    RedisStreamID   sql.NullString `gorm:"column:redis_stream_id;type:varchar(64);index"`
    CreatedAt       time.Time `gorm:"column:created_at"`
    UpdatedAt       time.Time `gorm:"column:updated_at"`
}

// TableName specifies the table name.
func (CartItemModel) TableName() string { return "cart_items" }

// ToEntity converts the GORM model to domain entity.
func (m *CartItemModel) ToEntity() *entity.CartItem {
    var snapshot entity.ProductSnapshot
    json.Unmarshal(m.ProductSnapshot, &snapshot)

    return entity.RestoreCartItem(
        m.ItemID, m.CartID, m.ProductID, m.SkuID,
        m.Quantity, m.UnitPriceAtAdd,
        snapshot, m.RedisStreamID.String,
        m.CreatedAt, m.UpdatedAt,
    )
}

// ToModel converts a domain entity to GORM model.
func ToCartItemModel(item *entity.CartItem) *CartItemModel {
    snapshotJSON, _ := json.Marshal(item.ProductSnapshot)
    return &CartItemModel{
        ItemID:          item.ItemID(),
        CartID:          item.CartID(),
        ProductID:       item.ProductID(),
        SkuID:           item.SkuID(),
        Quantity:        item.Quantity(),
        UnitPriceAtAdd:  item.UnitPriceAtAdd(),
        ProductSnapshot: snapshotJSON,
        RedisStreamID:   sql.NullString{String: item.StreamID(), Valid: item.StreamID() != ""},
        CreatedAt:       item.CreatedAt(),
        UpdatedAt:       item.UpdatedAt(),
    }
}
```

---

## 5. Cart Repository Implementation

### 5.1. Cart Repository (`internal/infrastructure/postgres/cart_repo.go`)

Implement `CartRepository` interface (Module 03).

```go
package postgres

import (
    "context"
    "fmt"
    "strings"
    "time"

    "gorm.io/gorm"
    "gorm.io/gorm/clause"

    "cart-service/internal/domain/entity"
    "cart-service/internal/domain/port"
)

// CartRepositoryImpl implements port.CartRepository using GORM.
// Ref: cart_db_schema.md
type CartRepositoryImpl struct {
    db *gorm.DB
}

// NewCartRepository creates a new cart repository.
func NewCartRepository(db *gorm.DB) *CartRepositoryImpl {
    return &CartRepositoryImpl{db: db}
}

var _ port.CartRepository = (*CartRepositoryImpl)(nil)

// GetCartStatus implements port.CartRepository.GetCartStatus.
func (r *CartRepositoryImpl) GetCartStatus(ctx context.Context, cartID string) (entity.CartStatus, error) {
    var model CartModel
    if err := r.db.WithContext(ctx).
        Select("status").
        First(&model, "cart_id = ?", cartID).Error; err != nil {
        if err == gorm.ErrRecordNotFound {
            return "", port.ErrCartNotFound
        }
        return "", fmt.Errorf("GetCartStatus: %w", err)
    }
    return entity.CartStatus(model.Status), nil
}

// ListCartsForAbandonedMark implements port.CartRepository.ListCartsForAbandonedMark.
func (r *CartRepositoryImpl) ListCartsForAbandonedMark(
    ctx context.Context, inactiveDays, limit int,
) ([]*entity.Cart, error) {
    var models []CartModel
    threshold := time.Now().UTC().AddDate(0, 0, -inactiveDays)

    if err := r.db.WithContext(ctx).
        Where("status = ? AND updated_at < ?", entity.CartStatusActive, threshold).
        Order("updated_at ASC").
        Limit(limit).
        Find(&models).Error; err != nil {
        return nil, fmt.Errorf("ListCartsForAbandonedMark: %w", err)
    }

    carts := make([]*entity.Cart, len(models))
    for i := range models {
        carts[i] = models[i].ToEntity()
    }
    return carts, nil
}

// UpdateCartStatus implements port.CartRepository.UpdateCartStatus.
// Uses stream_id guard: only updates if new stream_id > current.
func (r *CartRepositoryImpl) UpdateCartStatus(
    ctx context.Context, cartID string, status entity.CartStatus, streamID string,
) (int64, error) {

    result := r.db.WithContext(ctx).
        Model(&CartModel{}).
        Where("cart_id = ? AND (last_event_stream_id IS NULL OR last_event_stream_id < ?)", cartID, streamID).
        Updates(map[string]interface{}{
            "status":                status,
            "last_event_stream_id":  streamID,
            "updated_at":            time.Now().UTC(),
        })

    return result.RowsAffected, result.Error
}

// UpsertCartItem implements port.CartRepository.UpsertCartItem.
// Uses ON CONFLICT DO UPDATE WHERE redis_stream_id < newStreamID.
func (r *CartRepositoryImpl) UpsertCartItem(
    ctx context.Context, item *entity.CartItem, streamID string,
) error {
    model := ToCartItemModel(item)
    model.RedisStreamID = toNullString(streamID)

    return r.db.WithContext(ctx).Clauses(
        clause.OnConflict{
            Columns:   []clause.Column{{Name: "item_id"}},
            DoUpdates: clause.Assignments(map[string]interface{}{
                "quantity":          gorm.Expr("EXCLUDED.quantity"),
                "product_snapshot":  gorm.Expr("EXCLUDED.product_snapshot"),
                "redis_stream_id":   gorm.Expr("EXCLUDED.redis_stream_id"),
                "updated_at":        gorm.Expr("NOW()"),
            }),
            // WHERE redis_stream_id < EXCLUDED.redis_stream_id (partial update)
            Where: clause.Where{
                Exprs: []clause.Expression{
                    clause.Expr{
                        SQL:                "cart_items.redis_stream_id IS NULL OR cart_items.redis_stream_id < ?",
                        Vars:              []interface{}{streamID},
                    },
                },
            },
        },
    ).Create(model).Error
}

// BatchUpsertCartItems implements port.CartRepository.BatchUpsertCartItems.
// Groups items by cart_id and executes per-cart transactions.
func (r *CartRepositoryImpl) BatchUpsertCartItems(
    ctx context.Context, cartID string, items []*entity.CartItem, streamIDs []string,
) error {
    return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
        for i, item := range items {
            model := ToCartItemModel(item)
            sid := streamIDs[i]
            if sid == "" {
                sid = item.StreamID()
            }
            model.RedisStreamID = toNullString(sid)

            err := tx.Clauses(
                clause.OnConflict{
                    Columns:   []clause.Column{{Name: "item_id"}},
                    DoUpdates: clause.Assignments(map[string]interface{}{
                        "quantity":         gorm.Expr("EXCLUDED.quantity"),
                        "product_snapshot": gorm.Expr("EXCLUDED.product_snapshot"),
                        "redis_stream_id":  gorm.Expr("EXCLUDED.redis_stream_id"),
                        "updated_at":       gorm.Expr("NOW()"),
                    }),
                    Where: clause.Where{
                        Exprs: []clause.Expression{
                            clause.Expr{
                                SQL:  "cart_items.redis_stream_id IS NULL OR cart_items.redis_stream_id < ?",
                                Vars: []interface{}{sid},
                            },
                        },
                    },
                },
            ).Create(model).Error

            if err != nil {
                return fmt.Errorf("upsert item %s: %w", item.ItemID(), err)
            }
        }

        // Upsert cart master with ACTIVE status
        err := tx.Exec(`
            INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, NOW(), NOW())
            ON CONFLICT (cart_id) DO UPDATE SET
                status = 'ACTIVE',
                last_event_stream_id = EXCLUDED.last_event_stream_id,
                updated_at = NOW()
            WHERE carts.last_event_stream_id IS NULL OR carts.last_event_stream_id < EXCLUDED.last_event_stream_id
        `, cartID, items[0].CartID(), streamIDs[len(streamIDs)-1]).Error

        return err
    })
}

// DeleteCartItem implements port.CartRepository.DeleteCartItem.
// Hard delete with stream_id guard.
func (r *CartRepositoryImpl) DeleteCartItem(
    ctx context.Context, itemID, streamID string,
) (int64, error) {

    result := r.db.WithContext(ctx).
        Exec(`
            DELETE FROM cart_items
            WHERE item_id = ?
              AND (redis_stream_id IS NULL OR redis_stream_id < ?)
        `, itemID, streamID)

    return result.RowsAffected, result.Error
}

// BatchDeleteCartItems implements port.CartRepository.BatchDeleteCartItems.
func (r *CartRepositoryImpl) BatchDeleteCartItems(
    ctx context.Context, itemIDs []string, streamID string,
) (int64, error) {

    if len(itemIDs) == 0 {
        return 0, nil
    }

    placeholders := make([]string, len(itemIDs))
    args := make([]interface{}, len(itemIDs)+1)
    for i, id := range itemIDs {
        placeholders[i] = "?"
        args[i] = id
    }
    args[len(itemIDs)] = streamID

    query := fmt.Sprintf(`
        DELETE FROM cart_items
        WHERE item_id IN (%s)
          AND (redis_stream_id IS NULL OR redis_stream_id < ?)
    `, strings.Join(placeholders, ","))

    result := r.db.WithContext(ctx).Exec(query, args...)
    return result.RowsAffected, result.Error
}

// UpsertCartMaster implements port.CartRepository.UpsertCartMaster.
func (r *CartRepositoryImpl) UpsertCartMaster(
    ctx context.Context, cart *entity.Cart, streamID string,
) error {
    return r.db.WithContext(ctx).Exec(`
        INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, NOW(), NOW())
        ON CONFLICT (cart_id) DO UPDATE SET
            status = EXCLUDED.status,
            last_event_stream_id = EXCLUDED.last_event_stream_id,
            updated_at = NOW()
        WHERE carts.last_event_stream_id IS NULL OR carts.last_event_stream_id < EXCLUDED.last_event_stream_id
    `, cart.CartID(), cart.UserID(), cart.Status(), streamID).Error
}

// ListCartsForCleanup implements port.CartRepository.ListCartsForCleanup.
func (r *CartRepositoryImpl) ListCartsForCleanup(
    ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, limit int,
) ([]string, error) {
    statusStrings := make([]string, len(statuses))
    for i, s := range statuses {
        statusStrings[i] = string(s)
    }

    var cartIDs []string
    err := r.db.WithContext(ctx).
        Model(&CartModel{}).
        Select("cart_id").
        Where("updated_at < ? AND status IN ?", olderThan, statusStrings).
        Limit(limit).
        Pluck("cart_id", &cartIDs).Error

    return cartIDs, err
}

// HardDeleteCart implements port.CartRepository.HardDeleteCart.
// Deletes cart_items FIRST, then carts (within a transaction).
func (r *CartRepositoryImpl) HardDeleteCart(ctx context.Context, cartID string) error {
    return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
        // Delete cart_items first (no FK cascade)
        if err := tx.Exec("DELETE FROM cart_items WHERE cart_id = ?", cartID).Error; err != nil {
            return fmt.Errorf("delete cart_items: %w", err)
        }
        // Then delete cart
        if err := tx.Exec("DELETE FROM carts WHERE cart_id = ?", cartID).Error; err != nil {
            return fmt.Errorf("delete cart: %w", err)
        }
        return nil
    })
}

// ListPendingAbandonedEvents implements port.CartRepository.ListPendingAbandonedEvents.
func (r *CartRepositoryImpl) ListPendingAbandonedEvents(
    ctx context.Context, olderThan time.Time,
) ([]*entity.Cart, error) {
    var models []CartModel
    if err := r.db.WithContext(ctx).
        Where("status = ? AND abandoned_event_published_at IS NULL AND updated_at >= ?",
            entity.CartStatusAbandoned, olderThan).
        Find(&models).Error; err != nil {
        return nil, err
    }

    carts := make([]*entity.Cart, len(models))
    for i := range models {
        carts[i] = models[i].ToEntity()
    }
    return carts, nil
}

// MarkAbandonedEventPublished implements port.CartRepository.MarkAbandonedEventPublished.
func (r *CartRepositoryImpl) MarkAbandonedEventPublished(ctx context.Context, cartID string) error {
    return r.db.WithContext(ctx).
        Model(&CartModel{}).
        Where("cart_id = ?", cartID).
        Update("abandoned_event_published_at", time.Now().UTC()).Error
}
```

---

## 6. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `connection.go` | `internal/infrastructure/postgres/connection.go` | pgx pool + GORM setup |
| `models.go` | `internal/infrastructure/postgres/models.go` | GORM models |
| `cart_repo.go` | `internal/infrastructure/postgres/cart_repo.go` | CartRepository impl |
| `migrations/001.up.sql` | `internal/infrastructure/postgres/migrations/001_initial_schema.up.sql` | Schema migration |
| `migrations/001.down.sql` | `internal/infrastructure/postgres/migrations/001_initial_schema.down.sql` | Rollback migration |

---

## 7. Checklist triển khai Module 07

```markdown
## 07 - Infrastructure PostgreSQL
- [x] Tạo `internal/infrastructure/postgres/connection.go` với pgx pool + GORM
- [x] Tạo `internal/infrastructure/postgres/models.go` với GORM models + converters
- [x] Tạo `internal/infrastructure/postgres/cart_repo.go` với đầy đủ CRUD operations
- [x] Tạo migration file `001_initial_schema.up.sql`
- [x] Tạo rollback file `001_initial_schema.down.sql`
- [ ] Viết unit tests: stream_id guard logic (mock GORM)
- [ ] Viết unit tests: batch upsert transaction boundary
- [ ] Viết unit tests: hard delete order (cart_items before carts)
- [x] Verify `go build ./internal/infrastructure/postgres` compile thành công
- [ ] Chạy migration trên local DB (docker-compose)
```

---

*Tiếp theo: [08-infra-kafka.md](08-infra-kafka.md) — Kafka producer và consumer infrastructure.*

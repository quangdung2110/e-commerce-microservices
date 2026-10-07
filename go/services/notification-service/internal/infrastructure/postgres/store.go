package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// querier is the subset of pgx shared by *pgxpool.Pool and pgx.Tx, so one
// repository implementation serves both the auto-commit path and WithinTx.
type querier interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

var (
	_ querier = (*pgxpool.Pool)(nil)
	_ querier = (pgx.Tx)(nil)
)

var _ port.Store = (*Store)(nil)

// Store is the Postgres unit of work. Outside WithinTx every repository call
// auto-commits on the pool; inside it, the Tx bundle is bound to one
// transaction so "INSERT inbox_item + mark DELIVERED + INSERT outbox" is one
// commit (04-idempotency.md lớp D).
type Store struct {
	pool   *pgxpool.Pool
	clock  clock.Clock
	logger *zap.Logger
	repos  // bound to the pool
}

// New wires the store on a pool. clock is used only for bookkeeping columns
// (synced_at, updated_at) — every queue decision takes an explicit `now`.
func New(pool *pgxpool.Pool, clk clock.Clock, logger *zap.Logger) *Store {
	if logger == nil {
		logger = zap.NewNop()
	}
	if clk == nil {
		clk = clock.System{}
	}
	return &Store{pool: pool, clock: clk, logger: logger, repos: newRepos(pool, clk)}
}

// Pool exposes the underlying pool for the LISTEN connection and health.
func (s *Store) Pool() *pgxpool.Pool { return s.pool }

// Ping is used by readiness.
func (s *Store) Ping(ctx context.Context) error {
	return s.pool.Ping(ctx)
}

// WithinTx runs fn in one transaction; commit on nil, rollback on error.
func (s *Store) WithinTx(ctx context.Context, fn func(ctx context.Context, tx port.Tx) error) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return mapError(err)
	}
	bundle := newRepos(tx, s.clock)
	if err := fn(ctx, bundle); err != nil {
		// Detached context: the rollback must still run when the request
		// context is already cancelled, otherwise the connection is returned
		// dirty (pgx would close it, wasting the bulkhead's slot).
		rbCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		if rbErr := tx.Rollback(rbCtx); rbErr != nil && !errors.Is(rbErr, pgx.ErrTxClosed) {
			s.logger.Warn("postgres: rollback failed", zap.Error(rbErr))
		}
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return mapError(err)
	}
	return nil
}

// repos is the Tx bundle: one repository per table family, all on the same
// querier.
type repos struct {
	requests       *RequestRepository
	deliveries     *DeliveryRepository
	dedup          *DedupRepository
	preferences    *PreferenceRepository
	suppressions   *SuppressionRepository
	inbox          *InboxRepository
	outbox         *OutboxRepository
	providerEvents *ProviderEventRepository
	ops            *OpsRepository
	maintenance    *MaintenanceRepository
	ordering       *OrderingRepository
	tombstones     *TombstoneRepository
}

var _ port.Tx = repos{}

func newRepos(q querier, clk clock.Clock) repos {
	return repos{
		requests:       &RequestRepository{q: q},
		deliveries:     &DeliveryRepository{q: q},
		dedup:          &DedupRepository{q: q},
		preferences:    &PreferenceRepository{q: q, clock: clk},
		suppressions:   &SuppressionRepository{q: q},
		inbox:          &InboxRepository{q: q},
		outbox:         &OutboxRepository{q: q},
		providerEvents: &ProviderEventRepository{q: q},
		ops:            &OpsRepository{q: q},
		maintenance:    &MaintenanceRepository{q: q},
		ordering:       &OrderingRepository{q: q},
		tombstones:     &TombstoneRepository{q: q},
	}
}

func (r repos) Requests() port.RequestRepository             { return r.requests }
func (r repos) Deliveries() port.DeliveryRepository          { return r.deliveries }
func (r repos) Dedup() port.DedupRepository                  { return r.dedup }
func (r repos) Preferences() port.PreferenceRepository       { return r.preferences }
func (r repos) Suppressions() port.SuppressionRepository     { return r.suppressions }
func (r repos) Inbox() port.InboxRepository                  { return r.inbox }
func (r repos) Outbox() port.OutboxRepository                { return r.outbox }
func (r repos) ProviderEvents() port.ProviderEventRepository { return r.providerEvents }
func (r repos) Ops() port.OpsRepository                      { return r.ops }
func (r repos) Maintenance() port.MaintenanceRepository      { return r.maintenance }
func (r repos) Ordering() port.OrderingRepository            { return r.ordering }
func (r repos) Tombstones() port.TombstoneRepository         { return r.tombstones }

// mapError converts driver errors into the shared vocabulary. Not-found is the
// only sentinel repositories rely on; everything else is wrapped so the
// caller can still errors.As into *pgconn.PgError when it must.
func mapError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return apperr.ErrNotFound
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return apperr.Wrap(apperr.CodeDependencyUnavailable, "postgres call cancelled", err)
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return apperr.Wrap(apperr.CodeInternal, fmt.Sprintf("postgres error %s", pgErr.Code), err)
	}
	return apperr.Wrap(apperr.CodeInternal, "postgres error", err)
}

// ---- small scan helpers -----------------------------------------------------

func ptr[T any](v T) *T { return &v }

// nullString maps "" to SQL NULL for optional text columns.
func nullString(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// collect drains rows with scan; it always closes rows.
func collect[T any](rows pgx.Rows, scan func(pgx.Rows) (T, error)) ([]T, error) {
	defer rows.Close()
	var out []T
	for rows.Next() {
		v, err := scan(rows)
		if err != nil {
			return nil, mapError(err)
		}
		out = append(out, v)
	}
	if err := rows.Err(); err != nil {
		return nil, mapError(err)
	}
	return out, nil
}

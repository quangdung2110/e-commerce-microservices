package postgres

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.MaintenanceRepository = (*MaintenanceRepository)(nil)

// MaintenanceRepository runs the partition and stats jobs of
// 09-data-model.md §9 on top of the SQL functions in 002_partitions.up.sql.
type MaintenanceRepository struct {
	q querier
}

// wakeChannel is the LISTEN/NOTIFY channel per lane (02-pipeline.md §5.3).
func wakeChannel(lane entity.Lane) string { return "delivery_ready_" + string(lane) }

func (r *MaintenanceRepository) EnsurePartitions(ctx context.Context, now time.Time, ahead int) error {
	if ahead < 0 {
		ahead = 0
	}
	var created int
	if err := r.q.QueryRow(ctx, `SELECT notification_ensure_partitions($1, $2)`, now, ahead).Scan(&created); err != nil {
		return mapError(err)
	}
	return nil
}

func (r *MaintenanceRepository) partitionedTables(ctx context.Context) ([]string, error) {
	rows, err := r.q.Query(ctx, `SELECT table_name FROM notification_partitioned_tables()`)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, func(rows pgx.Rows) (string, error) {
		var name string
		err := rows.Scan(&name)
		return name, err
	})
}

func (r *MaintenanceRepository) FutureMonths(ctx context.Context, now time.Time) (map[string]int, error) {
	tables, err := r.partitionedTables(ctx)
	if err != nil {
		return nil, err
	}
	out := make(map[string]int, len(tables))
	for _, table := range tables {
		var n int
		if err := r.q.QueryRow(ctx, `SELECT notification_partition_future_months($1, $2)`, table, now).Scan(&n); err != nil {
			return nil, mapError(err)
		}
		out[table] = n
	}
	return out, nil
}

// DropExpiredPartitions drops every partition whose whole range is older than
// the table's retention. For notification_delivery a partition still holding
// queue rows is refused and reported: max_age caps at 72 h so it should never
// happen, the check is the safety net of 09 §9.
func (r *MaintenanceRepository) DropExpiredPartitions(ctx context.Context, now time.Time, retentionDays map[string]int) ([]string, []string, error) {
	tables, err := r.partitionedTables(ctx)
	if err != nil {
		return nil, nil, err
	}
	var dropped, refused []string
	for _, table := range tables {
		days, ok := retentionDays[table]
		if !ok || days <= 0 {
			continue
		}
		before := now.Add(-time.Duration(days) * 24 * time.Hour)
		rows, err := r.q.Query(ctx, `SELECT partition_name FROM notification_expired_partitions($1, $2) ORDER BY range_end`, table, before)
		if err != nil {
			return dropped, refused, mapError(err)
		}
		partitions, err := collect(rows, func(rows pgx.Rows) (string, error) {
			var name string
			err := rows.Scan(&name)
			return name, err
		})
		if err != nil {
			return dropped, refused, err
		}
		for _, partition := range partitions {
			ident := pgx.Identifier{partition}.Sanitize()
			if table == "notification_delivery" {
				var busy bool
				q := fmt.Sprintf(`SELECT EXISTS (SELECT 1 FROM %s WHERE status IN ('PENDING', 'RETRY', 'SENDING'))`, ident)
				if err := r.q.QueryRow(ctx, q).Scan(&busy); err != nil {
					return dropped, refused, mapError(err)
				}
				if busy {
					refused = append(refused, partition)
					continue
				}
			}
			if _, err := r.q.Exec(ctx, `DROP TABLE `+ident); err != nil {
				return dropped, refused, mapError(err)
			}
			dropped = append(dropped, partition)
		}
	}
	return dropped, refused, nil
}

// DeadTupleRatio sums pg_stat_user_tables over the partitions of table (the
// parent of a partitioned table carries no tuples itself). 02-pipeline.md
// §5.7: bloat on the hot table is the slow failure of database-as-queue.
func (r *MaintenanceRepository) DeadTupleRatio(ctx context.Context, table string) (float64, error) {
	var dead, live int64
	err := r.q.QueryRow(ctx, `
		SELECT COALESCE(sum(s.n_dead_tup), 0), COALESCE(sum(s.n_live_tup), 0)
		FROM   pg_stat_user_tables s
		WHERE  s.relname = $1
		   OR  s.relid IN (
		         SELECT i.inhrelid
		         FROM   pg_inherits i
		         JOIN   pg_class p ON p.oid = i.inhparent
		         WHERE  p.relname = $1)`, table).Scan(&dead, &live)
	if err != nil {
		return 0, mapError(err)
	}
	if dead+live == 0 {
		return 0, nil
	}
	return float64(dead) / float64(dead+live), nil
}

// Notify wakes the deliver workers of a lane after the consumer commits
// (02-pipeline.md §4). The payload is empty: the row is in the table.
func (r *MaintenanceRepository) Notify(ctx context.Context, lane entity.Lane) error {
	_, err := r.q.Exec(ctx, `SELECT pg_notify($1, '')`, wakeChannel(lane))
	return mapError(err)
}

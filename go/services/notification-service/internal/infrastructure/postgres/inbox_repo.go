package postgres

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.InboxRepository = (*InboxRepository)(nil)

// InboxRepository is the in-app read model (09-data-model.md §7). Listing is
// keyset-paginated on (created_at DESC, id DESC), the exact order of
// idx_inbox_user_list, so a page never skips or repeats an item while new
// ones arrive.
type InboxRepository struct {
	q querier
}

const inboxColumns = `id, user_id, template, title, body, deep_link, created_at, read_at, archived_at`

func (r *InboxRepository) Insert(ctx context.Context, item *entity.InboxItem) error {
	const query = `
		INSERT INTO inbox_item (` + inboxColumns + `)
		VALUES ($1, $2, $3, $4, $5, $6, COALESCE($7::timestamptz, now()), $8, $9)`
	var createdAt *time.Time
	if !item.CreatedAt.IsZero() {
		createdAt = &item.CreatedAt
	}
	_, err := r.q.Exec(ctx, query, item.ID, item.UserID, item.Template, item.Title, item.Body,
		item.DeepLink, createdAt, item.ReadAt, item.ArchivedAt)
	return mapError(err)
}

// Get is scoped by user so one user can never read another's item by id.
func (r *InboxRepository) Get(ctx context.Context, userID, id uuid.UUID) (*entity.InboxItem, error) {
	return r.one(ctx, `SELECT `+inboxColumns+` FROM inbox_item WHERE user_id = $1 AND id = $2`, userID, id)
}

func (r *InboxRepository) List(ctx context.Context, userID uuid.UUID, cursor *entity.InboxCursor, limit int, unreadOnly bool) (*entity.InboxPage, error) {
	if limit <= 0 {
		limit = 20
	}
	var (
		cursorAt *time.Time
		cursorID *uuid.UUID
	)
	if cursor != nil {
		cursorAt, cursorID = &cursor.CreatedAt, &cursor.ID
	}
	// limit+1: the extra row tells us whether a next page exists without a
	// second count query.
	const query = `
		SELECT ` + inboxColumns + `
		FROM   inbox_item
		WHERE  user_id = $1
		  AND  archived_at IS NULL
		  AND  ($2::timestamptz IS NULL OR (created_at, id) < ($2::timestamptz, $3::uuid))
		  AND  (NOT $4::boolean OR read_at IS NULL)
		ORDER  BY created_at DESC, id DESC
		LIMIT  $5`
	items, err := r.many(ctx, query, userID, cursorAt, cursorID, unreadOnly, limit+1)
	if err != nil {
		return nil, err
	}
	page := &entity.InboxPage{Items: items}
	if len(items) > limit {
		page.Items = items[:limit]
		last := page.Items[limit-1]
		page.NextCursor = &entity.InboxCursor{CreatedAt: last.CreatedAt, ID: last.ID}
	}
	if page.Items == nil {
		page.Items = []entity.InboxItem{}
	}
	return page, nil
}

// SetRead flips read_at and reports whether the unread state actually
// changed, so the Redis counter is only moved on a real transition.
func (r *InboxRepository) SetRead(ctx context.Context, userID, id uuid.UUID, read bool, now time.Time) (*entity.InboxItem, bool, error) {
	const query = `
		WITH cur AS (
			SELECT id, created_at, read_at FROM inbox_item WHERE user_id = $1 AND id = $2 FOR UPDATE
		)
		UPDATE inbox_item i
		SET    read_at = CASE WHEN $3::boolean THEN COALESCE(i.read_at, $4::timestamptz) ELSE NULL END
		FROM   cur
		WHERE  i.id = cur.id AND i.created_at = cur.created_at
		RETURNING i.id, i.user_id, i.template, i.title, i.body, i.deep_link, i.created_at, i.read_at, i.archived_at,
		          (cur.read_at IS NULL) <> (i.read_at IS NULL)`
	rows, err := r.q.Query(ctx, query, userID, id, read, now)
	if err != nil {
		return nil, false, mapError(err)
	}
	type result struct {
		item    entity.InboxItem
		changed bool
	}
	list, err := collect(rows, func(rows pgx.Rows) (result, error) {
		var res result
		err := rows.Scan(&res.item.ID, &res.item.UserID, &res.item.Template, &res.item.Title, &res.item.Body,
			&res.item.DeepLink, &res.item.CreatedAt, &res.item.ReadAt, &res.item.ArchivedAt, &res.changed)
		return res, err
	})
	if err != nil {
		return nil, false, err
	}
	if len(list) == 0 {
		return nil, false, mapError(pgx.ErrNoRows)
	}
	return &list[0].item, list[0].changed, nil
}

// Archive hides the item from lists; an archived unread item leaves the
// unread count, hence wasUnread.
func (r *InboxRepository) Archive(ctx context.Context, userID, id uuid.UUID, now time.Time) (wasUnread bool, changed bool, err error) {
	const query = `
		UPDATE inbox_item
		SET    archived_at = $3
		WHERE  user_id = $1 AND id = $2 AND archived_at IS NULL
		RETURNING read_at IS NULL`
	err = r.q.QueryRow(ctx, query, userID, id, now).Scan(&wasUnread)
	if err == nil {
		return wasUnread, true, nil
	}
	if err != pgx.ErrNoRows {
		return false, false, mapError(err)
	}
	// Already archived, or not this user's item.
	if _, getErr := r.Get(ctx, userID, id); getErr != nil {
		return false, false, getErr
	}
	return false, false, nil
}

func (r *InboxRepository) MarkAllRead(ctx context.Context, userID uuid.UUID, now time.Time) (int64, error) {
	tag, err := r.q.Exec(ctx, `
		UPDATE inbox_item SET read_at = $2
		WHERE user_id = $1 AND read_at IS NULL AND archived_at IS NULL`, userID, now)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

// CountUnread is the recompute path when the Redis counter misses; it uses
// idx_inbox_user_unread.
func (r *InboxRepository) CountUnread(ctx context.Context, userID uuid.UUID) (int64, error) {
	var n int64
	err := r.q.QueryRow(ctx, `
		SELECT count(*) FROM inbox_item
		WHERE user_id = $1 AND read_at IS NULL AND archived_at IS NULL`, userID).Scan(&n)
	if err != nil {
		return 0, mapError(err)
	}
	return n, nil
}

func (r *InboxRepository) DeleteByUser(ctx context.Context, userID uuid.UUID) (int64, error) {
	tag, err := r.q.Exec(ctx, `DELETE FROM inbox_item WHERE user_id = $1`, userID)
	if err != nil {
		return 0, mapError(err)
	}
	return tag.RowsAffected(), nil
}

func (r *InboxRepository) one(ctx context.Context, query string, args ...any) (*entity.InboxItem, error) {
	list, err := r.many(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, mapError(pgx.ErrNoRows)
	}
	return &list[0], nil
}

func (r *InboxRepository) many(ctx context.Context, query string, args ...any) ([]entity.InboxItem, error) {
	rows, err := r.q.Query(ctx, query, args...)
	if err != nil {
		return nil, mapError(err)
	}
	return collect(rows, scanInboxItem)
}

func scanInboxItem(rows pgx.Rows) (entity.InboxItem, error) {
	var it entity.InboxItem
	err := rows.Scan(&it.ID, &it.UserID, &it.Template, &it.Title, &it.Body, &it.DeepLink,
		&it.CreatedAt, &it.ReadAt, &it.ArchivedAt)
	return it, err
}

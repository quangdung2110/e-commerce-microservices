package entity

import (
	"time"

	"github.com/google/uuid"
)

// InboxItem is the in-app read model (09-data-model.md §7). ID equals the
// delivery id of the in_app channel.
type InboxItem struct {
	ID         uuid.UUID
	UserID     uuid.UUID
	Template   string
	Title      string
	Body       string
	DeepLink   *string
	CreatedAt  time.Time
	ReadAt     *time.Time
	ArchivedAt *time.Time
}

// InboxCursor is the opaque (created_at DESC, id) pagination position.
type InboxCursor struct {
	CreatedAt time.Time
	ID        uuid.UUID
}

// InboxPage is one page of a user's inbox.
type InboxPage struct {
	Items      []InboxItem
	NextCursor *InboxCursor
}

package service

import (
	"context"

	"github.com/google/uuid"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// NotificationContact is the recipient view Notification Service resolves at
// send time for every notice Auth did not originate (order, review, cart). It
// carries an address and nothing else: no secret, no phone number.
type NotificationContact struct {
	UserID        uuid.UUID
	Email         string
	EmailVerified bool
	// Locale is nil in phase 1: Auth stores no locale, and Notification falls
	// back to its default when the field is null.
	Locale *string
}

// ContactService serves the internal notification-contact API.
type ContactService struct {
	accounts port.AccountRepository
}

// NewContactService wires the internal contact lookup.
func NewContactService(accounts port.AccountRepository) *ContactService {
	return &ContactService{accounts: accounts}
}

// Resolve returns the current contact of an account. An unknown account and a
// deleted one are indistinguishable to the caller: deletion anonymizes the
// address immediately (AUTH-BR-007), so there is no recipient either way and
// Notification marks the delivery FAILED (no_recipient) without retrying.
//
// A PENDING_VERIFICATION or LOCKED account is still a recipient; the caller
// reads EmailVerified to decide whether a bulk notice may go to it.
func (s *ContactService) Resolve(ctx context.Context, userID uuid.UUID) (*NotificationContact, error) {
	account, err := s.accounts.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if account.IsDeleted() || account.Email.IsZero() {
		return nil, apperr.ErrAccountNotFound
	}

	return &NotificationContact{
		UserID:        account.ID,
		Email:         account.Email.String(),
		EmailVerified: account.IsEmailVerified(),
		Locale:        nil,
	}, nil
}

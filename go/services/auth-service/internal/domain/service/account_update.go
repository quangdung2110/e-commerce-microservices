package service

import (
	"context"
	"errors"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// maxAccountUpdateAttempts bounds how often a change is re-applied after losing
// the optimistic-concurrency race. Losing four times in a row means the account
// is under constant write pressure; the caller then gets a retryable error.
const maxAccountUpdateAttempts = 4

// accountMutation applies one change to an account and reports whether anything
// needs saving. It runs again on a freshly loaded copy whenever the save loses a
// race, so it must derive everything from the account it is given and re-check
// the preconditions of the change on every call. It must not do anything
// irreversible (publish, consume a secret) itself.
type accountMutation func(account *entity.Account) (changed bool, err error)

// updateAccount applies mutate to the account and saves it without ever writing
// over a newer version of the row.
//
// Auth services read an account, spend time (a 100 ms Argon2 verification, a
// Redis round trip) and then save the whole row. Without the version check, a
// login that began before a password change, an email change or a deletion
// committed would write the stale password hash, email or status back. When the
// repository reports ErrConcurrentUpdate the account is reloaded and mutate is
// applied to the current row, so the change is neither lost nor allowed to
// resurrect old state.
//
// It returns the copy that is now stored and whether a write happened.
func updateAccount(ctx context.Context, accounts port.AccountRepository, account *entity.Account, mutate accountMutation) (*entity.Account, bool, error) {
	current := account
	for attempt := 1; ; attempt++ {
		changed, err := mutate(current)
		if err != nil {
			return current, false, err
		}
		if !changed {
			return current, false, nil
		}

		err = accounts.Update(ctx, current)
		if err == nil {
			return current, true, nil
		}
		if !errors.Is(err, apperr.ErrConcurrentUpdate) || attempt >= maxAccountUpdateAttempts {
			return current, false, err
		}

		current, err = accounts.GetByID(ctx, current.ID)
		if err != nil {
			return nil, false, err
		}
	}
}

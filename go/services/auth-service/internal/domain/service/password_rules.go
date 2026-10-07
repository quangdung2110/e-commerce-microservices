package service

import (
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// validatePasswordFormat checks the schema-level rules of a new password: the
// configured length bounds and the confirmation match. Both map to 400 because
// the request payload itself is wrong.
func validatePasswordFormat(policy vo.PasswordPolicy, field, password, confirm string) error {
	if err := policy.ValidateFormat(field, password); err != nil {
		return err
	}
	if password != confirm {
		return apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "confirm_password",
			Message: "must match the new password",
		})
	}
	return nil
}

// validatePasswordStrength checks the semantic policy rules. The caller decides
// how to surface them: registration and reset report 400 because their contract
// has no 422, while the change-password endpoint reports 422.
func validatePasswordStrength(policy vo.PasswordPolicy, password string, email vo.Email) error {
	return policy.ValidateStrength(password, email)
}

// asValidation converts a password-policy violation into a request-validation
// error for the endpoints whose contract does not define 422.
func asValidation(err error, field string) error {
	if err == nil {
		return nil
	}
	appErr := apperr.From(err)
	if appErr.Code != apperr.CodePasswordPolicy {
		return err
	}
	return apperr.ErrValidation.WithFields(apperr.FieldError{
		Field:   field,
		Message: appErr.Detail,
	})
}

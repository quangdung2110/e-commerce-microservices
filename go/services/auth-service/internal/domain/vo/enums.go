// Package vo holds the value objects of the Auth domain. Every enum mirrors a
// PostgreSQL enum from docs/auth/schema/0001_initial_auth_schema.sql, so an
// unknown value must never reach the database.
package vo

import "fmt"

// AccountStatus mirrors auth.account_status.
type AccountStatus string

const (
	AccountPendingVerification AccountStatus = "PENDING_VERIFICATION"
	AccountActive              AccountStatus = "ACTIVE"
	AccountLocked              AccountStatus = "LOCKED"
	AccountDeleted             AccountStatus = "DELETED"
)

func (s AccountStatus) String() string { return string(s) }

func (s AccountStatus) Valid() bool {
	switch s {
	case AccountPendingVerification, AccountActive, AccountLocked, AccountDeleted:
		return true
	}
	return false
}

// ParseAccountStatus converts a database value into the enum.
func ParseAccountStatus(value string) (AccountStatus, error) {
	status := AccountStatus(value)
	if !status.Valid() {
		return "", fmt.Errorf("vo: unknown account status %q", value)
	}
	return status, nil
}

// Role mirrors auth.role_code. An admin also holds USER, so every buyer
// capability stays available to them.
type Role string

const (
	RoleUser  Role = "USER"
	RoleAdmin Role = "ADMIN"
)

func (r Role) String() string { return string(r) }

func (r Role) Valid() bool {
	return r == RoleUser || r == RoleAdmin
}

// ParseRole converts a database or header value into the enum.
func ParseRole(value string) (Role, error) {
	role := Role(value)
	if !role.Valid() {
		return "", fmt.Errorf("vo: unknown role %q", value)
	}
	return role, nil
}

// RoleStrings renders roles for a JWT claim or an event payload.
func RoleStrings(roles []Role) []string {
	out := make([]string, 0, len(roles))
	for _, role := range roles {
		out = append(out, role.String())
	}
	return out
}

// HasRole reports whether the set contains the wanted role.
func HasRole(roles []Role, wanted Role) bool {
	for _, role := range roles {
		if role == wanted {
			return true
		}
	}
	return false
}

// DeletionReason mirrors auth.deletion_reason.
type DeletionReason string

const (
	DeletionGDPRRequest   DeletionReason = "GDPR_REQUEST"
	DeletionAdminDeletion DeletionReason = "ADMIN_DELETION"
)

func (d DeletionReason) String() string { return string(d) }

func (d DeletionReason) Valid() bool {
	return d == DeletionGDPRRequest || d == DeletionAdminDeletion
}

// ParseDeletionReason converts a database value into the enum.
func ParseDeletionReason(value string) (DeletionReason, error) {
	reason := DeletionReason(value)
	if !reason.Valid() {
		return "", fmt.Errorf("vo: unknown deletion reason %q", value)
	}
	return reason, nil
}

// JwtKeyStatus mirrors auth.jwt_key_status.
type JwtKeyStatus string

const (
	JwtKeyActive   JwtKeyStatus = "ACTIVE"
	JwtKeyPhaseOut JwtKeyStatus = "PHASE_OUT"
	JwtKeyRetired  JwtKeyStatus = "RETIRED"
)

func (s JwtKeyStatus) String() string { return string(s) }

func (s JwtKeyStatus) Valid() bool {
	switch s {
	case JwtKeyActive, JwtKeyPhaseOut, JwtKeyRetired:
		return true
	}
	return false
}

// ParseJwtKeyStatus converts a database value into the enum.
func ParseJwtKeyStatus(value string) (JwtKeyStatus, error) {
	status := JwtKeyStatus(value)
	if !status.Valid() {
		return "", fmt.Errorf("vo: unknown jwt key status %q", value)
	}
	return status, nil
}

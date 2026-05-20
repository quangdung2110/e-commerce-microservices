package vo

type UserStatus string

const (
	StatusPendingVerification UserStatus = "PENDING_VERIFICATION"
	StatusActive              UserStatus = "ACTIVE"
	StatusLocked              UserStatus = "LOCKED"
	StatusBanned              UserStatus = "BANNED"
)

func (s UserStatus) IsActive() bool {
	return s == StatusActive
}

func (s UserStatus) IsLocked() bool {
	return s == StatusLocked
}

func (s UserStatus) IsBanned() bool {
	return s == StatusBanned
}
